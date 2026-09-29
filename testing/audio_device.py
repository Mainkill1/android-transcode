#!/usr/bin/env python3
"""Test-only host entry point; no command endpoint is installed in the release app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

CASES={
 'capabilities':('dev.forma.app.NativeAccelerationSmokeTest','h264-smoke.json'),
 'dsp':('dev.forma.app.audio.AudioDspDeviceTest','audio-dsp.json'),
 'analysis':('dev.forma.app.audio.AudioAnalysisDeviceTest','audio-analysis.json'),
 'preview':('dev.forma.app.audio.AudioPreviewDeviceTest','audio-preview.json'),
 'jobs':('dev.forma.app.audio.AudioJobDeviceTest','audio-jobs.json'),
 'ui':('dev.forma.app.audio.AudioEditorComposeTest',None),
}
def require_success(code,output):
    match=re.search(r'OK \((\d+) tests?\)',output)
    if code or not match or int(match[1])<1 or any(s in output for s in ('FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed','INSTRUMENTATION_ABORTED')):
        raise RuntimeError('Instrumentation did not pass:\n'+output[-3000:])
def checksum(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''):h.update(block)
    return h.hexdigest()
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=tuple(CASES)+('all',))
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--timeout',type=int,default=180)
    args=parser.parse_args()
    if not args.serial: parser.error('Supply --serial or ANDROID_SERIAL.')
    root=Path(__file__).resolve().parents[1]
    run_id=str(uuid.uuid4());directory=root/'testing'/'results'/run_id;directory.mkdir(parents=True)
    report={'schema':1,'runId':run_id,'serial':args.serial,'passed':False,'cases':[]}
    def adb(*command):
        result=subprocess.run(['adb','-s',args.serial,*command],capture_output=True,timeout=args.timeout)
        if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace')
    try:
        if adb('get-state').strip()!='device':raise RuntimeError('The target is not an authorized connected device.')
        apk=root/'app/build/outputs/apk/debug/app-debug.apk'
        test_apk=root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        report['apkSha256']=checksum(apk);report['testApkSha256']=checksum(test_apk)
        report['repoSha']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        report['workingDiffSha256']=hashlib.sha256(subprocess.check_output(['git','diff','HEAD'],cwd=root)).hexdigest()
        report['device']={key:adb('shell','getprop',prop).strip() for key,prop in (
            ('model','ro.product.model'),('sdk','ro.build.version.sdk'),('abi','ro.product.cpu.abi'),('fingerprint','ro.build.fingerprint'))}
        report['device']['pageSize']=adb('shell','getconf','PAGESIZE').strip()
        # Install the matching pair so the artifact hashes describe exactly what is exercised.
        adb('install','-r',str(apk));adb('install','-r',str(test_apk))
        names=list(CASES) if args.command=='all' else [args.command]
        for name in names:
            clazz,filename=CASES[name]
            if filename:adb('shell','run-as','dev.forma.transcode','rm','-f','files/native-readiness/'+filename)
            start=time.monotonic()
            process=subprocess.run(['adb','-s',args.serial,'shell','am','instrument','-w','-r','-e','formaNative','true','-e','class',clazz,
                'dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=args.timeout)
            output=process.stdout+process.stderr;(directory/(name+'.log')).write_text(output)
            require_success(process.returncode,output)
            case={'name':name,'elapsedMs':round((time.monotonic()-start)*1000)}
            if filename:
                result=json.loads(adb('exec-out','run-as','dev.forma.transcode','cat','files/native-readiness/'+filename),
                    parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite measurement '+value)))
                if not result.get('passed',result.get('smokePassed',False)):raise RuntimeError('Native report did not pass: '+str(result))
                case['nativeReport']=result
            report['cases'].append(case)
        report['passed']=True
    except (OSError,RuntimeError,ValueError,subprocess.SubprocessError) as error:report['error']=str(error)
    finally:
        path=directory/'report.json';path.write_text(json.dumps(report,indent=2,allow_nan=False));print(path)
    return 0 if report['passed'] else 1
if __name__=='__main__':sys.exit(main())

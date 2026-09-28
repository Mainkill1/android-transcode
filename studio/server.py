#!/usr/bin/env python3
"""Local-only Forma HTML workbench. Requires Python 3.10+, ffmpeg and ffprobe.
No Python packages, external web service, browser codec encoder or shell execution.
"""
from __future__ import annotations
import argparse, copy, http.client, ipaddress, json, mimetypes, os, re, secrets, shutil
import socket, ssl, subprocess, threading, time, uuid, webbrowser
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, urljoin, parse_qs, unquote
from planner import default_settings, make_plan, build_command, SAFE_DEMUXERS, VIDEO_ENCODERS, VIDEO_FORMATS, AUDIO_FORMATS

ROOT=Path(__file__).resolve().parent
MAX_FILE=2*1024**3
MAX_JSON=256*1024

def inspect_media(path: Path, ffprobe: str='ffprobe') -> dict:
    result=subprocess.run([ffprobe,'-v','error','-protocol_whitelist','file,pipe','-format_whitelist',SAFE_DEMUXERS,
        '-show_format','-show_streams','-of','json',str(path)],capture_output=True,text=True,timeout=45)
    if result.returncode:raise ValueError('FFprobe could not open this as a supported media file. Playlists, webpages and live streams are not supported by this runner.')
    data=json.loads(result.stdout)
    videos=[s for s in data.get('streams',[]) if s.get('codec_type')=='video' and not s.get('disposition',{}).get('attached_pic')]
    audios=[s for s in data.get('streams',[]) if s.get('codec_type')=='audio']
    if not videos and not audios:raise ValueError('No decodable video or audio streams were found.')
    v=videos[0] if videos else {}
    try:duration=float(data.get('format',{}).get('duration') or max(float(s.get('duration') or 0) for s in videos+audios))
    except (ValueError,TypeError):duration=0
    if not 0<duration<=86400*7:raise ValueError('A finite media duration is required. Live streams are not supported.')
    w=int(v.get('width',0));h=int(v.get('height',0));sar=1.0
    try:
        a,b=v.get('sample_aspect_ratio','1:1').split(':');sar=float(a)/float(b)
        if not .05<=sar<=20:sar=1.0
    except (ValueError,ZeroDivisionError):sar=1.0
    w=round(w*sar)
    rotation=0
    for side in v.get('side_data_list',[]):
        if 'rotation' in side:rotation=int(round(float(side['rotation'])))%360
    if rotation in (90,270):w,h=h,w
    return dict(duration=duration,width=w,height=h,sar=sar,rotation=rotation,
        hasVideo=bool(videos),hasAudio=bool(audios),videoIndex=v.get('index',0),videoCodec=v.get('codec_name'),
        hdr=v.get('color_transfer') in ('smpte2084','arib-std-b67'),
        audioTracks=[dict(index=s['index'],codec=s.get('codec_name'),channels=s.get('channels',2),
        sampleRate=int(s.get('sample_rate') or 48000),language=s.get('tags',{}).get('language','und')) for s in audios])

def encoder_capabilities(ffmpeg: str) -> set[str]:
    output=subprocess.run([ffmpeg,'-hide_banner','-encoders'],capture_output=True,text=True,timeout=15)
    return {m.group(1) for m in re.finditer(r'^\s+[VAS][A-Z.]{5}\s+(\S+)',output.stdout,re.M)}

class PinnedHTTP(http.client.HTTPConnection):
    def __init__(self,host,port,ip):super().__init__(host,port,timeout=30);self.ip=ip
    def connect(self):self.sock=socket.create_connection((self.ip,self.port),self.timeout)
class PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self,host,port,ip):super().__init__(host,port,timeout=30,context=ssl.create_default_context());self.ip=ip
    def connect(self):
        raw=socket.create_connection((self.ip,self.port),self.timeout)
        try:self.sock=self._context.wrap_socket(raw,server_hostname=self.host)
        except Exception:raw.close();raise

def validate_url(raw: str,allow_private: bool=False):
    if not isinstance(raw,str) or len(raw)>8192 or re.search(r'[\x00-\x20\x7f\\]',raw):raise ValueError('Use a complete HTTP(S) media URL without whitespace.')
    u=urlsplit(raw)
    if u.scheme not in ('http','https') or not u.hostname:raise ValueError('Use a directly downloadable HTTP or HTTPS media file.')
    if u.username or u.password:raise ValueError('Embedded URL credentials are not supported.')
    if u.fragment:raise ValueError('Remove the URL fragment after #.')
    if u.path.lower().endswith(('.m3u8','.mpd')):raise ValueError('This runner accepts media files, not streaming playlists.')
    port=u.port or (443 if u.scheme=='https' else 80)
    if not 1<=port<=65535:raise ValueError('Invalid URL port.')
    addresses=list(dict.fromkeys(x[4][0] for x in socket.getaddrinfo(u.hostname,port,type=socket.SOCK_STREAM)))
    if not addresses:raise ValueError('The media host could not be resolved.')
    if not allow_private and any(not ipaddress.ip_address(x).is_global for x in addresses):
        raise ValueError('Private or local URL addresses are disabled. Start with --allow-private-urls only for trusted LAN media.')
    return u,port,addresses[0]

def download_url(raw: str,destination: Path,allow_private: bool=False) -> str:
    start=time.monotonic();url=raw
    for redirect in range(6):
        u,port,ip=validate_url(url,allow_private)
        conn=(PinnedHTTPS if u.scheme=='https' else PinnedHTTP)(u.hostname,port,ip)
        try:
            conn.request('GET',u.path or '/' if not u.query else (u.path or '/')+'?'+u.query,
                headers={'User-Agent':'Forma-local-workbench/1.0','Accept':'*/*','Accept-Encoding':'identity'})
            response=conn.getresponse()
            if response.status in (301,302,303,307,308):
                if redirect==5:raise ValueError('Too many media URL redirects.')
                location=response.getheader('Location')
                if not location:raise ValueError('The media redirect has no destination.')
                url=urljoin(url,location);continue
            if response.status!=200:raise ValueError(f'The media server returned HTTP {response.status}.')
            if response.getheader('Content-Type','').split(';')[0].lower() in ('text/html','application/xhtml+xml','application/vnd.apple.mpegurl','application/x-mpegurl','application/dash+xml'):
                raise ValueError('The URL returned a webpage or stream playlist, not a media file.')
            length=response.getheader('Content-Length')
            if length and int(length)>MAX_FILE:raise ValueError('This file exceeds the 2 GiB workbench limit.')
            total=0
            with destination.open('wb') as out:
                while True:
                    chunk=response.read(256*1024)
                    if not chunk:break
                    total+=len(chunk)
                    if total>MAX_FILE or time.monotonic()-start>600:raise ValueError('The download exceeded the 2 GiB size or ten-minute limit.')
                    out.write(chunk)
            if not total:raise ValueError('The media download was empty.')
            return Path(unquote(u.path)).name or 'remote-media'
        finally:conn.close()
    raise ValueError('The media URL could not be retrieved.')

class Workbench:
    def __init__(self,data: Path,allow_private: bool=False):
        self.data=data;data.mkdir(parents=True,exist_ok=True)
        self.ffmpeg=shutil.which('ffmpeg');self.ffprobe=shutil.which('ffprobe')
        self.encoders=encoder_capabilities(self.ffmpeg) if self.ffmpeg and self.ffprobe else set()
        self.token=secrets.token_urlsafe(32);self.allow_private=allow_private
        self.sources={};self.jobs={};self.pending=deque();self.lock=threading.RLock();self.wake=threading.Event()
        self.stopping=False;self.worker=threading.Thread(target=self.run,daemon=True);self.worker.start()
    def require_engine(self):
        if not self.ffmpeg or not self.ffprobe:raise ValueError('Install ffmpeg and ffprobe on PATH, then restart the local runner.')
    def register(self,path: Path,name: str) -> dict:
        self.require_engine();info=inspect_media(path,self.ffprobe)
        identity=uuid.uuid4().hex
        source=dict(id=identity,name=Path(name.replace('\\','/')).name[:180],size=path.stat().st_size,path=str(path),**info)
        with self.lock:self.sources[identity]=source
        return self.public_source(source)
    @staticmethod
    def public_source(src):return {k:v for k,v in src.items() if k!='path'}
    def source(self,identity):
        with self.lock:src=self.sources.get(identity)
        if not src:raise ValueError('This source is no longer available. Select it again.')
        return src
    def add_job(self,sourceid,settings):
        self.require_engine();src=self.source(sourceid);plan=make_plan(src,settings,self.encoders)
        identity=uuid.uuid4().hex;out=self.data/(identity+'.'+plan['extension'])
        job=dict(id=identity,status='queued',progress=0,outputName=plan['outputName'],sourceName=src['name'],sourceId=src['id'],
            duration=plan['duration'],width=plan['width'],height=plan['height'],mode=plan['settings']['mode'],
            settings=copy.deepcopy(plan['settings']),plan=plan,input=src['path'],output=str(out),error='',cancel=False,process=None)
        with self.lock:self.jobs[identity]=job;self.pending.append(identity);self.wake.set()
        return self.public_job(job)
    @staticmethod
    def public_job(job):
        return {k:v for k,v in job.items() if k not in ('plan','input','output','process','cancel')}
    def cancel(self,identity):
        with self.lock:
            job=self.jobs.get(identity)
            if not job:raise ValueError('Unknown job.')
            if job['status'] in ('completed','failed','cancelled'):return self.public_job(job)
            job['cancel']=True;job['status']='cancelled';p=job['process']
            if p and p.poll() is None:
                try:p.kill()
                except ProcessLookupError:pass
            return self.public_job(job)
    def run(self):
        while not self.stopping:
            self.wake.wait(.5)
            with self.lock:
                if not self.pending:self.wake.clear();continue
                job=self.jobs[self.pending.popleft()]
                if job['cancel']:continue
                job['status']='encoding'
            logpath=self.data/(job['id']+'.log')
            try:
                args=build_command(job['plan'],job['input'],job['output'],self.ffmpeg)
                with logpath.open('w+',encoding='utf8') as log:
                    with self.lock:
                        if job['cancel']:continue
                        p=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1)
                        job['process']=p
                    for line in p.stdout:
                        if line.startswith('out_time_us='):
                            try:ratio=int(line.split('=',1)[1])/1_000_000/job['duration']
                            except ValueError:continue
                            with self.lock:job['progress']=max(0,min(.99,ratio))
                    code=p.wait();p.stdout.close()
                    with self.lock:job['process']=None
                    if job['cancel']:continue
                    if code:
                        log.seek(0);detail=log.read()[-3000:]
                        # Avoid exposing private filesystem paths in the UI.
                        detail=detail.replace(job['input'],'[source]').replace(job['output'],'[output]')
                        raise ValueError(detail or 'FFmpeg exited without a valid output.')
                with self.lock:
                    if job['cancel']:continue
                    job['status']='verifying'
                result=inspect_media(Path(job['output']),self.ffprobe)
                plan=job['plan']
                if bool(result['hasVideo'])!=bool(plan['videoEncoder']) or bool(result['hasAudio'])!=bool(plan['audioEncoder']):raise ValueError('The output stream verification failed.')
                if abs(result['duration']-plan['duration'])>max(.35,plan['duration']*.02):raise ValueError('The output duration verification failed.')
                if plan['videoEncoder'] and (result['width'],result['height'])!=(plan['width'],plan['height']):raise ValueError('The output picture dimensions do not match the plan.')
                with self.lock:
                    if not job['cancel']:
                        job.update(status='completed',progress=1,size=Path(job['output']).stat().st_size,verified=True)
            except Exception as exc:
                with self.lock:
                    if not job['cancel']:job.update(status='failed',error=str(exc)[:3500])
            finally:
                if job['status']!='completed':Path(job['output']).unlink(missing_ok=True)
    def close(self):
        self.stopping=True
        for identity in list(self.jobs):self.cancel(identity)
        self.wake.set();self.worker.join(timeout=5)

class Handler(BaseHTTPRequestHandler):
    server_version='FormaLocal/1.0'
    @property
    def app(self):return self.server.app
    def log_message(self,fmt,*args):pass # Do not log URL queries or private filenames.
    def guard(self,mutate=False):
        port=self.server.server_port;allowed={f'127.0.0.1:{port}',f'localhost:{port}'}
        if self.headers.get('Host') not in allowed:raise PermissionError('Unrecognized host.')
        origin=self.headers.get('Origin')
        if origin and origin not in {f'http://{host}' for host in allowed}:raise PermissionError('Cross-origin requests are not allowed.')
        if self.headers.get('Sec-Fetch-Site')=='cross-site':raise PermissionError('Cross-site requests are not allowed.')
        if mutate and not secrets.compare_digest(self.headers.get('X-Forma-Token',''),self.app.token):raise PermissionError('Missing local session token. Reload the app.')
    def respond(self,data,status=200):
        payload=json.dumps(data,allow_nan=False).encode('utf8');self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(payload)))
        self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.end_headers();self.wfile.write(payload)
    def json_body(self):
        n=int(self.headers.get('Content-Length','0'))
        if not 0<n<=MAX_JSON:raise ValueError('Invalid JSON request size.')
        def invalid_constant(x):raise ValueError('Non-finite JSON values are not supported.')
        data=json.loads(self.rfile.read(n),parse_constant=invalid_constant)
        if not isinstance(data,dict):raise ValueError('Request must be a JSON object.')
        return data
    def send_file(self,path,download=None):
        path=Path(path)
        if not path.is_file():return self.respond({'error':'File not found.'},404)
        length=path.stat().st_size;start=0;end=length-1;partial=False
        if self.headers.get('Range'):
            match=re.fullmatch(r'bytes=(\d*)-(\d*)',self.headers['Range'])
            if not match or not any(match.groups()):return self.respond({'error':'Invalid byte range.'},416)
            if match[1]:start=int(match[1]);end=min(end,int(match[2])) if match[2] else end
            else:start=max(0,length-int(match[2]))
            if start>end or start>=length:
                self.send_response(416);self.send_header('Content-Range',f'bytes */{length}');self.end_headers();return
            partial=True
        self.send_response(206 if partial else 200)
        self.send_header('Content-Type',mimetypes.guess_type(str(path))[0] or 'application/octet-stream')
        self.send_header('Content-Length',str(end-start+1));self.send_header('Accept-Ranges','bytes')
        self.send_header('X-Content-Type-Options','nosniff');self.send_header('Cache-Control','no-store')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('X-Frame-Options','DENY')
        if partial:self.send_header('Content-Range',f'bytes {start}-{end}/{length}')
        if download:self.send_header('Content-Disposition','attachment; filename="'+re.sub(r'[^A-Za-z0-9._ -]','_',download)+'"')
        self.end_headers()
        try:
            with path.open('rb') as f:
                f.seek(start);remaining=end-start+1
                while remaining:
                    data=f.read(min(256*1024,remaining))
                    if not data:break
                    self.wfile.write(data);remaining-=len(data)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_GET(self):
        try:
            self.guard();url=urlsplit(self.path);path=url.path
            if path=='/api/session':
                return self.respond(dict(token=self.app.token,available=bool(self.app.ffmpeg and self.app.ffprobe),
                    encoders=sorted(self.app.encoders),videoFormats=VIDEO_FORMATS,audioFormats=AUDIO_FORMATS,
                    allowPrivateUrls=self.app.allow_private))
            if path=='/api/jobs':
                with self.app.lock:jobs=[self.app.public_job(j) for j in self.app.jobs.values()]
                return self.respond(jobs)
            if path.startswith('/media/'):
                src=self.app.source(path.split('/')[-1]);return self.send_file(src['path'])
            if path.startswith('/output/'):
                identity=path.split('/')[-1];job=self.app.jobs.get(identity)
                if not job or job['status']!='completed':return self.respond({'error':'No verified output is available.'},404)
                return self.send_file(job['output'],job['outputName'] if 'download' in parse_qs(url.query,keep_blank_values=True) else None)
            files={'/':ROOT/'web/index.html','/index.html':ROOT/'web/index.html','/ui.js':ROOT/'web/ui.js','/ui.css':ROOT/'web/ui.css','/touch.css':ROOT/'web/touch.css',
                   '/sample.mp4':ROOT/'media/sample.mp4','/sample.wav':ROOT/'media/sample.wav'}
            if path in files:return self.send_file(files[path])
            return self.respond({'error':'Not found.'},404)
        except PermissionError as exc:self.respond({'error':str(exc)},403)
        except Exception as exc:self.respond({'error':str(exc)},400)
    def do_POST(self):
        target=None
        try:
            self.guard(mutate=True);url=urlsplit(self.path);path=url.path
            if path=='/api/upload':
                self.app.require_engine();n=int(self.headers.get('Content-Length','0'))
                if not 0<n<=MAX_FILE:raise ValueError('Select a nonempty media file up to 2 GiB.')
                name=parse_qs(url.query).get('name',['media'])[0]
                suffix=Path(name).suffix.lower();suffix=suffix if re.fullmatch(r'\.[a-z0-9]{1,6}',suffix) else '.bin'
                target=self.app.data/(uuid.uuid4().hex+suffix)
                remaining=n
                with target.open('wb') as f:
                    while remaining:
                        data=self.rfile.read(min(256*1024,remaining))
                        if not data:raise ValueError('The file upload was interrupted.')
                        remaining-=len(data);f.write(data)
                result=self.app.register(target,name);target=None;return self.respond(result,201)
            data=self.json_body()
            if path=='/api/url':
                self.app.require_engine();target=self.app.data/(uuid.uuid4().hex+'.bin')
                name=download_url(data.get('url',''),target,self.app.allow_private)
                suffix=Path(name).suffix.lower()
                if re.fullmatch(r'\.[a-z0-9]{1,6}',suffix):
                    new=target.with_suffix(suffix);target.rename(new);target=new
                result=self.app.register(target,name);target=None;return self.respond(result,201)
            if path=='/api/sample':
                kind='wav' if data.get('kind')=='audio' else 'mp4'
                return self.respond(self.app.register(ROOT/'media'/('sample.'+kind),'Studio sample.'+kind),201)
            if path=='/api/plan':
                src=self.app.source(data.get('sourceId'));plan=make_plan(src,data.get('settings',{}),self.app.encoders)
                return self.respond({k:v for k,v in plan.items() if k!='filtergraph'})
            if path=='/api/jobs':return self.respond(self.app.add_job(data.get('sourceId'),data.get('settings',{})),201)
            match=re.fullmatch(r'/api/jobs/([a-f0-9]{32})/cancel',path)
            if match:return self.respond(self.app.cancel(match[1]))
            return self.respond({'error':'Not found.'},404)
        except PermissionError as exc:self.respond({'error':str(exc)},403)
        except Exception as exc:self.respond({'error':str(exc)},400)
        finally:
            if target:target.unlink(missing_ok=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',type=int,default=8765);p.add_argument('--data-dir',type=Path,default=ROOT/'.forma-work')
    p.add_argument('--no-browser',action='store_true');p.add_argument('--allow-private-urls',action='store_true')
    args=p.parse_args();app=Workbench(args.data_dir,args.allow_private_urls)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler);server.app=app
    print(f'Forma: http://127.0.0.1:{server.server_port}',flush=True)
    print('Real FFmpeg exports enabled.' if app.ffmpeg and app.ffprobe else 'FFmpeg is missing. Install ffmpeg and ffprobe on PATH and restart.',flush=True)
    print(f'Files stay in {args.data_dir}. Queue is session-only. Press Ctrl+C to stop.',flush=True)
    if not args.no_browser:webbrowser.open(f'http://127.0.0.1:{server.server_port}')
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:app.close();server.server_close()
if __name__=='__main__':main()

"""UI checks. --inline uses set_content because this renderer blocks URL navigation.
With --inline, an explicit fetch transport forwards UI API requests to a REAL loopback
server/FFmpeg process through Python. No encoder response, progress, or output is mocked.
Normal mode uses the actual HTTP origin directly. Downloads are verified by API tests.
"""
from pathlib import Path
import argparse,base64,http.client,json,os,sys,threading,time,traceback
from playwright.sync_api import sync_playwright,expect
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from server import Workbench,Handler,ThreadingHTTPServer
INLINE='--inline' in sys.argv
results=[]
def check(name,fn):
    try:fn();results.append({'name':name,'passed':True});print('PASS',name,flush=True)
    except Exception as e:results.append({'name':name,'passed':False,'error':str(e)});print('FAIL',name,str(e),flush=True);raise

def assert_(value,message='Assertion failed'):
    assert value,message

def run():
    (ROOT/'screenshots').mkdir(exist_ok=True)
    work=ROOT/'.test-browser-work';app=Workbench(work,allow_private=True)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.app=app
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    url=f'http://127.0.0.1:{server.server_port}'
    html=(ROOT/'forma-studio.html').read_text()
    shim="""<script>
      const realFetch=window.fetch.bind(window);
      window.fetch=async function(path,options={}){
        if(/^(data:|blob:)/.test(String(path)))return realFetch(path,options);
        let bytes=null;
        if(options.body!==undefined){
          let b=options.body instanceof Blob?new Uint8Array(await options.body.arrayBuffer()):new TextEncoder().encode(options.body);
          let str='';for(let i=0;i<b.length;i+=32768)str+=String.fromCharCode(...b.subarray(i,i+32768));bytes=btoa(str);
        }
        const r=await window.__formaTestHttp({path:String(path),method:options.method||'GET',headers:options.headers||{},body:bytes});
        const b=Uint8Array.from(atob(r.body),c=>c.charCodeAt(0));
        return new Response(b,{status:r.status,headers:r.headers});
      };
      </script>"""
    def transport(payload):
        conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=30)
        conn.request(payload['method'],payload['path'],body=base64.b64decode(payload['body']) if payload['body'] else None,headers=payload['headers'])
        r=conn.getresponse();body=r.read();reply={'status':r.status,'headers':dict(r.getheaders()),'body':base64.b64encode(body).decode()};conn.close();return reply
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=os.environ.get('FORMA_BROWSER') or ('/usr/bin/chromium' if Path('/usr/bin/chromium').exists() else None),headless=True,args=['--no-sandbox'])
        page=browser.new_page(viewport={'width':390,'height':844},reduced_motion='reduce')
        errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        expect.set_options(timeout=4000)
        try:
            page.set_content(html)
            page.wait_for_function('FormaStudio.state.engineChecked')
            check('minimal home: file and URL, no conversion controls',lambda:(expect(page.locator('#select-media')).to_be_visible(),expect(page.locator('#media-url')).to_be_visible(),expect(page.locator('#workspace')).to_be_hidden(),expect(page.locator('#export-bar')).to_be_hidden()))
            page.screenshot(path=str(ROOT/'screenshots/mobile-home.png'))
            page.locator('[data-sample=video]').click();page.wait_for_function('FormaStudio.state.source?.duration')
            check('standalone sample uses actual playable media',lambda:assert_(page.evaluate('FormaStudio.state.source.duration')==8))
            check('standalone export is disabled, not simulated',lambda:expect(page.locator('#convert')).to_be_disabled())
            page.locator('#tab-edit').click();page.wait_for_function("document.querySelector('#preview-canvas').getContext('2d').getImageData(40,40,1,1).data[3]===255")
            check('first editor frame has decoded pixels without pressing play',lambda:assert_(page.evaluate("document.querySelector('#preview-canvas').getContext('2d').getImageData(40,40,1,1).data[3]")==255))
            page.locator('#menu').click()
            check('phone shelf is modal and main is inert',lambda:assert_(page.locator('#main').evaluate('(x)=>x.inert') and page.locator('#shelf').get_attribute('aria-modal')=='true'))
            page.keyboard.press('Escape');check('Escape dismisses phone shelf',lambda:expect(page.locator('#shelf')).to_be_hidden())
            page.locator('#file-input').set_input_files(str(ROOT/'media/sample.wav'));page.wait_for_function('FormaStudio.state.source?.hasVideo===false')
            check('audio source starts on Audio, not Video',lambda:(expect(page.locator('#tab-audio')).to_have_attribute('aria-selected','true'),expect(page.locator('#tab-video')).to_be_disabled()))
            page.locator('[data-setting=format]').select_option('flac')
            check('lossless format hides meaningless bitrate quality presets',lambda:expect(page.locator('.goal-row')).to_have_count(0))
            page.screenshot(path=str(ROOT/'screenshots/mobile-audio-lossless.png'))
            # New page with actual engine API transport.
            page.close();page=browser.new_page(viewport={'width':1280,'height':900},reduced_motion='reduce');page.on('pageerror',lambda e:errors.append(str(e)))
            if INLINE:
                page.expose_function('__formaTestHttp',transport)
                live=html.replace("if(!/^https?:$/.test(location.protocol))throw new Error('Standalone preview');",'')
                page.set_content(live.replace('<head>','<head>'+shim,1))
            else:page.goto(url)
            page.wait_for_function('FormaStudio.state.engine.available')
            check('capabilities come from the installed FFmpeg process',lambda:assert_('libx264' in page.evaluate('FormaStudio.state.engine.encoders')))
            page.locator('#file-input').set_input_files(str(ROOT/'media/sample.mp4'));page.wait_for_function('FormaStudio.state.plan !== null')
            check('local upload is FFprobe-inspected with true audio stream index',lambda:assert_(page.evaluate('FormaStudio.state.source.audioTracks[0].index')==1))
            for fmt in ('webm','mkv','mov','mp4'):
                page.locator('[data-setting=format]').select_option(fmt);page.wait_for_function('FormaStudio.state.plan!==null')
                check(f'{fmt} output builds a valid real engine plan',lambda f=fmt:assert_(page.evaluate('FormaStudio.state.plan.extension')==f))
            page.locator('#advanced-toggle').click();page.locator('[data-setting=crf]').fill('99');page.locator('[data-setting=crf]').dispatch_event('change')
            page.wait_for_function('FormaStudio.state.planError.length>0')
            check('invalid CRF blocks export and explains the error',lambda:(expect(page.locator('#convert')).to_be_disabled(),expect(page.locator('#workspace-message')).to_contain_text('quality')))
            page.locator('[data-setting=crf]').fill('21');page.locator('[data-setting=crf]').dispatch_event('change');page.wait_for_function('FormaStudio.state.plan!==null')
            page.locator('#advanced-toggle').click();page.locator('#advanced-toggle').click()
            check('collapsing settings does not reset custom quality',lambda:expect(page.locator('[data-setting=crf]')).to_have_value('21'))
            page.locator('#tab-audio').click()
            for fmt in ('mp3','m4a','wav','flac','opus'):
                page.locator('[data-setting=format]').select_option(fmt);page.wait_for_function('FormaStudio.state.plan!==null')
                check(f'{fmt} audio has a real encoder and no output video',lambda:assert_(page.evaluate('FormaStudio.state.plan.videoEncoder') is None and bool(page.evaluate('FormaStudio.state.plan.audioEncoder'))))
            page.locator('#tab-video').click();page.locator('#tab-edit').click();page.wait_for_function("document.querySelector('#media').readyState>=2")
            page.locator('[data-setting=start]').fill('1');page.locator('[data-setting=start]').dispatch_event('change')
            page.locator('[data-setting=end]').fill('7');page.locator('[data-setting=end]').dispatch_event('change')
            check('trim changes the kept range',lambda:assert_(page.evaluate('FormaStudio.state.settings.segments')==[{'start':1,'end':7}]))
            page.locator('#scrub').evaluate('(x)=>{x.value=3;x.dispatchEvent(new Event("input",{bubbles:true}));}')
            page.wait_for_function('Math.abs(document.querySelector("#media").currentTime-3)<.02');page.locator('#split').click()
            check('split creates two contiguous kept clips',lambda:assert_(page.evaluate('FormaStudio.state.settings.segments')==[{'start':1,'end':3},{'start':3,'end':7}]))
            page.locator('[data-move="-1"]').click()
            check('reordering changes actual export range order',lambda:assert_(page.evaluate('FormaStudio.state.settings.segments[0].start')==3))
            page.locator('#remove-clip').click();check('remove excludes the selected clip',lambda:assert_(page.evaluate('FormaStudio.state.settings.segments.length')==1))
            page.locator('#undo').click();check('undo restores a removed clip',lambda:assert_(page.evaluate('FormaStudio.state.settings.segments.length')==2))
            page.locator('#redo').click();check('redo reapplies the removal',lambda:assert_(page.evaluate('FormaStudio.state.settings.segments.length')==1))
            page.locator('#reset-cuts').click();page.locator('[data-setting=speed]').select_option('2')
            check('speed changes planned output duration',lambda:assert_(page.evaluate('FormaStudio.duration()')==4))
            page.locator('[data-inspector=picture]').click();page.locator('[data-ratio="1"]').click()
            page.wait_for_function('FormaStudio.state.plan!==null')
            check('square crop matches planner dimensions',lambda:assert_(page.evaluate('[FormaStudio.state.plan.width,FormaStudio.state.plan.height]')==[360,360]))
            page.locator('[data-ratio=original]').click()
            for key in ('crop.left','crop.right'):
                page.locator(f'[data-setting="{key}"]').fill('10');page.locator(f'[data-setting="{key}"]').dispatch_event('change')
            page.locator('[data-setting=rotation]').select_option('90');page.wait_for_function('FormaStudio.state.plan!==null')
            check('rotated crop preview agrees with exported picture dimensions',lambda:assert_(page.evaluate('[document.querySelector("#preview-canvas").width,document.querySelector("#preview-canvas").height]')==page.evaluate('[FormaStudio.state.plan.width,FormaStudio.state.plan.height]')==[360,512]))
            page.locator('[data-inspector=sound]').click();page.locator('[data-setting=normalize]').check()
            page.locator('[data-setting=fadeIn]').fill('.2');page.locator('[data-setting=fadeIn]').dispatch_event('change')
            page.locator('[data-setting=fadeOut]').fill('.3');page.locator('[data-setting=fadeOut]').dispatch_event('change');page.wait_for_function('FormaStudio.state.plan!==null')
            check('sound edits are bound to a validated export',lambda:assert_(page.evaluate('FormaStudio.state.plan.settings.normalize') and page.evaluate('FormaStudio.state.plan.settings.fadeOut')==.3))
            page.locator('#add-queue').click();page.wait_for_function('FormaStudio.state.jobs.length>0')
            job_id=page.evaluate('FormaStudio.state.jobs[0].id')
            page.locator('[data-setting=fadeOut]').fill('.5');page.locator('[data-setting=fadeOut]').dispatch_event('change');page.wait_for_function('FormaStudio.state.plan!==null')
            check('queued settings do not change with subsequent edits',lambda:assert_(page.evaluate('FormaStudio.state.jobs[0].settings.fadeOut')==.3))
            page.wait_for_function('FormaStudio.state.jobs[0].status==="completed"',timeout=20000)
            check('editor creates a genuinely verified FFmpeg output',lambda:assert_(page.evaluate('FormaStudio.state.jobs[0].verified') and page.evaluate('FormaStudio.state.jobs[0].size')>0))
            page.locator('#convert').click();page.wait_for_function('FormaStudio.state.page==="queue"')
            check('primary export navigates to the real queue',lambda:expect(page.locator('#queue-screen')).to_be_visible())
            page.wait_for_function('FormaStudio.state.jobs.length>=2&&FormaStudio.state.jobs.every(j=>j.status==="completed")',timeout=20000)
            check('verified files expose Open and Save actions',lambda:assert_(page.locator('a[download]').count()==2))
            page.screenshot(path=str(ROOT/'screenshots/desktop-queue.png'))
            page.evaluate('FormaStudio.navigate("edit")');page.locator('[data-inspector=picture]').click();page.locator('[data-ratio=original]').click();page.locator('[data-setting=rotation]').select_option('0')
            page.locator('[data-inspector=trim]').click();page.locator('[data-setting=speed]').select_option('1')
            for width in (320,360,390,768,1360):
                page.set_viewport_size({'width':width,'height':900});page.wait_for_timeout(60)
                check(f'editor has no horizontal overflow at {width}px',lambda w=width:assert_(page.evaluate('document.documentElement.scrollWidth')<=w))
            page.set_viewport_size({'width':1360,'height':950});page.locator('#menu').click();page.wait_for_timeout(200)
            check('desktop shelf shifts rather than covers workspace',lambda:assert_(page.locator('#main').bounding_box()['x']>=248))
            page.locator('#toast').evaluate('(x)=>x.hidden=true');page.screenshot(path=str(ROOT/'screenshots/desktop-editor.png'))
            page.locator('#close-shelf').click();page.set_viewport_size({'width':390,'height':844});page.evaluate('window.scrollTo(0,0)')
            page.screenshot(path=str(ROOT/'screenshots/mobile-editor.png'))
            page.locator('#tab-video').click();page.locator('#advanced-toggle').click() if page.locator('#advanced-toggle').get_attribute('aria-expanded')=='true' else None
            page.screenshot(path=str(ROOT/'screenshots/mobile-video.png'))
            page.locator('#tab-audio').click();page.locator('[data-setting=format]').select_option('mp3');page.wait_for_function('FormaStudio.state.plan!==null')
            page.screenshot(path=str(ROOT/'screenshots/mobile-audio.png'))
            page.locator('#advanced-toggle').click();page.screenshot(path=str(ROOT/'screenshots/mobile-audio-advanced.png'),full_page=True)
            page.evaluate('FormaStudio.navigate("home")');page.locator('#media-url').fill(f'{url}/sample.wav');page.locator('#url-form button').click();page.wait_for_function('FormaStudio.state.source.name==="sample.wav"')
            check('URL media is downloaded, probed, and enters the same audio flow',lambda:assert_(page.evaluate('FormaStudio.state.source.backend') and not page.evaluate('FormaStudio.state.source.hasVideo')))
            check('no JavaScript runtime errors',lambda:assert_(not errors,str(errors)))
        finally:
            (ROOT/'tests/browser-results.json').write_text(json.dumps({'transport':'inline + real localhost forwarding' if INLINE else 'HTTP origin','checks':results,'pageErrors':errors},indent=2))
            browser.close();server.shutdown();app.close();server.server_close();thread.join(3)
if __name__=='__main__':
    run();print(f'{len(results)} browser checks passed.')

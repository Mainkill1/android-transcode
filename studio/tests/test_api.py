import copy,http.client,json,socket,sys,threading,time
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server import Workbench,Handler,ThreadingHTTPServer,validate_url,inspect_media
from planner import default_settings
ROOT=Path(__file__).resolve().parents[1]
@pytest.fixture(scope='module')
def service(tmp_path_factory):
    app=Workbench(tmp_path_factory.mktemp('work'),allow_private=True)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.app=app
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    yield app,server
    server.shutdown();app.close();server.server_close();thread.join(3)

def request(service,path,body=None,headers=None,method=None):
    app,server=service;c=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=20)
    h={'X-Forma-Token':app.token};h.update(headers or {})
    if isinstance(body,dict):body=json.dumps(body).encode();h['Content-Type']='application/json'
    c.request(method or ('POST' if body is not None else 'GET'),path,body=body,headers=h)
    r=c.getresponse();content=r.read();status=r.status;rh=dict(r.getheaders());c.close()
    if rh.get('Content-Type','').startswith('application/json'):content=json.loads(content)
    return status,content,rh

def source(service,kind='video'):
    status,data,_=request(service,'/api/sample',{'kind':kind});assert status==201;return data

def wait_job(service,identity):
    for _ in range(250):
        _,jobs,_=request(service,'/api/jobs');job=next(j for j in jobs if j['id']==identity)
        if job['status'] in ('completed','failed','cancelled'):return job
        time.sleep(.05)
    raise AssertionError('Job timed out')

def test_session_reports_real_capabilities(service):
    code,result,_=request(service,'/api/session');assert code==200 and result['available'] and 'libx264' in result['encoders']

def test_origin_is_checked(service):
    code,_,_=request(service,'/api/jobs',{},headers={'Origin':'https://other.invalid'});assert code==403

def test_token_is_checked(service):
    code,_,_=request(service,'/api/sample',{'kind':'video'},headers={'X-Forma-Token':'wrong'});assert code==403

def test_host_is_checked(service):
    code,_,_=request(service,'/api/session',headers={'Host':'attacker.invalid'});assert code==403

def test_upload_and_probe_audio(service):
    code,result,_=request(service,'/api/upload?name=audio.wav',(ROOT/'media/sample.wav').read_bytes());assert code==201
    assert result['hasAudio'] and not result['hasVideo'] and result['duration']==8

def test_media_byte_ranges(service):
    src=source(service);code,content,headers=request(service,'/media/'+src['id'],headers={'Range':'bytes=0-99'})
    assert code==206 and len(content)==100 and headers['Content-Range'].startswith('bytes 0-99/')

def test_invalid_byte_ranges(service):
    src=source(service);code,_,_=request(service,'/media/'+src['id'],headers={'Range':'bytes=999999999-'});assert code==416

def test_invalid_media_not_registered(service):
    before=len(service[0].sources)
    code,_,_=request(service,'/api/upload?name=fake.mp4',b'<html>not a movie</html>');assert code==400 and len(service[0].sources)==before

def test_real_queue_snapshot_and_verified_download(service,tmp_path):
    src=source(service);settings=default_settings(src);settings.update(mode='audio',format='mp3',segments=[{'start':1,'end':2}],filename='audio-result')
    code,job,_=request(service,'/api/jobs',{'sourceId':src['id'],'settings':settings});assert code==201
    settings['format']='flac'
    result=wait_job(service,job['id']);assert result['status']=='completed',result
    assert result['settings']['format']=='mp3' and result['verified'] and result['progress']==1
    code,content,headers=request(service,'/output/'+job['id']+'?download=1');assert code==200 and 'audio-result.mp3' in headers['Content-Disposition']
    out=tmp_path/'result.mp3';out.write_bytes(content);assert inspect_media(out)['audioTracks'][0]['codec']=='mp3'

def test_invalid_plan_creates_no_job(service):
    src=source(service);before=len(service[0].jobs);settings=default_settings(src);settings['speed']=100
    code,_,_=request(service,'/api/jobs',{'sourceId':src['id'],'settings':settings});assert code==400 and len(service[0].jobs)==before

def test_cancelled_job_has_no_download(service):
    src=source(service);s=default_settings(src);s.update(videoCodec='av1',segments=[{'start':0,'end':8}]*6)
    code,job,_=request(service,'/api/jobs',{'sourceId':src['id'],'settings':s});assert code==201
    code,_,_=request(service,f"/api/jobs/{job['id']}/cancel",{});assert code==200
    result=wait_job(service,job['id']);assert result['status']=='cancelled'
    code,_,_=request(service,f"/output/{job['id']}");assert code==404

def test_direct_url_download_probe(service):
    url=f'http://127.0.0.1:{service[1].server_port}/sample.wav'
    code,src,_=request(service,'/api/url',{'url':url});assert code==201 and src['hasAudio'] and src['duration']==8

def test_private_url_blocked_without_flag():
    with pytest.raises(ValueError,match='Private'):validate_url('http://127.0.0.1/test.mp4')

@pytest.mark.parametrize('url',['file:///etc/passwd','https://u:p@example.com/file.mp4','http://127.0.0.1/a.m3u8','https://example.com/x\ny'])
def test_bad_url_rejected(url):
    with pytest.raises(ValueError):validate_url(url,True)

def test_unknown_output_is_not_exposed(service):
    code,_,_=request(service,'/output/'+'0'*32);assert code==404


def test_web_routes_include_touch_styles(service):
    code, content, _ = request(service, '/')
    assert code == 200 and b'touch.css' in content
    code, content, _ = request(service, '/touch.css')
    assert code == 200 and b'--touch-target:48px' in content

@pytest.mark.parametrize('path,mime', [('/timeline.js','javascript'),('/timeline.css','text/css')])
def test_timeline_assets_are_served_over_real_http(service,path,mime):
    code,content,headers=request(service,path)
    assert code==200
    assert mime in headers['Content-Type']
    assert content==(ROOT/'web'/path.lstrip('/')).read_bytes()

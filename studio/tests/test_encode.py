import sys, subprocess
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from planner import default_settings,make_plan,build_command
from server import inspect_media,encoder_capabilities
ROOT=Path(__file__).resolve().parents[1]
@pytest.fixture(scope='module')
def src():return {'name':'sample.mp4',**inspect_media(ROOT/'media/sample.mp4')}
@pytest.fixture(scope='module')
def encoders():return encoder_capabilities('ffmpeg')

CASES=[
 ('mp4',{'videoCodec':'h264'}),('mkv',{'videoCodec':'hevc'}),('webm',{'videoCodec':'vp9'}),
 ('mov',{'videoCodec':'h264'}),('mp4',{'videoCodec':'av1'}),
 ('mp3',{'mode':'audio'}),('m4a',{'mode':'audio'}),('flac',{'mode':'audio'}),
 ('wav',{'mode':'audio','channels':'2','sampleRate':'44100'}),('opus',{'mode':'audio'}),
 ('mp4',{'crop':{'left':10,'right':10,'top':0,'bottom':0},'rotation':90,'flip':True,'speed':2}),
 ('mp4',{'segments':[{'start':4,'end':5},{'start':1,'end':2}],'volume':1.5,'normalize':True,'fadeIn':.2,'fadeOut':.2}),
 ('mp4',{'mute':True,'fps':'24','rateMode':'bitrate','videoBitrate':750}),
]
@pytest.mark.parametrize('fmt,changes',CASES)
def test_actual_ffmpeg_outputs(tmp_path,src,encoders,fmt,changes):
    s=default_settings(src);s.update(segments=[{'start':1,'end':2}],resolution='480',preset='ultrafast');s.update(changes);s['format']=fmt
    plan=make_plan(src,s,encoders);out=tmp_path/('result.'+fmt)
    r=subprocess.run(build_command(plan,str(ROOT/'media/sample.mp4'),str(out)),capture_output=True,text=True,timeout=40)
    assert r.returncode==0,r.stderr
    result=inspect_media(out)
    assert result['hasVideo']==(plan['videoEncoder'] is not None)
    assert result['hasAudio']==(plan['audioEncoder'] is not None)
    assert abs(result['duration']-plan['duration'])<.15
    if result['hasVideo']:assert (result['width'],result['height'])==(plan['width'],plan['height'])
    if changes.get('channels')=='2':assert result['audioTracks'][0]['channels']==2
    if changes.get('sampleRate')=='44100':assert result['audioTracks'][0]['sampleRate']==44100
    if fmt=='mp3':assert result['audioTracks'][0]['codec']=='mp3'

def test_audio_input_to_audio(tmp_path,encoders):
    source={'name':'tone.wav',**inspect_media(ROOT/'media/sample.wav')};settings=default_settings(source)
    settings.update(format='flac',segments=[{'start':.5,'end':1.5}],speed=.5,fadeIn=.1,fadeOut=.1)
    plan=make_plan(source,settings,encoders);out=tmp_path/'audio.flac'
    r=subprocess.run(build_command(plan,str(ROOT/'media/sample.wav'),str(out)),capture_output=True,text=True,timeout=20)
    assert r.returncode==0,r.stderr
    info=inspect_media(out);assert abs(info['duration']-2)<.1 and not info['hasVideo']

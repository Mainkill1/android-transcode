import copy, math, sys
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from planner import default_settings, make_plan, build_command

SOURCE={'id':'test','name':'clip.mp4','duration':4.0,'width':320,'height':180,'sar':1.0,'rotation':0,'hasVideo':True,'hasAudio':True,'audioTracks':[{'index':1,'codec':'aac','channels':2,'sampleRate':48000}], 'hdr':False}
ALL={'libx264','libx265','libvpx-vp9','libaom-av1','aac','libmp3lame','libopus','flac','pcm_s16le'}
def configured(**changes):
    s=default_settings(SOURCE);s.update(changes);return s

def test_video_defaults_generate_valid_plan():
    p=make_plan(SOURCE,configured(),ALL)
    assert p['videoEncoder']=='libx264' and p['audioEncoder']=='aac'
    assert p['duration']==4 and p['extension']=='mp4'

@pytest.mark.parametrize('fmt,enc',[('mp3','libmp3lame'),('m4a','aac'),('flac','flac'),('wav','pcm_s16le'),('opus','libopus')])
def test_audio_formats_have_no_video(fmt,enc):
    p=make_plan(SOURCE,configured(mode='audio',format=fmt),ALL)
    assert p['videoEncoder'] is None and p['audioEncoder']==enc
    assert '[vout]' not in p['filtergraph']

def test_audio_source_cannot_export_video():
    src={**SOURCE,'hasVideo':False}
    with pytest.raises(ValueError,match='video'):make_plan(src,configured(),ALL)

def test_audio_source_defaults_to_audio():
    assert default_settings({**SOURCE,'hasVideo':False})['mode']=='audio'

def test_audio_output_requires_audio_track():
    with pytest.raises(ValueError,match='audio'):make_plan({**SOURCE,'hasAudio':False,'audioTracks':[]},configured(mode='audio',format='mp3'),ALL)

@pytest.mark.parametrize('field,value',[('speed',0),('speed',float('nan')),('crf',90),('videoBitrate',-1),('volume',-2),('sampleRate',12345),('channels','seven'),('rotation',13),('audioBitrate',9999)])
def test_invalid_numbers_and_enums(field,value):
    with pytest.raises(ValueError):make_plan(SOURCE,configured(**{field:value}),ALL)

def test_reversed_range_rejected():
    with pytest.raises(ValueError,match='range'):make_plan(SOURCE,configured(segments=[{'start':3,'end':2}]),ALL)

def test_range_outside_source_rejected():
    with pytest.raises(ValueError,match='range'):make_plan(SOURCE,configured(segments=[{'start':0,'end':9}]),ALL)

def test_reordered_ranges_are_retained_and_concatenated():
    p=make_plan(SOURCE,configured(segments=[{'start':2,'end':4},{'start':0,'end':1}],speed=2),ALL)
    assert p['duration']==1.5 and 'concat=n=2:v=1:a=1' in p['filtergraph']
    assert 'trim=start=2:end=4' in p['filtergraph']

def test_crop_rotate_dimensions_match():
    p=make_plan(SOURCE,configured(crop={'left':10,'right':10,'top':0,'bottom':0},rotation=90),ALL)
    assert (p['width'],p['height'])==(180,256)
    assert 'crop=256:180:32:0' in p['filtergraph'] and 'transpose=clock' in p['filtergraph']

def test_bad_crop_rejected():
    with pytest.raises(ValueError,match='crop'):make_plan(SOURCE,configured(crop={'left':60,'right':60,'top':0,'bottom':0}),ALL)

def test_unavailable_encoder_rejected():
    with pytest.raises(ValueError,match='encoder'):make_plan(SOURCE,configured(),{'aac'})

def test_bad_container_codec_pair_rejected():
    with pytest.raises(ValueError,match='compatible'):make_plan(SOURCE,configured(format='webm',videoCodec='h264'),ALL)

def test_audio_track_mapping_is_explicit():
    p=make_plan(SOURCE,configured(),ALL)
    assert '[0:1]' in p['filtergraph']

def test_mute_removes_audio_mapping():
    p=make_plan(SOURCE,configured(mute=True),ALL)
    assert p['audioEncoder'] is None and '[aout]' not in p['filtergraph']

def test_gain_normalization_and_fades_are_real_filters():
    p=make_plan(SOURCE,configured(volume=1.5,normalize=True,fadeIn=.3,fadeOut=.4),ALL)
    assert 'volume=1.5' in p['filtergraph'] and 'loudnorm=' in p['filtergraph'] and 'afade=t=out' in p['filtergraph']

def test_no_audio_source_video_still_works():
    p=make_plan({**SOURCE,'hasAudio':False,'audioTracks':[]},configured(),ALL)
    assert p['audioEncoder'] is None and p['videoEncoder']=='libx264'

def test_hdr_is_not_silently_destroyed():
    with pytest.raises(ValueError,match='HDR'):make_plan({**SOURCE,'hdr':True},configured(),ALL)

def test_safe_argument_array_and_no_source_overwrite():
    p=make_plan(SOURCE,configured(),ALL)
    args=build_command(p,'/tmp/source;name.mp4','/tmp/out.mp4')
    assert isinstance(args,list) and '/tmp/source;name.mp4' in args
    assert '-progress' in args and 'pipe:1' in args
    with pytest.raises(ValueError):build_command(p,'/tmp/same.mp4','/tmp/same.mp4')

def test_audio_export_ignores_inactive_video_settings():
    p=make_plan(SOURCE,configured(mode='audio',format='mp3',videoCodec='bad',crf=99,
        videoBitrate=-1,preset='bad',rateMode='bad',fps='bad',resolution='bad',
        rotation=13,crop={'left':99,'right':99},flip='bad'),ALL)
    assert p['audioEncoder']=='libmp3lame' and p['videoEncoder'] is None

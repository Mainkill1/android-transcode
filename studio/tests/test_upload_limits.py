"""Upload caps are measured in bytes, never inferred from FFmpeg's exit status."""
import copy
import hashlib
import math
import struct
import sys
import time
import zlib
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from planner import default_settings, make_plan, build_command
from server import Workbench, inspect_media

ROOT = Path(__file__).resolve().parents[1]
SOURCE = dict(name='clip.mp4', kind='video', duration=120, width=1920, height=1080,
              hasVideo=True, hasAudio=True, fps=60, hdr=False,
              audioTracks=[dict(index=1, channels=2, sampleRate=48000, codec='aac')])
ENCODERS = {'libx264', 'aac', 'libwebp', 'mjpeg', 'png'}


def goal(source=SOURCE, **values):
    s = default_settings(source)
    s.update(targetBytes=10_000_000, **values)
    return s


def test_upload_goal_uses_bitrate_and_leaves_container_headroom():
    p = make_plan(SOURCE, goal(), ENCODERS)
    assert p['settings']['rateMode'] == 'bitrate'
    assert p['extension'] == 'mp4' and p['videoEncoder'] == 'libx264'
    assert p['targetBytes'] == 10_000_000 and p['maxAttempts'] == 4
    payload = (p['settings']['videoBitrate'] + p['settings']['audioBitrate']) * 1000 * 120 / 8
    assert payload < p['targetBytes'] * .96
    assert p['height'] <= 720 and p['settings']['fps'] == '30'
    assert '-fs' not in build_command(p, 'source.mp4', 'output.mp4')


@pytest.mark.parametrize('bad', [True, False, 0, -1, '10mb', 1.5, float('nan'), float('inf'), 2_000_000_001])
def test_invalid_byte_limit_rejected(bad):
    s = goal(); s['targetBytes'] = bad
    with pytest.raises(ValueError, match='limit'):
        make_plan(SOURCE, s, ENCODERS)


def test_budget_uses_kept_duration_and_speed_not_source_duration():
    s = goal(segments=[dict(start=90, end=110), dict(start=0, end=10)], speed=2)
    before = copy.deepcopy(s)
    p = make_plan(SOURCE, s, ENCODERS)
    assert p['duration'] == 15 and p['settings']['videoBitrate'] > 3000
    assert s == before and p['settings']['segments'] == s['segments']


def test_impossible_budget_does_not_silently_trim_or_mute():
    with pytest.raises(ValueError, match='[Tt]rim|[Ll]arger'):
        make_plan({**SOURCE, 'duration': 3600}, goal(), ENCODERS)


def test_portrait_does_not_upscale_or_change_aspect():
    src = {**SOURCE, 'width': 720, 'height': 1280}
    p = make_plan(src, goal(src), ENCODERS)
    assert p['width'] <= 720 and p['height'] <= 1280
    assert abs(p['width'] / p['height'] - 720 / 1280) < .01


def test_audio_goal_uses_aac_and_budget():
    src = {**SOURCE, 'kind': 'audio', 'hasVideo': False}
    p = make_plan(src, goal(src), ENCODERS)
    assert p['videoEncoder'] is None and p['audioEncoder'] == 'aac'
    assert p['extension'] == 'm4a' and p['targetBytes'] == 10_000_000


def test_missing_encoder_not_faked():
    with pytest.raises(ValueError, match='encoder'):
        make_plan(SOURCE, goal(), {'aac'})


def write_png(path, width=640, height=360, alpha=False):
    """Deterministic noisy PNG: compression cannot win merely by spotting flat color."""
    import random
    rng = random.Random(349)
    channels = 4 if alpha else 3
    raw = b''.join(b'\x00' + rng.randbytes(width * channels) for _ in range(height))
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6 if alpha else 2, 0, 0, 0))
                     + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
    return path


def terminal(app, identity):
    for _ in range(1200):
        with app.lock:
            job = app.public_job(app.jobs[identity])
        if job['status'] in ('completed', 'failed', 'cancelled'):
            return job
        time.sleep(.025)
    pytest.fail('Encoding did not finish within 30 seconds')


@pytest.fixture
def app(tmp_path):
    instance = Workbench(tmp_path / 'jobs')
    yield instance
    instance.close()


def test_png_is_an_image_not_a_zero_duration_video(tmp_path):
    src = inspect_media(write_png(tmp_path / 'photo.png'))
    assert src['kind'] == 'image' and src['duration'] == 0
    assert not src['hasVideo'] and not src['hasAudio']
    assert src['width'] == 640 and src['height'] == 360


@pytest.mark.parametrize('kind, cap', [('video', 150_000), ('audio', 80_000), ('image', 50_000)])
def test_real_outputs_fit_cap_and_original_is_unchanged(app, tmp_path, kind, cap):
    source = write_png(tmp_path / 'photo.png') if kind == 'image' else ROOT / 'media' / ('sample.wav' if kind == 'audio' else 'sample.mp4')
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    src = app.register(source, source.name)
    settings = default_settings(src); settings['targetBytes'] = cap
    job = app.add_job(src['id'], settings)
    settings['targetBytes'] = 500_000_000
    result = terminal(app, job['id'])
    assert result['status'] == 'completed', result
    assert result['verified'] and result['fitsLimit'] and 0 < result['size'] < cap
    assert result['settings']['targetBytes'] == cap
    assert result['attempts'] and result['attempts'][-1]['bytes'] == result['size']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == before
    out = inspect_media(Path(app.jobs[job['id']]['output']))
    if kind != 'image':
        assert abs(out['duration'] - 8) < .2


def test_oversize_output_retries_from_original_with_stricter_settings(app, monkeypatch):
    src = app.register(ROOT / 'media/sample.mp4', 'sample.mp4')
    real = app.encode_attempt
    seen = []
    def oversized_once(job, plan, log):
        seen.append((job['input'], plan['settings']['videoBitrate']))
        real(job, plan, log)
        if len(seen) == 1:
            with open(job['output'], 'ab') as f:
                f.write(b'\0' * 180_000)  # fault injection: valid but oversized first result
    monkeypatch.setattr(app, 'encode_attempt', oversized_once)
    settings = default_settings(src); settings['targetBytes'] = 180_000
    result = terminal(app, app.add_job(src['id'], settings)['id'])
    assert result['status'] == 'completed', result
    assert len(seen) >= 2 and seen[1][1] < seen[0][1]
    assert len({item[0] for item in seen}) == 1
    assert result['attempts'][0]['bytes'] >= 180_000 and result['size'] < 180_000


def test_exhausted_retries_publish_nothing(app, monkeypatch):
    src = app.register(ROOT / 'media/sample.mp4', 'sample.mp4')
    real = app.encode_attempt
    def always_large(job, plan, log):
        real(job, plan, log)
        with open(job['output'], 'ab') as f: f.write(b'\0' * 1_000_000)
    monkeypatch.setattr(app, 'encode_attempt', always_large)
    settings = default_settings(src); settings['targetBytes'] = 1_000_000
    identity = app.add_job(src['id'], settings)['id']
    result = terminal(app, identity)
    assert result['status'] == 'failed' and not result.get('verified')
    assert len(result['attempts']) <= 4 and 'limit' in result['error'].lower()
    assert not Path(app.jobs[identity]['output']).exists()


def test_codec_errors_do_not_trigger_compression_retries(app, monkeypatch):
    src = app.register(ROOT / 'media/sample.mp4', 'sample.mp4')
    calls = []
    def broken(job, plan, log):
        calls.append(1)
        raise ValueError('Decoder error: broken packet')
    monkeypatch.setattr(app, 'encode_attempt', broken)
    settings = default_settings(src); settings['targetBytes'] = 1_000_000
    result = terminal(app, app.add_job(src['id'], settings)['id'])
    assert result['status'] == 'failed' and len(calls) == 1
    assert 'Decoder error' in result['error']


def test_alpha_is_preserved_and_jpeg_not_silently_flattened(app, tmp_path):
    src = app.register(write_png(tmp_path / 'transparent.png', 128, 128, alpha=True), 'transparent.png')
    assert src['hasAlpha']
    settings = default_settings(src); settings['targetBytes'] = 50_000
    with pytest.raises(ValueError, match='transparency'):
        make_plan(src, {**settings, 'imageFormat':'jpg'}, ENCODERS)
    identity = app.add_job(src['id'], settings)['id']
    result = terminal(app, identity)
    assert result['status'] == 'completed', result
    assert inspect_media(Path(app.jobs[identity]['output']))['hasAlpha']


def test_animation_is_rejected_instead_of_discarding_frames(tmp_path):
    from upload_limits import image_header
    path = tmp_path / 'animation.png'
    # Valid PNG header followed by animation control. We stop before decoding.
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + struct.pack('>I4sII', 8, b'acTL', 2, 0) + b'\0' * 4)
    with pytest.raises(ValueError, match='Animated'):
        image_header(path)


def test_cancellation_during_oversize_retry_publishes_nothing(app, monkeypatch):
    src = app.register(ROOT / 'media/sample.mp4', 'sample.mp4')
    real = app.encode_attempt
    def cancel_after_encode(job, plan, log):
        real(job, plan, log)
        app.cancel(job['id'])
    monkeypatch.setattr(app, 'encode_attempt', cancel_after_encode)
    settings = default_settings(src); settings['targetBytes'] = 500_000
    identity = app.add_job(src['id'], settings)['id']
    result = terminal(app, identity)
    assert result['status'] == 'cancelled' and not result.get('verified')
    # Worker cleanup runs immediately after cancellation; wait for that boundary.
    for _ in range(100):
        if not Path(app.jobs[identity]['output']).exists(): break
        time.sleep(.01)
    assert not Path(app.jobs[identity]['output']).exists()


def test_equal_to_limit_is_not_published_as_under_limit():
    from upload_limits import next_plan
    p = make_plan(SOURCE, goal(), ENCODERS)
    assert next_plan(SOURCE, goal(), p, p['targetBytes'], ENCODERS) is not None
    assert next_plan(SOURCE, goal(), p, p['targetBytes'] - 1, ENCODERS) is None


def test_image_resize_retries_reduce_both_quality_and_area():
    from upload_limits import next_plan
    src = dict(name='photo.png',kind='image',width=1920,height=1080,hasAlpha=False)
    p = make_plan(src, {'targetBytes':100_000}, ENCODERS)
    nxt = next_plan(src, {'targetBytes':100_000}, p, 500_000, ENCODERS)
    assert nxt['settings']['imageQuality'] < p['settings']['imageQuality']
    assert nxt['width'] < p['width'] and nxt['height'] < p['height']


def test_larger_limit_does_not_force_video_upscaling():
    p = make_plan({**SOURCE,'width':160,'height':90}, goal(), ENCODERS)
    assert p['width'] <= 160 and p['height'] <= 90


def test_image_edits_are_not_silently_ignored():
    src = dict(name='photo.png',kind='image',width=1920,height=1080,hasAlpha=False)
    with pytest.raises(ValueError, match='not supported'):
        make_plan(src, {'targetBytes':100_000, 'rotation':90}, ENCODERS)


def test_default_ten_mb_goal_with_an_oversized_real_image(app, tmp_path):
    image = write_png(tmp_path / 'large-photo.png', 2400, 1800)
    assert image.stat().st_size > 10_000_000
    src = app.register(image, image.name)
    result = terminal(app, app.add_job(src['id'], goal(src))['id'])
    assert result['status'] == 'completed', result
    assert result['fitsLimit'] and result['size'] < 10_000_000


def test_default_ten_mb_goal_with_an_oversized_real_video(app, tmp_path):
    import subprocess
    path = tmp_path / 'large-video.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-f', 'lavfi', '-i',
                    'testsrc2=size=1280x720:rate=30', '-t', '10', '-c:v', 'libx264',
                    '-preset', 'ultrafast', '-qp', '0', '-threads', '2', str(path)],
                   check=True, timeout=30)
    assert path.stat().st_size > 10_000_000
    src = app.register(path, path.name)
    result = terminal(app, app.add_job(src['id'], goal(src))['id'])
    assert result['status'] == 'completed', result
    assert result['fitsLimit'] and result['size'] < 10_000_000
    assert result['effectiveSettings']['segments'] == [{'start':0.0,'end':10.0}]


def test_api_can_request_a_goal_without_copying_every_default_setting():
    p = make_plan(SOURCE, {'targetBytes':10_000_000}, ENCODERS)
    assert p['targetBytes'] == 10_000_000 and p['settings']['speed'] == 1
    assert p['settings']['segments'] == [{'start':0.0,'end':120.0}]

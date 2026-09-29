"""Automatic upload-size plans and bounded, measured retry policy.

MB means 1,000,000 bytes. A bitrate budget is only an estimate; publication
requires a nonempty, verified file strictly below the requested byte ceiling.
This module never changes the selected duration, removes sound, or edits a source.
"""
from __future__ import annotations

import copy
import math
import struct
from pathlib import Path

MIN_TARGET_BYTES = 32_000
MAX_TARGET_BYTES = 2_000_000_000
SIZE_PRESETS_MB = (10, 20, 25, 50, 100, 500)
MAX_MEDIA_ATTEMPTS = 4
MAX_IMAGE_ATTEMPTS = 7
IMAGE_ENCODERS = {'webp': 'libwebp', 'jpg': 'mjpeg', 'png': 'png'}


def target_limit(settings: dict) -> int | None:
    value = settings.get('targetBytes')
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or not MIN_TARGET_BYTES <= value <= MAX_TARGET_BYTES:
        raise ValueError('Upload limit must be a whole byte count between 32,000 and 2,000,000,000.')
    return value


def image_header(path: Path) -> str | None:
    """Identify supported still images and reject animation rather than flatten it.

    Container chunks are read by declared length, not by scanning compressed pixel
    data for accidental text. No paths from image metadata are ever followed.
    """
    size = path.stat().st_size
    with path.open('rb') as stream:
        head = stream.read(12)
        if head.startswith(b'GIF8'):
            raise ValueError('GIF animation is not supported here. Convert it to a video first; frames will not be silently discarded.')
        if head.startswith(b'\xff\xd8\xff'):
            return 'jpg'
        if head.startswith(b'\x89PNG\r\n\x1a\n'):
            stream.seek(8)
            while stream.tell() + 8 <= size:
                raw = stream.read(8)
                length, kind = struct.unpack('>I4s', raw)
                if kind == b'acTL':
                    raise ValueError('Animated PNG is not supported; animation will not be flattened.')
                if stream.tell() + length + 4 > size:
                    raise ValueError('The PNG contains a truncated chunk.')
                stream.seek(length + 4, 1)
                if kind == b'IEND': break
            return 'png'
        if head[:4] == b'RIFF' and head[8:12] == b'WEBP':
            while stream.tell() + 8 <= size:
                kind, length = struct.unpack('<4sI', stream.read(8))
                if kind in (b'ANIM', b'ANMF'):
                    raise ValueError('Animated WebP is not supported; animation will not be flattened.')
                if stream.tell() + length > size:
                    raise ValueError('The WebP contains a truncated chunk.')
                stream.seek(length + (length % 2), 1)
            return 'webp'
    return None


def media_plan(source: dict, settings: dict, encoders=None, *, budget_bytes=None, attempt=1) -> dict:
    from planner import _make_media_plan

    target = target_limit(settings)
    assert target is not None
    s = copy.deepcopy(settings)
    mode = s.get('mode', 'video' if source.get('hasVideo') else 'audio')
    # Explicit upload mode owns compression fields; editing fields remain intact.
    s.update(format='m4a' if mode == 'audio' else 'mp4', videoCodec='h264',
             rateMode='bitrate', videoBitrate=2500, audioBitrate=128, crf=23,
             preset='medium', resolution='source', fps='source', channels='2', sampleRate='48000')
    base = _make_media_plan(source, s, encoders)
    s = copy.deepcopy(base['settings'])  # Normalize partial API requests before budgeting.
    budget = min(int(target * .96), budget_bytes if budget_bytes is not None else target)
    # Reserve fixed headers in addition to the percentage allowance. Do not spend
    # audio's budget on video, and use the edited output duration (including speed).
    total_kbps = math.floor(max(0, budget - 16_384) * 8 / base['duration'] / 1000)
    video = mode == 'video'
    audio = bool(base['audioEncoder'])
    if not video:
        choices = [n for n in (16, 24, 32, 48, 64, 96, 128, 160, 192) if n <= total_kbps]
        if not choices:
            raise ValueError('Upload limit is too small for this duration. Trim the audio or choose a larger limit.')
        s['audioBitrate'] = choices[-1]
        s['channels'] = '1' if choices[-1] < 64 else '2'
    else:
        allowance = max(16, min(160, total_kbps * .18))
        s['audioBitrate'] = max(n for n in (16, 24, 32, 48, 64, 96, 128, 160) if n <= allowance) if audio else 16
        available = total_kbps - (s['audioBitrate'] if audio else 0)
        if available < 64:
            raise ValueError('Upload limit is too small for the full video and sound. Trim the video or choose a larger limit.')
        s['videoBitrate'] = min(15_000, available)
        s['channels'] = '1' if s['audioBitrate'] < 64 else '2'
        source_fps = source.get('fps') or 30
        source_fps = source_fps if isinstance(source_fps, (float, int)) and math.isfinite(source_fps) and source_fps > 0 else 30
        fps = min(30, source_fps * s['speed'])
        if available < 250: fps = min(fps, 15)
        s['fps'] = str(fps).removesuffix('.0')
        # A documented pixels-per-bit heuristic, not a claim of optimal perceptual
        # quality. Larger budgets allow larger pictures, never source upscaling.
        w, h = base['width'], base['height']
        s['resolution'] = '240'
        for short, long in ((1080, 1920), (720, 1280), (480, 854), (360, 640), (240, 426)):
            mw, mh = (long, short) if w >= h else (short, long)
            scale = min(1, mw / w, mh / h)
            if w * h * scale * scale * fps * .07 <= available * 1000:
                s['resolution'] = str(short)
                break
    plan = _make_media_plan(source, s, encoders)
    plan.update(targetBytes=target, fitBudgetBytes=budget, attempt=attempt,
                maxAttempts=MAX_MEDIA_ATTEMPTS, automatic=True)
    plan['warnings'] = []
    if video and min(plan['width'], plan['height']) < 480:
        plan['warnings'].append('This limit needs a small picture. Trimming the clip can preserve more detail.')
    return plan


def image_plan(source: dict, settings: dict, encoders=None, *, quality=90, scale=1.0, attempt=1) -> dict:
    from planner import number, safe_name

    target = target_limit(settings)
    if source.get('kind') != 'image':
        raise ValueError('Image output requires a still-image source.')
    if source.get('hdr'):
        raise ValueError('HDR images need a color-managed export path.')
    s = copy.deepcopy(settings)
    if s.get('segments') or s.get('rotation', 0) or s.get('flip', False) or any(s.get('crop', {}).values()):
        raise ValueError('Still-image cropping, rotation and timeline edits are not supported by this conversion path.')
    fmt = s.get('imageFormat', 'auto')
    if fmt == 'auto':
        fmt = 'webp' if encoders is None or 'libwebp' in encoders else ('png' if source.get('hasAlpha') else 'jpg')
    if s.get('imageFormat', 'auto') == 'auto' and min(source.get('width', 0), source.get('height', 0)) < 2: fmt = 'png'
    if fmt not in IMAGE_ENCODERS:
        raise ValueError('Choose WebP, JPEG, or PNG image output.')
    enc = IMAGE_ENCODERS[fmt]
    if encoders is not None and enc not in encoders:
        raise ValueError(f'The installed FFmpeg build does not include encoder {enc}.')
    if fmt == 'jpg' and source.get('hasAlpha'):
        raise ValueError('JPEG would discard transparency. Use WebP or PNG instead.')
    w = int(number(source.get('width'), 'Image width', 1, 32768, True))
    h = int(number(source.get('height'), 'Image height', 1, 32768, True))
    if w * h > 40_000_000:
        raise ValueError('Images above 40 megapixels exceed this workbench’s decode budget.')
    width, height = max(1, int(w * scale)), max(1, int(h * scale))
    if fmt in ('jpg', 'webp'):
        width, height = max(2, width // 2 * 2), max(2, height // 2 * 2)
        if width > w or height > h:
            raise ValueError('Use PNG for images with a one-pixel dimension.')
    s.update(mode='image', format=fmt, imageQuality=quality, imageScale=scale, segments=[])
    name = s.get('filename') or Path(source.get('name', 'image')).stem + '-upload'
    return dict(settings=s, kind='image', imageEncoder=enc, videoEncoder=None, audioEncoder=None,
                extension=fmt, outputName=safe_name(name, fmt), width=width, height=height,
                duration=0, targetBytes=target, maxAttempts=MAX_IMAGE_ATTEMPTS if target else 1,
                attempt=attempt, automatic=target is not None, warnings=[])


def next_plan(source: dict, requested: dict, previous: dict, measured_bytes: int, encoders=None) -> dict | None:
    target = previous.get('targetBytes')
    if not target or measured_bytes < target or previous['attempt'] >= previous['maxAttempts']:
        return None
    attempt = previous['attempt'] + 1
    ratio = max(.35, min(.85, target / measured_bytes * .9))
    if previous.get('kind') == 'image':
        scale = previous['settings']['imageScale'] * math.sqrt(ratio)
        return image_plan(source, requested, encoders, quality=max(35, 90 - (attempt - 1) * 10), scale=scale, attempt=attempt)
    minimum_kbps = (64 + (16 if previous['audioEncoder'] else 0)) if previous['videoEncoder'] else 16
    minimum_budget = math.ceil(minimum_kbps * 1000 * previous['duration'] / 8) + 16_384
    budget = max(minimum_budget, math.floor(previous['fitBudgetBytes'] * ratio))
    if budget >= previous['fitBudgetBytes']: return None
    try:
        return media_plan(source, requested, encoders, budget_bytes=budget, attempt=attempt)
    except ValueError as exc:
        if 'limit is too small' in str(exc): return None
        raise


def image_command(plan: dict, input_path: str, output_path: str, ffmpeg='ffmpeg') -> list[str]:
    from planner import SAFE_DEMUXERS

    if Path(input_path).resolve() == Path(output_path).resolve():
        raise ValueError('Output must never overwrite the source.')
    quality = plan['settings']['imageQuality']
    enc = plan['imageEncoder']
    args = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
            '-protocol_whitelist', 'file,pipe', '-format_whitelist', SAFE_DEMUXERS,
            '-i', str(input_path), '-map', '0:v:0', '-frames:v', '1', '-an', '-sn', '-dn',
            '-vf', f'scale={plan["width"]}:{plan["height"]}:flags=lanczos',
            '-c:v', enc, '-threads', '2', '-map_metadata', '-1']
    if enc == 'libwebp':
        args += ['-quality', str(quality), '-compression_level', '6', '-lossless', '0']
    elif enc == 'mjpeg':
        args += ['-q:v', str(max(2, min(31, round(2 + (100 - quality) * .29)))), '-pix_fmt', 'yuvj420p']
    else:
        args += ['-compression_level', '9']
    args += ['-f', 'image2', '-update', '1', '-progress', 'pipe:1', '-nostats', str(output_path)]
    return args

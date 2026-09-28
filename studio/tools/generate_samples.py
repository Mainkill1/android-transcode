"""Generate eight-second test fixtures locally. Never check source media into git."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def generate():
    ffmpeg = shutil.which('ffmpeg')
    if not ffmpeg:
        raise SystemExit('Install FFmpeg on PATH to generate the optional samples.')
    media = ROOT / 'media'
    media.mkdir(exist_ok=True)
    commands = {
        'sample.mp4': ['-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=30',
                       '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                       '-t', '8', '-c:v', 'libx264', '-preset', 'ultrafast',
                       '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-movflags', '+faststart'],
        'sample.wav': ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                       '-t', '8', '-c:a', 'pcm_s16le'],
    }
    for name, args in commands.items():
        target = media / name
        if target.exists():
            continue
        partial = media / ('temporary-' + name)
        try:
            subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y',
                            *args, str(partial)], check=True, timeout=60)
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    print('Generated samples are available in studio/media/.')

if __name__ == '__main__':
    generate()

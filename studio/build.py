"""Build a standalone HTML preview. Add --samples to generate and embed test media."""
from pathlib import Path
import argparse
import base64
import json
from tools.generate_samples import generate

ROOT = Path(__file__).resolve().parent

def build(with_samples=False):
    if with_samples:
        generate()
    html = (ROOT / 'web/index.html').read_text(encoding='utf-8')
    for name in ('ui.css', 'touch.css', 'timeline.css', 'upload.css'):
        html = html.replace(f'<link rel="stylesheet" href="{name}">', '')
    for name in ('upload-ui.js', 'ui.js', 'timeline.js'):
        html = html.replace(f'<script src="{name}"></script>', '')
    css = '\n'.join((ROOT / 'web' / f).read_text(encoding='utf-8') for f in ('ui.css', 'touch.css', 'timeline.css', 'upload.css'))
    html = html.replace('/*__CSS__*/', css)
    html = html.replace('/*__JS__*/', '\n'.join((ROOT / 'web' / name).read_text(encoding='utf-8') for name in ('upload-ui.js', 'ui.js', 'timeline.js')))
    samples = {}
    for kind, name, mime in [('video', 'sample.mp4', 'video/mp4'), ('audio', 'sample.wav', 'audio/wav')]:
        path = ROOT / 'media' / name
        if path.exists():
            samples[kind] = f'data:{mime};base64,' + base64.b64encode(path.read_bytes()).decode('ascii')
    html = html.replace('/*__SAMPLES__*/', 'window.FORMA_SAMPLES=' + json.dumps(samples) + ';')
    target = ROOT / 'forma-studio.html'
    target.write_text(html, encoding='utf-8')
    return target

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', action='store_true')
    print(build(parser.parse_args().samples))

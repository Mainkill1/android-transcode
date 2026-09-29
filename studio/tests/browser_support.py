"""Select one browser source; explicit channels must not be shadowed by PATH."""
import json
import os
from pathlib import Path
import shutil


def launch_options():
    options = dict(headless=True, args=['--no-sandbox'])
    explicit = os.environ.get('FORMA_BROWSER')
    channel = os.environ.get('FORMA_BROWSER_CHANNEL')
    if explicit:
        options['executable_path'] = explicit
    elif channel:
        options['channel'] = channel
    elif shutil.which('chromium'):
        options['executable_path'] = shutil.which('chromium')
    return options


def record_browser(browser):
    page = browser.new_page()
    try:
        codecs = page.evaluate('''() => {const v=document.createElement('video');return {
            h264:v.canPlayType('video/mp4; codecs="avc1.42E01E"'),
            aac:v.canPlayType('audio/mp4; codecs="mp4a.40.2"')};}''')
    finally:
        page.close()
    data = dict(options=launch_options(), version=browser.version, codecs=codecs)
    path = Path(__file__).resolve().parents[1] / 'test-results/browser-runtime.json'
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(data, indent=2))
    print('Browser runtime:', json.dumps(data), flush=True)

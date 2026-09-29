"""Explicit browser channel selection must win over an incidental PATH browser."""
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import browser_support


def test_explicit_channel_disables_automatic_executable_lookup(monkeypatch):
    monkeypatch.delenv('FORMA_BROWSER', raising=False)
    monkeypatch.setenv('FORMA_BROWSER_CHANNEL', 'chrome')
    monkeypatch.setattr(browser_support.shutil, 'which', lambda _: '/usr/bin/chromium')
    assert browser_support.launch_options() == dict(channel='chrome', headless=True, args=['--no-sandbox'])


def test_explicit_executable_is_unambiguous(monkeypatch):
    monkeypatch.setenv('FORMA_BROWSER', '/custom/chromium')
    monkeypatch.setenv('FORMA_BROWSER_CHANNEL', 'chrome')
    assert browser_support.launch_options() == dict(executable_path='/custom/chromium', headless=True, args=['--no-sandbox'])


def test_without_overrides_path_chromium_remains_supported(monkeypatch):
    monkeypatch.delenv('FORMA_BROWSER', raising=False)
    monkeypatch.delenv('FORMA_BROWSER_CHANNEL', raising=False)
    monkeypatch.setattr(browser_support.shutil, 'which', lambda _: '/usr/bin/chromium')
    assert browser_support.launch_options()['executable_path'] == '/usr/bin/chromium'

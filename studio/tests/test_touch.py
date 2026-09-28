"""Real Chromium touch input. Uses inline assets; no FFmpeg/network responses are faked."""
from pathlib import Path
import json
import os
import shutil
import pytest
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]

@pytest.fixture(scope='module')
def browser():
    with sync_playwright() as p:
        b = p.chromium.launch(executable_path=os.environ.get('FORMA_BROWSER') or shutil.which('chromium'), headless=True, args=['--no-sandbox'])
        yield b
        b.close()

@pytest.fixture
def page(browser):
    context = browser.new_context(viewport={'width':390,'height':844}, has_touch=True, is_mobile=True, reduced_motion='reduce')
    page = context.new_page()
    page.set_default_timeout(1800)
    page.set_content((ROOT/'forma-studio.html').read_text())
    page.wait_for_function('FormaStudio.state.engineChecked')
    yield page
    context.close()

def sample(page, kind='video'):
    page.locator(f'[data-sample={kind}]').tap()
    page.wait_for_function('FormaStudio.state.source?.duration === 8')

def edit(page):
    sample(page)
    page.locator('#tab-edit').tap()

def small_targets(page):
    return page.evaluate('''() => [...document.querySelectorAll('button,a[href],select,input:not([type=hidden])')]
      .filter(e => e.getClientRects().length && !e.closest('[hidden],[inert]'))
      .map(e => { const t=e.type==='checkbox'?e.closest('label'):e; const r=t.getBoundingClientRect();
          return {name:e.getAttribute('aria-label')||e.id||e.textContent.trim().slice(0,35),w:r.width,h:r.height}; })
      .filter(r=>r.w<47.9||r.h<47.9)''')

def test_home_touch_targets(page):
    assert not small_targets(page), small_targets(page)
    expect(page.locator('#workspace')).to_be_hidden()
    expect(page.locator('#media-url')).to_be_visible()

@pytest.mark.parametrize('view',['video','audio','edit'])
def test_workspace_touch_targets(page,view):
    sample(page)
    page.locator('#tab-'+view).tap()
    if view!='edit':page.locator('#advanced-toggle').tap()
    assert not small_targets(page), small_targets(page)

@pytest.mark.parametrize('tool',['trim','picture','sound'])
def test_editor_inspector_touch_targets(page,tool):
    edit(page)
    page.locator('[data-inspector='+tool+']').tap()
    assert not small_targets(page), small_targets(page)

@pytest.mark.parametrize('width,height',[(320,640),(360,780),(390,844),(600,960),(844,390),(1280,900)])
def test_no_horizontal_overflow(page,width,height):
    page.set_viewport_size({'width':width,'height':height})
    for view in ('home','edit'):
        if view=='edit':edit(page)
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth+1')
    page.locator('[data-inspector=picture]').tap()
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth+1')

def test_shelf_locks_background_and_restores_focus(page):
    page.locator('#menu').tap()
    expect(page.locator('#shelf')).to_have_attribute('aria-modal','true')
    assert page.locator('#main').evaluate('(e)=>e.inert')
    assert page.evaluate('getComputedStyle(document.body).overflowY')=='hidden'
    assert not small_targets(page), small_targets(page)
    page.locator('#close-shelf').tap()
    expect(page.locator('#menu')).to_be_focused()
    assert not page.locator('#main').evaluate('(e)=>e.inert')

def test_trim_can_be_adjusted_by_tap_and_undone(page):
    edit(page)
    page.locator('[data-trim-nudge=start][data-delta="0.1"]').tap()
    assert page.evaluate('FormaStudio.state.settings.segments[0].start')==.1
    page.locator('#undo').tap()
    assert page.evaluate('FormaStudio.state.settings.segments[0].start')==0
    page.locator('#redo').tap()
    assert page.evaluate('FormaStudio.state.settings.segments[0].start')==.1

def test_playhead_buttons_and_set_trim_here(page):
    edit(page)
    page.locator('[data-seek="1"]').tap()
    assert .95<=page.locator('#media').evaluate('(e)=>e.currentTime')<=1.05
    page.locator('[data-trim-here=start]').tap()
    assert .95<=page.evaluate('FormaStudio.state.settings.segments[0].start')<=1.05

def test_trim_bounds_cannot_cross(page):
    edit(page)
    page.locator('[data-setting=end]').fill('0.05')
    page.locator('[data-setting=end]').dispatch_event('change')
    page.locator('[data-trim-nudge=start][data-delta="0.1"]').tap()
    r=page.evaluate('FormaStudio.state.settings.segments[0]')
    assert r['start']>=0 and r['end']-r['start']>=.0399

def test_tap_reorders_clips_without_dragging(page):
    edit(page)
    page.locator('[data-seek="1"]').tap()
    page.locator('#split').tap()
    assert page.evaluate('FormaStudio.state.settings.segments.length')==2
    page.locator('[data-move="-1"]').tap()
    assert page.evaluate('FormaStudio.state.settings.segments[0].start')==1

def test_mobile_input_font_is_not_tiny(page):
    edit(page)
    sizes=page.locator('#inspector-content input:not([type=range])').evaluate_all('(es)=>es.map(e=>parseFloat(getComputedStyle(e).fontSize))')
    assert sizes and min(sizes)>=16

def test_export_padding_tracks_real_footer_size(page):
    edit(page)
    page.wait_for_timeout(80)
    result=page.evaluate('''() => ({padding:parseFloat(getComputedStyle(document.querySelector('.workspace')).paddingBottom),
      footer:document.querySelector('#export-bar').getBoundingClientRect().height})''')
    assert result['padding'] >= result['footer']+16

def test_zoom_not_disabled(page):
    viewport=page.locator('meta[name=viewport]').get_attribute('content')
    assert 'user-scalable=no' not in viewport and 'maximum-scale=1' not in viewport


def test_space_on_button_activates_button_not_global_play(page):
    edit(page)
    page.locator('[data-seek="1"]').tap()
    page.locator('#split').focus()
    page.keyboard.press('Space')
    assert page.evaluate('FormaStudio.state.settings.segments.length') == 2
    assert page.locator('#media').evaluate('(e)=>e.paused')


def test_tabs_support_keyboard_arrows(page):
    sample(page)
    page.locator('#tab-video').focus()
    page.keyboard.press('ArrowRight')
    expect(page.locator('#tab-audio')).to_have_attribute('aria-selected','true')


def test_dialog_touch_targets(page):
    page.locator('#engine-badge').tap()
    assert not small_targets(page), small_targets(page)

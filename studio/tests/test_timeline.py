"""Interaction tests use real Chromium media and actual mouse/touch input."""
from pathlib import Path
import json
import subprocess
import pytest
from playwright.sync_api import expect
from test_touch import browser, page, small_targets

ROOT = Path(__file__).resolve().parents[1]

def open_editor(page, kind='video'):
    page.locator('#file-input').set_input_files(str(ROOT / 'media' / ('sample.wav' if kind == 'audio' else 'sample.mp4')))
    page.wait_for_function('FormaStudio.state.source?.duration > 0')
    page.locator('#tab-edit').tap()

def segments(page):
    return page.evaluate('FormaStudio.state.settings.segments')

def drag(page, edge, seconds, touch=False):
    handle = page.locator('#trim-' + edge)
    handle.evaluate('(e)=>e.scrollIntoView({block:"center",inline:"nearest"})')
    box = handle.bounding_box()
    track = page.locator('#trim-track').bounding_box()
    value = float(handle.get_attribute('aria-valuenow'))
    duration = page.evaluate('FormaStudio.state.source.duration')
    x, y = box['x'] + box['width']/2, box['y'] + box['height']/2
    target = x + (seconds-value) / duration * track['width']
    assert page.evaluate('([x,y])=>!!document.elementFromPoint(x,y)?.closest(".trim-handle")',[x,y])
    if touch:
        cdp = page.context.new_cdp_session(page)
        def send(t, points): cdp.send('Input.dispatchTouchEvent', {'type':t,'touchPoints':points})
        send('touchStart', [{'x':x,'y':y}])
        for i in range(1,9): send('touchMove', [{'x':x+(target-x)*i/8,'y':y}])
        send('touchEnd', [])
        # CDP submits raw input, not a completed compositor gesture. Starting
        # another tap immediately can suppress its click in Chromium. Keep the
        # assertions intact and allow the prior touch gesture to settle.
        page.wait_for_timeout(150)
        cdp.detach()
    else:
        page.mouse.move(x,y); page.mouse.down()
        page.mouse.move(target,y,steps=8); page.mouse.up()


def test_brackets_replace_independent_sliders(page):
    open_editor(page)
    expect(page.locator('#trim-start')).to_be_visible()
    expect(page.locator('#trim-end')).to_be_visible()
    expect(page.locator('[data-setting=trimStart],[data-setting=trimEnd]')).to_have_count(0)
    expect(page.locator('#trim-start')).to_have_attribute('role','slider')

@pytest.mark.parametrize('touch', [False, True])
def test_drag_brackets_commits_real_range_once_and_undoes(page, touch):
    open_editor(page)
    drag(page,'start',2,touch)
    drag(page,'end',6,touch)
    r=segments(page)[0]
    assert r['start']==pytest.approx(2,abs=.08) and r['end']==pytest.approx(6,abs=.08)
    assert page.evaluate('FormaStudio.state.history.length')==2
    expect(page.locator('#trim-start')).to_have_attribute('aria-valuenow',str(r['start']))
    expect(page.locator('#trim-summary')).to_contain_text('Removed')
    page.locator('#undo').tap()
    assert segments(page)[0]['end']==pytest.approx(8,abs=.1)
    page.locator('#redo').tap()
    assert segments(page)==[r]


def test_scrubbing_does_not_change_cuts(page):
    open_editor(page)
    before=segments(page)
    page.locator('#scrub').evaluate('(e)=>{e.value=3;e.dispatchEvent(new Event("input",{bubbles:true}));}')
    assert segments(page)==before
    assert page.locator('#media').evaluate('(e)=>e.currentTime')==pytest.approx(3,abs=.03)


def test_keyboard_brackets_and_escape_cancel(page):
    open_editor(page)
    h=page.locator('#trim-start'); h.focus(); page.keyboard.press('ArrowRight')
    assert segments(page)[0]['start']==pytest.approx(.1)
    before=segments(page)
    h.evaluate('(e)=>e.scrollIntoView({block:"center",inline:"nearest"})'); r=h.bounding_box()
    page.mouse.move(r['x']+24,r['y']+30);page.mouse.down();page.mouse.move(r['x']+85,r['y']+30)
    page.keyboard.press('Escape');page.mouse.up()
    assert segments(page)==before
    assert page.evaluate('FormaStudio.state.history.length')==1


def test_brackets_never_overlap_or_cross(page):
    open_editor(page)
    page.evaluate('FormaStudio.change({segments:[{start:3,end:3.05}]})')
    a=page.locator('#trim-start').bounding_box();b=page.locator('#trim-end').bounding_box()
    assert a['x']+a['width']<=b['x']+.01
    page.locator('#trim-start').focus();page.keyboard.press('End')
    r=segments(page)[0];assert r['end']-r['start']>=.03999


def test_discard_masks_account_for_other_kept_clips(page):
    open_editor(page)
    page.evaluate('FormaStudio.change({segments:[{start:1,end:3},{start:5,end:7}]})')
    cuts=page.locator('.trim-cut').evaluate_all('(es)=>es.map(e=>[+e.dataset.start,+e.dataset.end])')
    assert cuts[:2]==[[0,1],[3,5]] and cuts[2][0]==7
    page.locator('[data-clip="1"]').tap()
    expect(page.locator('#trim-start')).to_have_attribute('aria-valuenow','5')
    expect(page.locator('#trim-end')).to_have_attribute('aria-valuenow','7')


def test_real_video_thumbnails_are_decoded(page):
    open_editor(page)
    page.wait_for_function('document.querySelectorAll("#trim-filmstrip img").length >= 4',timeout=15000)
    assert page.locator('#trim-filmstrip img').evaluate_all('(es)=>es.every(e=>e.complete && e.naturalWidth>0)')


def test_audio_uses_measured_waveform_and_same_brackets(page):
    open_editor(page,'audio')
    page.wait_for_function('document.querySelector("#trim-waveform path")',timeout=15000)
    drag(page,'start',1,True)
    assert segments(page)[0]['start']==pytest.approx(1,abs=.08)
    assert page.locator('#trim-waveform').get_attribute('data-measured')=='true'

@pytest.mark.parametrize('width,height',[(320,640),(390,844),(844,390),(1280,900)])
def test_timeline_targets_and_zoom_stay_inside_page(page,width,height):
    page.set_viewport_size({'width':width,'height':height});open_editor(page)
    assert not small_targets(page),small_targets(page)
    before=segments(page)
    page.locator('#timeline-zoom-in').tap()
    assert page.locator('#timeline-viewport').evaluate('(e)=>e.scrollWidth>e.clientWidth')
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    page.locator('#timeline-fit').tap()
    assert segments(page)==before
    assert page.locator('#timeline-viewport').evaluate('(e)=>e.scrollWidth<=e.clientWidth+1')


def test_numeric_trim_and_brackets_stay_in_sync(page):
    open_editor(page)
    page.locator('[data-setting=start]').fill('1.25');page.locator('[data-setting=start]').dispatch_event('change')
    expect(page.locator('#trim-start')).to_have_attribute('aria-valuenow','1.25')
    page.locator('#trim-end').focus();page.keyboard.press('ArrowLeft')
    assert float(page.locator('[data-setting=end]').input_value())==pytest.approx(7.9,abs=.1)


def test_dragged_range_produces_actual_trimmed_output(page,tmp_path):
    from planner import make_plan, build_command
    from server import inspect_media, encoder_capabilities
    open_editor(page);drag(page,'start',2);drag(page,'end',6)
    settings=page.evaluate('FormaStudio.state.settings')
    source=ROOT/'media/sample.mp4'
    info={'name':'sample.mp4',**inspect_media(source)}
    output=tmp_path/'trimmed.mp4'
    # Same planner used by the HTTP queue, with the exact settings committed by the UI.
    plan=make_plan(info,settings,encoder_capabilities('ffmpeg'))
    command=build_command(plan,str(source),str(output))
    subprocess.run(command,check=True,capture_output=True,timeout=30)
    result=inspect_media(output)
    assert result['duration']==pytest.approx(plan['duration'],abs=.15)
    assert result['duration']==pytest.approx(4,abs=.2)


def test_source_edge_tap_preserves_fractional_duration(page):
    open_editor(page)
    page.evaluate('''() => {const a=FormaStudio; a.activate({...a.state.source,duration:8.023},document.querySelector('#media').src);a.setView('edit');}''')
    before=segments(page)
    page.locator('#trim-end').tap()
    assert segments(page)==before
    assert page.evaluate('FormaStudio.state.history.length')==0


def test_drag_uses_zoom_scale_not_full_viewport(page):
    open_editor(page)
    page.locator('#timeline-zoom-in').tap()
    # Bring the start edge into view using the supplied panning control.
    page.locator('#timeline-viewport').evaluate('(e)=>e.scrollLeft=0')
    drag(page,'start',1)
    assert segments(page)[0]['start']==pytest.approx(1,abs=.1)


def test_pointer_cancel_restores_range_without_history(page):
    open_editor(page)
    h=page.locator('#trim-start');h.evaluate('(e)=>e.scrollIntoView({block:"center"})')
    b=h.bounding_box();x,y=b['x']+24,b['y']+50
    cdp=page.context.new_cdp_session(page)
    cdp.send('Input.dispatchTouchEvent',{'type':'touchStart','touchPoints':[{'x':x,'y':y}]})
    cdp.send('Input.dispatchTouchEvent',{'type':'touchMove','touchPoints':[{'x':x+50,'y':y}]})
    cdp.send('Input.dispatchTouchEvent',{'type':'touchCancel','touchPoints':[]})
    assert segments(page)[0]['start']==0
    assert page.evaluate('FormaStudio.state.history.length')==0
    cdp.detach()


def test_drag_updates_preview_and_removed_overlay_before_commit(page):
    open_editor(page)
    h=page.locator('#trim-start');h.evaluate('(e)=>e.scrollIntoView({block:"center"})')
    b=h.bounding_box();x,y=b['x']+24,b['y']+50
    page.mouse.move(x,y);page.mouse.down();page.mouse.move(x+50,y,steps=4)
    assert float(h.get_attribute('aria-valuenow'))>0
    assert page.locator('#trim-cuts .trim-cut').count()==1
    assert page.locator('#media').evaluate('(e)=>e.currentTime')>0
    assert segments(page)[0]['start']==0 # single transaction is still in progress
    page.mouse.up()
    assert segments(page)[0]['start']>0


def test_source_change_cancels_old_thumbnail_worker_and_selection(page):
    open_editor(page)
    page.locator('#file-input').set_input_files(str(ROOT/'media/sample.wav'))
    page.wait_for_function('FormaStudio.state.source?.hasVideo===false')
    page.locator('#tab-edit').tap()
    page.wait_for_function('document.querySelector("#trim-waveform path")',timeout=15000)
    expect(page.locator('#trim-filmstrip img')).to_have_count(0)
    expect(page.locator('#trim-start')).to_have_attribute('aria-valuenow','0')


def test_bracket_bounds_do_not_exceed_fractional_source_end(page):
    open_editor(page)
    page.evaluate('''() => {const a=FormaStudio; a.activate({...a.state.source,duration:8.023},document.querySelector('#media').src);a.setView('edit');}''')
    page.locator('[data-setting=end]').fill('99');page.locator('[data-setting=end]').dispatch_event('change')
    assert segments(page)[0]['end']==8.023


def test_short_source_brackets_remain_valid(page):
    open_editor(page)
    page.evaluate('''() => {const a=FormaStudio; a.activate({...a.state.source,duration:.023},document.querySelector('#media').src);a.setView('edit');}''')
    page.locator('#trim-start').focus();page.keyboard.press('ArrowRight')
    page.locator('#trim-end').focus();page.keyboard.press('ArrowLeft')
    assert segments(page)==[{'start':0,'end':.023}]


def test_overlapping_kept_clips_are_not_double_counted_as_source_removed(page):
    open_editor(page)
    page.evaluate('FormaStudio.change({segments:[{start:1,end:5},{start:3,end:7}]})')
    cuts=page.locator('.trim-cut').evaluate_all('(es)=>es.map(e=>[+e.dataset.start,+e.dataset.end])')
    assert cuts==[[0,1],[7,8]]
    expect(page.locator('#trim-summary')).to_contain_text('Removed 00:02.00')
    expect(page.locator('#trim-summary')).to_contain_text('Output 00:08.00')


def test_preview_selected_clip_does_not_play_next_kept_clip(page):
    open_editor(page)
    page.evaluate('FormaStudio.change({segments:[{start:1,end:1.4},{start:5,end:6}]})')
    page.locator('#preview-scope').select_option('clip')
    page.locator('#play').tap()
    page.wait_for_function('document.querySelector("#media").paused',timeout=4000)
    assert page.locator('#media').evaluate('(e)=>e.currentTime')==pytest.approx(1.4,abs=.06)


def test_preview_all_kept_clips_skips_removed_gaps(page):
    open_editor(page)
    page.evaluate('FormaStudio.change({segments:[{start:1,end:1.4},{start:5,end:5.4}]})')
    page.locator('#play').tap()
    page.wait_for_function('document.querySelector("#media").currentTime>=5',timeout=4000)
    page.wait_for_function('document.querySelector("#media").paused',timeout=4000)
    assert page.locator('#media').evaluate('(e)=>e.currentTime')==pytest.approx(5.4,abs=.06)


@pytest.mark.parametrize('width,height', [(1280,900), (1360,1030)])
def test_desktop_preview_and_bracket_readouts_are_visible_together(page,width,height):
    page.set_viewport_size({'width':width,'height':height})
    open_editor(page)
    page.evaluate('scrollTo(0,0)')
    readouts=page.locator('.trim-readouts').bounding_box()
    footer=page.locator('#export-bar').bounding_box()
    assert readouts['y']+readouts['height'] < footer['y']
    assert page.locator('.preview-stage').bounding_box()['height'] >= 200

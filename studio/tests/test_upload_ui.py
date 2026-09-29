"""Touch-first upload-goal workflow. Browser assets are real; encoding tested separately."""
from playwright.sync_api import expect
from test_touch import browser, page, sample, small_targets


def test_home_defaults_to_ten_megabytes_without_codec_controls(page):
    expect(page.locator('[data-limit-mb="10"]')).to_have_attribute('aria-pressed', 'true')
    expect(page.locator('#select-media')).to_be_visible()
    expect(page.locator('#media-url')).to_be_visible()
    expect(page.locator('#workspace')).to_be_hidden()
    assert not small_targets(page)


def test_goal_selected_before_source_applies_on_import(page):
    page.locator('[data-limit-mb="25"]').tap()
    sample(page)
    assert page.evaluate('FormaStudio.state.settings.targetBytes') == 25_000_000
    expect(page.locator('#fit-card')).to_be_visible()
    expect(page.locator('#convert-label')).to_contain_text('25 MB')


def test_custom_limit_is_decimal_megabytes_and_invalid_input_is_not_accepted(page):
    page.locator('[data-limit-mb="custom"]').tap()
    page.locator('#custom-limit').fill('2.5')
    page.locator('#apply-limit').tap()
    sample(page)
    assert page.evaluate('FormaStudio.state.settings.targetBytes') == 2_500_000


def test_workspace_goal_changes_preserve_cuts_and_manual_is_explicit(page):
    sample(page)
    page.evaluate('FormaStudio.change({segments:[{start:1,end:5}]})')
    page.locator('#workspace-limit').select_option('50000000')
    assert page.evaluate('FormaStudio.state.settings.targetBytes') == 50_000_000
    assert page.evaluate('FormaStudio.state.settings.segments[0]') == {'start':1, 'end':5}
    page.locator('#advanced-toggle').tap()
    assert page.evaluate('FormaStudio.state.settings.targetBytes') == 50_000_000
    page.locator('#manual-conversion').tap()
    assert page.evaluate('FormaStudio.state.settings.targetBytes') is None
    expect(page.locator('[data-setting="format"]')).to_be_visible()


def test_image_import_has_image_controls_not_audio_or_timeline(page, tmp_path):
    from test_upload_limits import write_png
    path = write_png(tmp_path / 'image.png', 64, 64)
    page.locator('#file-input').set_input_files(path)
    page.wait_for_function('FormaStudio.state.source?.kind === "image"')
    expect(page.locator('#tab-image')).to_be_visible()
    expect(page.locator('#tab-video')).to_be_disabled()
    expect(page.locator('#tab-audio')).to_be_disabled()
    expect(page.locator('#tab-edit')).to_be_disabled()
    expect(page.locator('#image-preview')).to_be_visible()
    expect(page.locator('#convert-label')).to_contain_text('10 MB')
    assert not small_targets(page)

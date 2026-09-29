"""Actual HTTP upload/import/job/download paths must retain the byte ceiling."""
from pathlib import Path
import pytest
from test_api import service, request, wait_job, source
from test_upload_limits import write_png
from planner import default_settings


@pytest.mark.parametrize('path,mime', [('/upload-ui.js','javascript'),('/upload.css','text/css')])
def test_upload_assets_served(service, path, mime):
    status, content, headers = request(service, path)
    assert status == 200 and mime in headers['Content-Type'] and content


def test_invalid_target_rejected_before_queue_mutation(service):
    src = source(service)
    count = len(service[0].jobs)
    settings = default_settings(src); settings['targetBytes'] = True
    status, result, _ = request(service, '/api/jobs', {'sourceId':src['id'], 'settings':settings})
    assert status == 400 and 'limit' in result['error'] and len(service[0].jobs) == count


def test_url_image_upload_and_verified_download(service, tmp_path):
    photo = write_png(tmp_path / 'picture.png', 320, 180)
    status, uploaded, _ = request(service, '/api/upload?name=picture.png', photo.read_bytes())
    assert status == 201 and uploaded['kind'] == 'image'
    # The URL importer must use probe results, not the URL's suffix or MIME guess.
    url = f'http://127.0.0.1:{service[1].server_port}/media/{uploaded["id"]}'
    status, src, _ = request(service, '/api/url', {'url':url})
    assert status == 201 and src['kind'] == 'image'
    settings = default_settings(src); settings['targetBytes'] = 32_000
    status, job, _ = request(service, '/api/jobs', {'sourceId':src['id'], 'settings':settings})
    assert status == 201
    result = wait_job(service, job['id'])
    assert result['status'] == 'completed', result
    status, content, headers = request(service, f'/output/{job["id"]}?download=1')
    assert status == 200 and 0 < len(content) < 32_000
    assert len(content) == result['size'] and result['fitsLimit'] and result['verified']
    assert '.webp' in headers['Content-Disposition'] and content[:4] == b'RIFF'

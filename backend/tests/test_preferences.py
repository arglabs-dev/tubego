from datetime import datetime,timezone,timedelta
import uuid
import pytest
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from tubego_server.auth import token_hash,utcnow
from tubego_server.preferences import (MediaPreferences, resolve_selection,
    download_options, resource_fields, quality_notice, verified_output_path)


def seed(app,status='approved',verified=True):
    user,token=str(uuid.uuid4()),str(uuid.uuid4()); now=utcnow()
    with app.state.db.transaction() as conn:
        conn.execute('INSERT INTO users VALUES (?,?,?,?,?,?,?,?)',(user,user+'@example.com','hash','user',status,now if verified else None,now,now))
        conn.execute('INSERT INTO sessions VALUES (?,?,NULL,?,?,NULL,?)',(str(uuid.uuid4()),user,token_hash(token),(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),now))
    return user,{'Authorization':'Bearer '+token}


def test_preferences_persist_private_and_strict(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:
        u,a=seed(app); _,b=seed(app)
        expected={'ask_every_time':True,'selection':'720','rewind_seconds':10}
        assert client.get('/api/v1/account/preferences',headers=a).json()==expected
        value={'ask_every_time':False,'selection':'audio','rewind_seconds':20}
        assert client.put('/api/v1/account/preferences',headers=a,json=value).json()==value
        assert client.get('/api/v1/account/preferences',headers=b).json()==expected
        assert client.put('/api/v1/account/preferences',headers=a,json={**value,'owner_id':'other'}).status_code==422
        for invalid in ({**value,'selection':'4k'},{**value,'ask_every_time':'true'}, {**value,'rewind_seconds':-1},{**value,'rewind_seconds':200}):
            assert client.put('/api/v1/account/preferences',headers=a,json=invalid).status_code==422
        _,pending=seed(app,status='pending_approval')
        _,unverified=seed(app,verified=False)
        for headers in ({},pending,unverified):
            assert client.get('/api/v1/account/preferences',headers=headers).status_code in (401,403)
            assert client.put('/api/v1/account/preferences',headers=headers,json=value).status_code in (401,403)
    with TestClient(create_app(Settings(tmp_path))) as client:
        assert client.get('/api/v1/account/preferences',headers=a).json()==value


def test_selection_before_enqueue():
    default=MediaPreferences()
    with pytest.raises(ValueError): resolve_selection(default)
    assert resolve_selection(default,'480')=='480'
    saved=MediaPreferences(ask_every_time=False,selection='audio')
    assert resolve_selection(saved)=='audio' and resolve_selection(saved,'1080')=='1080'
    assert saved.selection=='audio'
    with pytest.raises(ValueError): resolve_selection(saved,'$(touch /tmp/file)')


@pytest.mark.parametrize('selection', ['480','720','1080'])
def test_video_never_upscales(selection):
    options=download_options(selection)
    assert options['format']==f'bestvideo[height<={selection}]+bestaudio/best[height<={selection}]'
    assert options['merge_output_format']=='mp4'
    assert 'scale' not in repr(options)
    assert quality_notice(selection,360)=='lower_quality_available'
    assert quality_notice(selection,None)=='quality_unknown'
    assert quality_notice(selection,int(selection)) is None


def test_audio_and_best():
    assert download_options('best')['format']=='bestvideo+bestaudio/best'
    assert download_options('audio')['postprocessors'][0]['preferredcodec']=='mp3'
    assert resource_fields('audio')=={'media_format':'audio','quality':'best'}
    assert quality_notice('best',720) is None
    with pytest.raises(ValueError): download_options('evil')


def test_actual_postprocessed_path(tmp_path):
    root=tmp_path/'media';root.mkdir()
    output=root/'123.mp3';output.write_bytes(b'fake')
    assert verified_output_path({'filepath':str(output)},root)==output
    with pytest.raises(ValueError): verified_output_path({'filename':str(output)},root)
    outside=tmp_path/'outside.mp4';outside.write_bytes(b'fake')
    with pytest.raises(ValueError): verified_output_path({'filepath':str(outside)},root)
    link=root/'link.mp4';link.symlink_to(outside)
    with pytest.raises(ValueError): verified_output_path({'filepath':str(link)},root)


def test_real_ffmpeg_audio_postprocessing(tmp_path):
    import subprocess
    import shutil
    from yt_dlp import YoutubeDL
    from yt_dlp.postprocessor.ffmpeg import FFmpegExtractAudioPP
    if not shutil.which('ffmpeg'): pytest.skip('FFmpeg required for local postprocessing')
    root=tmp_path/'media';root.mkdir()
    source=root/'lecture.wav'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','anullsrc=r=44100:cl=mono',
                    '-t','0.1',str(source)],check=True)
    with YoutubeDL({'quiet':True}) as engine:
        processor=FFmpegExtractAudioPP(engine,preferredcodec='mp3',preferredquality='192')
        to_delete,info=processor.run({'filepath':str(source),'ext':'wav'})
    output=verified_output_path(info,root)
    assert output.suffix=='.mp3' and output.stat().st_size>0
    assert str(source) in to_delete


def test_real_ytdlp_selector_uses_available_lower_video():
    from yt_dlp import YoutubeDL
    info={'id':'lecture','title':'Lecture','extractor':'test','webpage_url':'https://video.example/v',
        'formats':[
            {'format_id':'low','url':'https://video.example/low.mp4','ext':'mp4','height':360,'width':640,'vcodec':'h264','acodec':'aac'},
            {'format_id':'high','url':'https://video.example/high.mp4','ext':'mp4','height':1080,'width':1920,'vcodec':'h264','acodec':'aac'}]}
    with YoutubeDL({**download_options('480'),'quiet':True}) as engine:
        selected=engine.process_ie_result(info,download=False)
    assert selected['height']==360 and selected['format_id']=='low'
    assert quality_notice('480',selected['height'])=='lower_quality_available'

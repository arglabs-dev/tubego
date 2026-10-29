"""PLA-265: source metadata, real yt-dlp selection, FFmpeg and private variants."""
import copy
import json
import shutil
import subprocess
import uuid
import pytest
from yt_dlp import YoutubeDL
from tubego_server.audio_tracks import catalog, resolve, restrict, verify_output
from tubego_server.media import MediaError, analyze_media
from tubego_server.preferences import download_options
from test_private_library import service, owner
from test_media import public_dns, factory


def metadata():
    def fmt(identifier, language, note):
        return dict(format_id=identifier, url='https://video.example/'+identifier+'.m4a',
                    ext='m4a', acodec='aac', vcodec='none', language=language, format_note=note)
    return dict(id='multi', title='Multilingual', extractor='test', webpage_url='https://video.example/multi', formats=[
        dict(format_id='v720', url='https://video.example/v.mp4', ext='mp4', acodec='none', vcodec='h264',height=720),
        dict(format_id='v1080', url='https://video.example/v1080.mp4', ext='mp4', acodec='none', vcodec='h264',height=1080),
        fmt('a-en','en','English original'), fmt('a-es','es-es','Spanish dubbed'),
        fmt('a-es-auto','es-419','Spanish auto-dubbed'), fmt('a-es-auto-low','es-419','Spanish auto-dubbed')])


def test_catalog_uses_audio_formats_only_and_preserves_regions():
    info=metadata()
    info['automatic_captions']={'es': [{'url':'https://video.example/subs'}]}
    tracks=catalog(info)
    assert len(tracks)==3
    assert tracks[0]=={'id':'en~original','language':'en','kind':'original'}
    assert resolve(info,'es-419~auto')['kind']=='auto'
    assert resolve(info,'original')['language']=='en'
    assert catalog({'automatic_captions':{'es':[]},'subtitles':{'es':[]}})==[]
    assert resolve({'formats':[]},'original') is None
    with pytest.raises(MediaError,match='no longer available'):resolve(info,'es~unknown')
    invalid=metadata();invalid['formats'][2]['has_drm']=True
    assert not any(t['language']=='en' for t in catalog(invalid))
    analyzed=analyze_media('https://video.example/multi',factory=factory(info),resolver=public_dns)
    assert analyzed['audio_tracks']==tracks
    assert 'formats' not in analyzed and 'a-en' not in str(analyzed)


@pytest.mark.parametrize('choice,language', [('original','en'),('es-es~dubbed','es-es'),('es-419~auto','es-419')])
@pytest.mark.parametrize('selection',['480','720','1080','best','audio'])
def test_real_selector_never_falls_back_to_another_audio(choice,language,selection):
    info=metadata();restrict(info,resolve(info,choice))
    with YoutubeDL({**download_options(selection),'quiet':True,'no_warnings':True}) as engine:
        selected=engine.process_ie_result(info,download=False)
    streams=selected.get('requested_formats') or [selected]
    audio=[f for f in streams if f.get('acodec')!='none']
    assert len(audio)==1 and audio[0]['language']==language
    if selection not in ('audio','best'):
        assert selected['height']==(1080 if selection=='1080' else 720)


def test_private_variants_and_replay_do_not_reuse_another_language(service,monkeypatch):
    app,client=service;alice,bob=owner(app),owner(app)
    monkeypatch.setattr('tubego_server.tasks.normalize_url',lambda value:value)
    path='/api/v1/resources'
    request=dict(url='https://youtube.com/watch?v=abcdefghijk',selection='720',request_id=str(uuid.uuid4()))
    original=client.post(path,headers=alice['headers'],json=request).json()['resource_id']
    spanish={**request,'audio_track':'es-419~auto','request_id':str(uuid.uuid4())}
    other=client.post(path,headers=alice['headers'],json=spanish).json()['resource_id']
    assert original!=other
    assert client.post(path,headers=alice['headers'],json=spanish).json()['resource_id']==other
    assert client.post(path,headers=alice['headers'],json={**spanish,'audio_track':'original'}).status_code==409
    assert client.post(path,headers=alice['headers'],json={**spanish,'url':'https://youtu.be/abcdefghijk','request_id':str(uuid.uuid4())}).json()['resource_id']==other
    assert client.get(path+'/'+other,headers=alice['headers']).json()['audio_track']=='es-419~auto'
    assert client.get(path+'/'+other,headers=bob['headers']).status_code==404
    assert client.post(path,headers=alice['headers'],json={**spanish,'audio_track':'es]/best'}).status_code==422
    assert len(client.get(path,headers=alice['headers']).json()['items'])==2


@pytest.fixture
def audio_file(tmp_path):
    if not shutil.which('ffmpeg') or not shutil.which('ffprobe'):pytest.skip('FFmpeg required')
    path=tmp_path/'audio.m4a'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=frequency=440:duration=0.2','-c:a','aac',str(path)],check=True)
    return path


@pytest.mark.parametrize('choice',['es-es~dubbed','es-419~auto'])
def test_final_file_verified_with_real_ffmpeg_and_ffprobe(audio_file,choice):
    track=resolve(metadata(),choice)
    audio=next(f for f in metadata()['formats'] if f.get('language')==track['language'])
    result=verify_output(audio,audio_file,track,'audio')
    assert result['verified'] and result['language']==track['language']
    probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(audio_file)]))
    assert probe['streams'][0]['tags']['language']=='spa'


def test_false_success_rejected_for_wrong_identity_and_broken_file(audio_file):
    es=resolve(metadata(),'es-es~dubbed')
    en=metadata()['formats'][2]
    with pytest.raises(MediaError):verify_output(en,audio_file,es,'audio')
    # YouTube source container tags can be wrong; checked format identity wins.
    verify_output(en,audio_file,resolve(metadata(),'original'),'audio')
    spanish=metadata()['formats'][3]
    assert verify_output(spanish,audio_file,es,'audio')['language']=='es-es' 
    audio_file.write_bytes(b'broken')
    with pytest.raises(MediaError):verify_output(spanish,audio_file,es,'audio')


def test_edit_allowed_only_before_worker_claim_and_duplicate_is_rejected(service,monkeypatch):
    app,client=service;alice,bob=owner(app),owner(app)
    monkeypatch.setattr('tubego_server.tasks.normalize_url',lambda value:value)
    body={'url':'https://video.example/v','selection':'720'}
    rid=client.post('/api/v1/resources',headers=alice['headers'],json=body).json()['resource_id']
    change={'audio_track':'es-es~dubbed','request_id':str(uuid.uuid4())}
    path=f'/api/v1/resources/{rid}/audio-track'
    assert client.post(path,headers=bob['headers'],json=change).status_code==404
    assert client.post(path,headers=alice['headers'],json=change).status_code==200
    assert client.post(path,headers=alice['headers'],json=change).status_code==200
    assert client.get('/api/v1/resources/'+rid,headers=alice['headers']).json()['audio_track']=='es-es~dubbed'
    # Changing a resource updates its dedup identity; original now gets its own resource.
    original=client.post('/api/v1/resources',headers=alice['headers'],json=body).json()['resource_id']
    assert original!=rid
    assert client.post(path,headers=alice['headers'],json={'audio_track':'original','request_id':str(uuid.uuid4())}).status_code==409
    from tubego_server.scheduler import Scheduler
    claimed=Scheduler(app.state.db).claim()
    locked='/api/v1/resources/'+claimed['resource_id']+'/audio-track'
    assert client.post(locked,headers=alice['headers'],json={'audio_track':'es-419~auto','request_id':str(uuid.uuid4())}).status_code==409


@pytest.mark.parametrize('choice,selection',[('original','720'),('es-es~dubbed','720'),('es-419~auto','720'),('es-es~dubbed','audio')])
def test_worker_real_download_merge_probe_and_delivery(service,monkeypatch,tmp_path,choice,selection):
    if not shutil.which('ffmpeg'):pytest.skip('FFmpeg required')
    from http.server import BaseHTTPRequestHandler
    from test_egress_proxy import proxy_fixture,answers
    from test_worker import seed
    from tubego_server.media import restricted_ytdlp
    from tubego_server.tasks import submit
    from tubego_server.worker import Worker
    from tubego_server.delivery import read_setting
    app,client=service;principal,headers,device=seed(app)
    # Real encoded source tracks with distinguishable waveforms, not pre-tagged copies.
    assets={}
    video=tmp_path/'source.mp4'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','color=size=1280x720:rate=10:duration=0.3','-an','-c:v','libx264','-preset','ultrafast',str(video)],check=True)
    assets['/v.mp4']=video.read_bytes();assets['/v1080.mp4']=assets['/v.mp4']
    for name,frequency in [('a-en',880),('a-es',440),('a-es-auto',440),('a-es-auto-low',440)]:
        path=tmp_path/(name+'.m4a')
        subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i',f'sine=frequency={frequency}:duration=0.3','-c:a','aac',str(path)],check=True)
        assets['/'+path.name]=path.read_bytes()
    class Source(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            data=assets[self.path]
            self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    monkeypatch.setattr('test_egress_proxy.Source',Source)
    with proxy_fixture() as (proxy,contacted):
        monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY',f'http://127.0.0.1:{proxy.server_address[1]}')
        def engine_factory(options):
            engine=restricted_ytdlp(options,resolver=answers)
            info=metadata()
            for fmt in info['formats']:fmt['url']=fmt['url'].replace('https://','http://')
            engine.extract_info=lambda *args,**kwargs:copy.deepcopy(info)
            return engine
        submitted=submit(app.state.db,principal,'https://video.example/multi',selection,resolver=answers,audio_track=choice)
        with Worker(app.state.settings,engine_factory) as worker:assert worker.run_once()
    task=client.get('/api/v1/tasks/'+submitted['task']['id'],headers=headers).json()
    assert task['status']=='completed',task
    rid=submitted['resource_id']
    resource=client.get('/api/v1/resources/'+rid,headers=headers).json()
    expected='en' if choice=='original' else choice.split('~')[0]
    assert resource['audio_resolved']['language']==expected
    assert resource['audio_resolved']['verified'] is True
    synced=client.get('/api/v1/device/sync',headers=headers).json()['deliveries'][0]
    assert synced['audio_track']==choice and synced['audio_resolved']['language']==expected
    data=client.get('/api/v1/resources/'+rid+'/download',headers=headers)
    assert data.status_code==200
    output=tmp_path/('delivered.mp3' if selection=='audio' else 'delivered.mp4');output.write_bytes(data.content)
    # Decode the delivered bytes: distinguish the selected Spanish fixture (440 Hz)
    # from the original (880 Hz), independent of language tags or selected metadata.
    import struct
    pcm=subprocess.check_output(['ffmpeg','-v','error','-i',str(output),'-vn','-ac','1','-ar','8000','-f','s16le','-'])
    samples=struct.unpack('<'+'h'*(len(pcm)//2),pcm)
    crossings=sum(a<0<=b for a,b in zip(samples,samples[1:]))
    frequency=crossings/(len(samples)/8000)
    assert abs(frequency-(880 if choice=='original' else 440))<35


def test_reprocessing_previously_analyzed_metadata_does_not_retain_original_selection():
    with YoutubeDL({**download_options('720'),'quiet':True,'no_warnings':True}) as engine:
        analyzed=engine.process_ie_result(metadata(),download=False)
        restrict(analyzed,resolve(analyzed,'es-es~dubbed'))
        downloaded=engine.process_ie_result(analyzed,download=False)
    from tubego_server.audio_tracks import selected_audio
    assert selected_audio(downloaded)[0]['language']=='es-es'

"""Policy validation uses the real YoutubeDL constructor, without any network call."""
from tubego_server.media import restricted_ytdlp

def test_only_packaged_ejs_and_sandboxed_deno_are_allowed(monkeypatch):
    monkeypatch.setenv('TUBEGO_MEDIA_EGRESS_PROXY','http://127.0.0.1:8081')
    with restricted_ytdlp({'quiet':True,'nocheckcertificate':True,'js_runtimes':{'node':{},'bun':{}},'remote_components':['ejs:npm','ejs:github']}) as engine:
        assert engine.params['js_runtimes']=={'deno':{}}
        assert engine.params['remote_components']==set()
        assert engine.params['external_downloader'] is None
        assert engine.params['nocheckcertificate'] is False
        assert not engine.params['enable_file_urls']
        assert engine.params['cookiefile'] is None
        assert engine.params['cookiesfrombrowser'] is None

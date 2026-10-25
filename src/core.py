import yt_dlp
import os
import shutil
import errno
from pathlib import Path
from src.disk_guard import DiskGuard, StoragePaused, notify_admin_storage

class Downloader:
    def __init__(self, download_dir="downloads"):
        self.download_dir = download_dir
        if not os.path.exists(self.download_dir):
            os.makedirs(self.download_dir)
        self.disk = DiskGuard(download_dir,notify=lambda state:notify_admin_storage(state,
            Path(os.getenv('TUBEGO_DATA_DIR','data/mobile'))/'tubego.sqlite3'))
        self.has_ffmpeg = shutil.which('ffmpeg') is not None

    def get_format_string(self, mode, quality):
        if not self.has_ffmpeg:
            if mode == 'audio': return 'bestaudio[ext=m4a]/bestaudio'
            if quality == 'max' or quality == '1080': return 'best[ext=mp4]/best'
            return f'best[height<={quality}][ext=mp4]/best[ext=mp4]/best'
        
        if mode == 'audio': return 'bestaudio/best'
        if quality == 'max' or quality == 'best': return 'bestvideo+bestaudio/best'
        return f'bestvideo[height<={quality}][ext=mp4]+bestaudio[ext=m4a]/best[height<={quality}][ext=mp4]/best'

    def get_video_info(self, url):
        ydl_opts = {'quiet': True, 'no_warnings': True, 'skip_download': True}
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=False)
                return {
                    "status": "success",
                    "title": info.get('title', 'Unknown'),
                    "duration": info.get('duration_string', 'N/A'),
                    "uploader": info.get('uploader', 'Unknown'),
                    "thumbnail": info.get('thumbnail', None)
                }
        except Exception as e: return {"status": "error", "message": str(e)}

    def download(self, url, mode='video', quality='720', progress_hook=None, check_cancel=None):
        def cancel_check():
            if check_cancel and check_cancel(): raise Exception("CANCELLED_BY_USER")
        def wait_for_disk(required=0):
            self.disk.wait(check_cancel=cancel_check,required_bytes=required,
                on_pause=lambda state:progress_hook({'status':'paused','pause_reason':'server_storage'}) if progress_hook else None,
                on_resume=lambda:progress_hook({'status':'resuming'}) if progress_hook else None)
        def internal_hook(d):
            cancel_check()
            total=d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            wait_for_disk(max(0,total-d.get('downloaded_bytes',0)))
            if progress_hook: progress_hook(d)
        def processing_hook(d):
            cancel_check()
            required=sum(p.stat().st_size for p in Path(self.download_dir).iterdir() if p.is_file())*2
            wait_for_disk(required)


        ydl_opts = {
            'outtmpl': os.path.join(self.download_dir, '%(title).100s.%(ext)s'),
            'progress_hooks': [internal_hook], 'postprocessor_hooks':[processing_hook],
            'continuedl':True, 'hls_prefer_native':True,
            'quiet': True, 'no_warnings': True, 'restrictfilenames': True
        }
        ydl_opts['format'] = self.get_format_string(mode, quality)

        if self.has_ffmpeg:
            if mode == 'audio':
                ydl_opts['postprocessors'] = [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'}]
            else:
                ydl_opts['merge_output_format'] = 'mp4'

        class GuardedDownloader(yt_dlp.YoutubeDL):
            def dl(self,name,info,subtitle=False,test=False):
                from yt_dlp.downloader import get_suitable_downloader
                from yt_dlp.downloader.http import HttpFD
                from yt_dlp.downloader.hls import HlsFD
                from yt_dlp.downloader.dash import DashSegmentsFD
                if get_suitable_downloader(info,self.params,to_stdout=(name=='-')) not in (HttpFD,HlsFD,DashSegmentsFD):
                    raise ValueError('This source requires an unmanaged network downloader')
                return super().dl(name,info,subtitle=subtitle,test=test)

        while True:
            try:
                wait_for_disk()
                with GuardedDownloader(ydl_opts) as ydl:
                    original=ydl.urlopen
                    def guarded_request(request):
                        cancel_check();wait_for_disk()
                        return original(request)
                    ydl.urlopen=guarded_request
                    info = ydl.extract_info(url, download=True)
                    wait_for_disk()
                    return {
                        "status": "success", "title": info.get('title', 'Unknown'),
                        "path": ydl.prepare_filename(info), "ffmpeg_used": self.has_ffmpeg
                    }
            except Exception as e:
                if str(e)=="CANCELLED_BY_USER":return {"status":"cancelled","message":"Cancelado por usuario"}
                if (isinstance(e,OSError) and e.errno==errno.ENOSPC) or 'No space left on device' in str(e):
                    import time
                    time.sleep(2)
                    wait_for_disk()
                    continue
                return {"status":"error","message":str(e)}

    def list_downloads(self):
        try: return [f for f in os.listdir(self.download_dir) if os.path.isfile(os.path.join(self.download_dir, f))]
        except: return []

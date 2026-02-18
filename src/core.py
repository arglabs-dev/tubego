import yt_dlp
import os
import shutil

class Downloader:
    def __init__(self, download_dir="downloads"):
        self.download_dir = download_dir
        if not os.path.exists(self.download_dir):
            os.makedirs(self.download_dir)
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
        def internal_hook(d):
            if check_cancel and check_cancel(): raise Exception("CANCELLED_BY_USER")
            if progress_hook: progress_hook(d)

        ydl_opts = {
            'outtmpl': os.path.join(self.download_dir, '%(title).100s.%(ext)s'),
            'progress_hooks': [internal_hook],
            'quiet': True, 'no_warnings': True, 'restrictfilenames': True
        }
        ydl_opts['format'] = self.get_format_string(mode, quality)

        if self.has_ffmpeg:
            if mode == 'audio':
                ydl_opts['postprocessors'] = [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'mp3', 'preferredquality': '192'}]
            else:
                ydl_opts['merge_output_format'] = 'mp4'

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                return {
                    "status": "success", "title": info.get('title', 'Unknown'),
                    "path": ydl.prepare_filename(info), "ffmpeg_used": self.has_ffmpeg
                }
        except Exception as e: return {"status": "error", "message": str(e)}

    def list_downloads(self):
        try: return [f for f in os.listdir(self.download_dir) if os.path.isfile(os.path.join(self.download_dir, f))]
        except: return []

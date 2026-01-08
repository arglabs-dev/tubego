import yt_dlp
import os
import shutil

class Downloader:
    def __init__(self, download_dir="downloads"):
        self.download_dir = download_dir
        if not os.path.exists(self.download_dir):
            os.makedirs(self.download_dir)
        
        # Detectar si tenemos ffmpeg (Generalmente SI en PC, NO en Android)
        self.has_ffmpeg = shutil.which('ffmpeg') is not None

    def get_format_string(self, mode, quality):
        # --- MODO SIN FFMPEG (ANDROID / LIGHT) ---
        if not self.has_ffmpeg:
            if mode == 'audio':
                # Descargar audio nativo (m4a/aac) que Android lee bien sin convertir
                return 'bestaudio[ext=m4a]/bestaudio'
            else:
                # Video: Buscar el mejor archivo que YA tenga video+audio juntos.
                # YouTube limita esto usualmente a 720p. Si pedimos 1080p sin ffmpeg,
                # yt-dlp bajaría video y audio separados y fallaría al unirlos.
                if quality == 'max' or quality == '1080':
                    # Intentamos buscar si existe (raro), si no, caerá al mejor disponible
                    return 'best[ext=mp4]/best' 
                return f'best[height<={quality}][ext=mp4]/best[ext=mp4]/best'

        # --- MODO CON FFMPEG (PC / FULL) ---
        if mode == 'audio':
            return 'bestaudio/best'
        
        if quality == 'max':
            return 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best'
        
        return f'bestvideo[height<={quality}][ext=mp4]+bestaudio[ext=m4a]/best[height<={quality}][ext=mp4]/best'

    def download(self, url, mode='video', quality='720', progress_hook=None):
        """
        Descarga inteligente adaptada a la presencia de FFMPEG.
        """
        
        ydl_opts = {
            'outtmpl': os.path.join(self.download_dir, '%(title)s.%(ext)s'),
            'progress_hooks': [progress_hook] if progress_hook else [],
            'quiet': True,
            'no_warnings': True,
        }

        fmt = self.get_format_string(mode, quality)
        ydl_opts['format'] = fmt

        # Solo configuramos post-procesadores si existe FFMPEG
        if self.has_ffmpeg:
            if mode == 'audio':
                ydl_opts['postprocessors'] = [{
                    'key': 'FFmpegExtractAudio',
                    'preferredcodec': 'mp3',
                    'preferredquality': '192',
                }]
            else:
                # En video, merge_output_format requiere ffmpeg
                ydl_opts['merge_output_format'] = 'mp4'
        else:
            # Ajustes para móvil/sin ffmpeg
            # No forzamos merge ni conversión.
            pass

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                
                # Si estamos en modo audio sin ffmpeg, el archivo será .m4a
                # Si estamos en modo audio con ffmpeg, será .mp3
                
                return {
                    "status": "success", 
                    "title": info.get('title', 'Unknown'),
                    "path": ydl.prepare_filename(info),
                    "ffmpeg_used": self.has_ffmpeg
                }
        except Exception as e:
            return {"status": "error", "message": str(e)}

    def list_downloads(self):
        try:
            return [f for f in os.listdir(self.download_dir) if os.path.isfile(os.path.join(self.download_dir, f))]
        except:
            return []

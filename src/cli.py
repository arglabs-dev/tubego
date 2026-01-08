import argparse
import sys
from src.core import Downloader

def progress_hook(d):
    if d['status'] == 'downloading':
        try:
            p = d.get('_percent_str', 'N/A').replace('%','')
            print(f"Descargando: {p}% completado", end='\r')
        except:
            pass
    elif d['status'] == 'finished':
        print("\nDescarga completada, procesando...")

def run_cli():
    parser = argparse.ArgumentParser(description="YouTube Downloader CLI v0.2")
    parser.add_argument("url", help="URL del video de YouTube")
    parser.add_argument("--type", choices=['video', 'audio'], default='video', help="Tipo de descarga (default: video)")
    parser.add_argument("--quality", choices=['max', '1080', '720', '480'], default='480', help="Calidad del video (default: 480)")
    
    args = parser.parse_args()
    
    # Si el usuario elige audio, la calidad no importa, forzamos modo audio
    mode = args.type
    quality = args.quality
    
    print(f"Iniciando descarga de: {args.url}")
    print(f"Modo: {mode} | Calidad: {quality if mode == 'video' else 'N/A (Audio)'}")
    
    downloader = Downloader()
    result = downloader.download(args.url, mode=mode, quality=quality, progress_hook=progress_hook)
    
    if result['status'] == 'success':
        print(f"\n¡Éxito! Archivo guardado en: {result['path']}")
    else:
        print(f"\nError: {result['message']}")

if __name__ == "__main__":
    run_cli()
import yt_dlp
import json

url = "https://youtu.be/LOn-mmezykQ"

ydl_opts = {
    'quiet': True,
    'no_warnings': True,
    'skip_download': True,
}

print(f"Analizando: {url}")

with yt_dlp.YoutubeDL(ydl_opts) as ydl:
    info = ydl.extract_info(url, download=False)
    
    print("\n--- FORMATOS DE AUDIO ENCONTRADOS ---")
    formats = info.get('formats', [])
    found_langs = set()
    
    for f in formats:
        # Filtrar solo audio o video+audio
        if f.get('acodec') != 'none':
            lang = f.get('language')
            note = f.get('format_note')
            print(f"ID: {f['format_id']} | Lang: {lang} | Note: {note} | Acodec: {f['acodec']}")
            if lang:
                found_langs.add(lang)

    print("\n--- IDIOMAS DETECTADOS (Resumen) ---")
    print(list(found_langs))

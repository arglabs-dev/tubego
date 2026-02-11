import yt_dlp

url = "https://youtu.be/LOn-mmezykQ"

# Intentamos forzar la detección de todos los streams
ydl_opts = {
    'quiet': True,
    'skip_download': True,
    # Estas opciones a veces ayudan a revelar tracks ocultos
    'extractor_args': {'youtube': {'include_dubs': True}},
}

print(f"Analizando (Con include_dubs): {url}")

with yt_dlp.YoutubeDL(ydl_opts) as ydl:
    info = ydl.extract_info(url, download=False)
    
    formats = info.get('formats', [])
    found_langs = set()
    
    print(f"Formatos Totales: {len(formats)}")
    
    for f in formats:
        lang = f.get('language')
        if lang:
            found_langs.add(lang)
            # Imprimir solo si es diferente a ingles para no spamear
            if lang != 'en':
                print(f"DETECTADO: {lang} - {f.get('format_note')}")

    print("\n--- IDIOMAS ---")
    print(list(found_langs))

"""Portable descriptive download names; media extensions are selected by the server."""
import re
import unicodedata


def download_name(title, media_format):
    title = unicodedata.normalize('NFKC', title or '')
    title = ''.join(' ' if unicodedata.category(c).startswith('C') or c in '<>:"/\\|?*' else c for c in title)
    title = re.sub(r'\s+', ' ', title).strip(' .') or 'Video'
    if re.fullmatch(r'CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]', title.split('.')[0], re.I):
        title = 'Video ' + title
    title = title.encode('utf-8')[:160].decode('utf-8', errors='ignore').rstrip(' .')
    return title + ('.mp3' if media_format == 'audio' else '.mp4')

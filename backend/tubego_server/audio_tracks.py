"""Per-request source audio choices; never infer audio from subtitles or titles."""
import json
import errno
import re
import subprocess
from typing import Annotated
from pydantic import StringConstraints
from tubego_server.media import MediaError

AudioChoice = Annotated[str, StringConstraints(pattern=r'^(original|[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}~(?:original|dubbed|auto|unknown))$', max_length=64)]
LANGUAGE = re.compile(r'^[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}$')


def describe(fmt):
    if not isinstance(fmt, dict) or fmt.get('acodec') in (None, 'none') or fmt.get('has_drm'):
        return None
    language = str(fmt.get('language') or '').lower()
    if not LANGUAGE.fullmatch(language):
        return None
    note = str(fmt.get('format_note') or '').lower()
    if any(word in note for word in ('auto-dubbed', 'auto dubbed', 'automatically dubbed')):
        kind = 'auto'
    elif 'original' in note:
        kind = 'original'
    elif 'dubbed' in note or 'dubbing' in note:
        kind = 'dubbed'
    else:
        kind = 'unknown'
    return {'id': language+'~'+kind, 'language': language, 'kind': kind}


def catalog(info):
    choices = {}
    for fmt in info.get('formats') or []:
        track = describe(fmt)
        if track:
            choices[track['id']] = track
    return sorted(choices.values(), key=lambda t: (t['kind'] != 'original', t['language'], t['kind']))


def resolve(info, choice):
    tracks = catalog(info)
    if choice == 'original':
        originals = [t for t in tracks if t['kind'] == 'original']
        # Without evidence, preserve the extractor's established default behavior.
        return originals[0] if len(originals) == 1 else None
    for track in tracks:
        if track['id'] == choice:
            return track
    raise MediaError('audio_track_unavailable')


def restrict(info, track):
    """Keep all video-only qualities, but only the chosen audio (including muxed).

    yt-dlp's existing quality selector then works normally, including its lower
    quality fallback, with no path capable of selecting a different language.
    """
    if track:
        info['formats'] = [fmt for fmt in info.get('formats', [])
                           if fmt.get('acodec') == 'none' or describe(fmt) == track]


def selected_audio(info):
    downloads = info.get('requested_downloads')
    if isinstance(downloads, list) and len(downloads) == 1:
        info = downloads[0]
    return [fmt for fmt in info.get('requested_formats') or [info]
            if fmt.get('acodec') not in (None, 'none')]


def _probe(path):
    try:
        result = subprocess.run(['ffprobe', '-v', 'error', '-protocol_whitelist', 'file,crypto,data', '-show_streams', '-show_format',
                                 '-of', 'json', str(path)], capture_output=True, timeout=60, check=True)
        return json.loads(result.stdout)
    except (OSError, ValueError, subprocess.SubprocessError):
        raise MediaError('audio_verification_failed') from None


def _base(language):
    from yt_dlp.utils import ISO639Utils
    code = language.lower().split('-')[0]
    return ISO639Utils.short2long(code) or code


def verify_output(info, path, track, selection, check=lambda: None):
    """Prove selected format identity and final audio stream before publication.

    Language tags derive from the checked source format, not speech recognition.
    Some source files lack tags; persist that verified language for external players.
    ffprobe validates the final stream and tag after local lossless remuxing.
    """
    audio = selected_audio(info)
    if len(audio) != 1 or describe(audio[0]) != track:
        raise MediaError('audio_verification_failed')
    check()
    data = _probe(path)
    streams = [s for s in data.get('streams', []) if s.get('codec_type') == 'audio']
    if len(streams) != 1 or not streams[0].get('codec_name'):
        raise MediaError('audio_verification_failed')
    language = track['language']
    # Container language tags are not source track identity: real YouTube Spanish
    # Opus streams can carry an 'eng' tag. Only the checked extractor format
    # identifies the choice; replace unreliable tags after verifying that format.
    videos = [s for s in data.get('streams', []) if s.get('codec_type') == 'video']
    if selection != 'audio' and len(videos) != 1:
        raise MediaError('audio_verification_failed')
    temporary = path.with_name('verified-'+path.name)
    try:
        check()
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-protocol_whitelist', 'file,crypto,data', '-i', str(path), '-map', '0',
                        '-c', 'copy', '-metadata', 'language='+_base(language),
                        '-metadata:s:a:0', 'language='+_base(language), str(temporary)],
                       capture_output=True, timeout=300, check=True)
        check()
        verified = _probe(temporary)
        audio_streams = [s for s in verified.get('streams', []) if s.get('codec_type') == 'audio']
        effective = (audio_streams[0].get('tags', {}).get('language') if len(audio_streams) == 1 else None)
        effective = effective or verified.get('format', {}).get('tags', {}).get('language')
        if len(audio_streams) != 1 or not effective or _base(effective) != _base(language):
            raise MediaError('audio_verification_failed')
        temporary.replace(path)
    except subprocess.CalledProcessError as error:
        if b'No space left on device' in (error.stderr or b''):
            raise OSError(errno.ENOSPC, 'Audio verification needs free storage') from None
        raise MediaError('audio_verification_failed') from None
    except OSError as error:
        if error.errno == errno.ENOSPC:
            raise
        raise MediaError('audio_verification_failed') from None
    except subprocess.SubprocessError:
        raise MediaError('audio_verification_failed') from None
    finally:
        temporary.unlink(missing_ok=True)
    return track | {'verified': True, 'format_id': str(audio[0].get('format_id') or '')[:128]}

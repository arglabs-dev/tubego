"""User-owned, persisted mobile media preferences; no schema migration required."""
from contextlib import closing
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool
from tubego_server.auth import utcnow

Selection = Literal['480', '720', '1080', 'best', 'audio']


class MediaPreferences(BaseModel):
    model_config = ConfigDict(extra='forbid')
    ask_every_time: StrictBool = True
    selection: Selection = '720'
    rewind_seconds: int = Field(default=10, ge=0, le=120, strict=True)


def read_preferences(database, user_id):
    with closing(database.connect()) as conn:
        row = conn.execute("SELECT value_json FROM settings WHERE scope='user' AND owner_id=? AND key='media_preferences'", (user_id,)).fetchone()
    return MediaPreferences.model_validate_json(row['value_json']) if row else MediaPreferences()


def save_preferences(database, user_id, preferences):
    with database.transaction() as conn:
        conn.execute("""INSERT INTO settings(scope,owner_id,key,value_json,updated_at)
            VALUES ('user',?,'media_preferences',?,?) ON CONFLICT(scope,owner_id,key)
            DO UPDATE SET value_json=excluded.value_json,updated_at=excluded.updated_at""",
            (user_id,preferences.model_dump_json(),utcnow()))
    return preferences


def resolve_selection(preferences, override=None):
    """Call BEFORE enqueue: ask-every-time requires an explicit per-link selection."""
    if override is not None:
        if override not in ('480','720','1080','best','audio'):
            raise ValueError('Invalid media selection')
        return override
    if preferences.ask_every_time:
        raise ValueError('Select quality or audio before submitting this resource')
    return preferences.selection


def resource_fields(selection):
    # Strict validation happens even for trusted internal callers.
    resolve_selection(MediaPreferences(), selection)
    return {'media_format': 'audio' if selection == 'audio' else 'video',
            'quality': 'best' if selection == 'audio' else selection}


def download_options(selection):
    """Controlled yt-dlp options; use ONLY with media.restricted_ytdlp()."""
    resolve_selection(MediaPreferences(), selection)
    if selection == 'audio':
        return {'format':'bestaudio/best', 'postprocessors':[
            {'key':'FFmpegExtractAudio','preferredcodec':'mp3','preferredquality':'192'}]}
    cap = '' if selection == 'best' else f'[height<={selection}]'
    return {'format':f'bestvideo{cap}+bestaudio/best{cap}', 'merge_output_format':'mp4',
            'postprocessors':[{'key':'FFmpegVideoRemuxer','preferedformat':'mp4'}]}


def quality_notice(selection, actual_height):
    """Persist/display this notice after extraction; unknown height is explicit."""
    if selection in ('audio','best'):
        return None
    resolve_selection(MediaPreferences(), selection)
    if actual_height is None:
        return 'quality_unknown'
    if actual_height < int(selection):
        return 'lower_quality_available'
    return None


def verified_output_path(info, media_dir):
    """Use actual yt-dlp postprocessor filepath, never infer a requested extension."""
    from pathlib import Path
    value = info.get('filepath')
    if not isinstance(value,str):
        raise ValueError('Postprocessed output path missing')
    root = Path(media_dir).resolve()
    output = Path(value).resolve()
    if not output.is_relative_to(root) or not output.is_file():
        raise ValueError('Invalid postprocessed output')
    return output

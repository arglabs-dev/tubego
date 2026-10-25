"""Open finalized media beneath the private root without following symlinks."""
import os
from pathlib import Path
import stat
from fastapi import HTTPException


def open_private_media(root: Path, stored_path: str):
    root = root.absolute()
    path = Path(stored_path)
    try:
        relative = path.relative_to(root) if path.is_absolute() else path
        parts = relative.parts
        if not parts or any(part in ('.', '..') for part in parts):
            raise ValueError('Invalid path')
        parent = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for part in parts[:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
                os.close(parent)
                parent = child
            fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        finally:
            os.close(parent)
        stream = os.fdopen(fd, 'rb')
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode):
            stream.close()
            raise ValueError('Not a regular file')
        return stream, metadata
    except (OSError, ValueError):
        raise HTTPException(404, 'Media unavailable') from None


def byte_range(header, size):
    """Single RFC byte range. Multi-ranges are outside the MVP transport."""
    if header is None:
        return 0, size - 1
    try:
        if not header.startswith('bytes=') or ',' in header:
            raise ValueError()
        start, end = header[6:].split('-', 1)
        if not start:
            suffix = int(end)
            if suffix <= 0:
                raise ValueError()
            start, end = max(0, size - suffix), size - 1
        else:
            start = int(start)
            end = min(int(end), size-1) if end else size-1
        if start < 0 or start >= size or end < start:
            raise ValueError()
        return start, end
    except ValueError:
        raise HTTPException(416, 'Requested range unavailable', headers={'Content-Range': f'bytes */{size}'}) from None

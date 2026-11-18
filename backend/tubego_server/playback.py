"""Owner-scoped playback snapshots. Position is latest observation, never maximum.

Coverage is a union of observed continuous intervals. Manual seen/unseen is a
separate last-write-wins register and cannot be undone by an old checkpoint.
"""
from uuid import UUID
from typing import Annotated
from pydantic import BaseModel, ConfigDict, Field, StrictBool, model_validator
from tubego_server.history import value
from tubego_server.scheduler import put_setting

MAX_MS = 30 * 24 * 60 * 60 * 1000
ZERO = UUID(int=0)


class Snapshot(BaseModel):
    model_config = ConfigDict(extra='forbid')
    position_ms: int = Field(default=0, ge=0, le=MAX_MS, strict=True)
    duration_ms: int = Field(default=0, ge=0, le=MAX_MS, strict=True)
    position_at_ms: int = Field(default=0, ge=0, strict=True)
    position_id: UUID = ZERO
    ranges: list[tuple[Annotated[int, Field(strict=True)], Annotated[int, Field(strict=True)]]] = Field(default_factory=list, max_length=4096)
    ended: StrictBool = False
    manual_seen: StrictBool | None = None
    manual_at_ms: int = Field(default=0, ge=0, strict=True)
    manual_id: UUID = ZERO

    @model_validator(mode='after')
    def coherent(self):
        if self.position_ms > self.duration_ms:
            raise ValueError('Position outside duration')
        if (self.duration_ms or self.position_ms or self.ranges or self.ended) and (self.position_at_ms == 0 or self.position_id == ZERO):
            raise ValueError('Playback requires an observation time and ID')
        if self.manual_at_ms > 0 and self.manual_id == ZERO:
            raise ValueError('Manual state requires an observation ID')
        for start, end in self.ranges:
            if type(start) is not int or type(end) is not int or not 0 <= start < end <= self.duration_ms:
                raise ValueError('Invalid coverage interval')
        if (self.manual_seen is None) != (self.manual_at_ms == 0):
            raise ValueError('Manual state requires its own observation time')
        return self


def ranges(intervals, duration):
    result = []
    for start, end in sorted(intervals):
        end = min(end, duration)
        if start >= end:
            continue
        if result and start <= result[-1][1]:
            result[-1][1] = max(result[-1][1], end)
        else:
            result.append([start, end])
    # Conservatively drop smallest intervals if a very fragmented history grows.
    if len(result) > 4096:
        result = sorted(sorted(result, key=lambda r: r[1]-r[0], reverse=True)[:4096])
    return result


def status(state):
    covered = sum(end-start for start, end in state['ranges'])
    seen = state['manual_seen']
    if seen is True or (seen is None and state['ended'] and state['duration_ms'] > 0
                         and covered * 100 >= state['duration_ms'] * 95):
        return 'finished'
    return 'in_progress' if covered > 0 or state['position_ms'] > 0 else 'not_started'


def read(conn, resource_id):
    state = value(conn, resource_id, 'playback_progress', Snapshot().model_dump(mode='json'))
    return state | {'status': status(state)}


def merge(conn, resource_id, incoming):
    state = read(conn, resource_id)
    candidate = incoming.model_dump(mode='json')
    for prefix, fields in [('position', ('position_ms', 'duration_ms', 'position_at_ms', 'position_id')),
                           ('manual', ('manual_seen', 'manual_at_ms', 'manual_id'))]:
        if (candidate[prefix+'_at_ms'], candidate[prefix+'_id']) > (state[prefix+'_at_ms'], state[prefix+'_id']):
            for key in fields:
                state[key] = candidate[key]
    state['ranges'] = ranges(state['ranges'] + candidate['ranges'], state['duration_ms'])
    state['ended'] = state['ended'] or candidate['ended']
    state.pop('status', None)
    put_setting(conn, 'resource', resource_id, 'playback_progress', state)
    return state | {'status': status(state)}

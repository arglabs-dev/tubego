"""Audit raw trial associations; regenerate trials.json without interpreting watched state."""
import json
from pathlib import Path
root = Path(__file__).resolve().parent
records = [json.loads(line) for line in (root / 'evidence.jsonl').read_text().splitlines()]
trials = {}
requests = {}
for event in records:
    if event['event'] == 'launch':
        trial = event['trial']
        detail = event['detail']
        assert trial not in trials, f'Duplicate trial {trial}'
        assert detail['request'] not in requests, 'Reused request code'
        assert detail['requested_ms'] == max(0, detail['saved_ms'] - detail['rewind_s'] * 1000)
        requests[detail['request']] = trial
        trials[trial] = dict(trial=trial, launch_utc=event['utc'], **detail, results=[], caller_events=[])
    elif event['event'] == 'activity_result':
        trial = event['trial']
        assert trial in trials, f'Orphan callback {trial}'
        detail = event['detail']
        assert requests[detail['request']] == trial, 'Callback crossed trial boundaries'
        if detail['accepted_checkpoint']:
            assert detail['result'] == -1 and detail['action'] == 'org.videolan.vlc.player.result'
            assert detail['extra_position_type'] == detail['extra_duration_type'] == 'java.lang.Long'
            assert 0 <= detail['extra_position'] <= detail['extra_duration'] and detail['extra_duration'] > 0
        trials[trial]['results'].append(dict(utc=event['utc'], **detail))
    elif event['event'] in ('caller_die', 'caller_recreate', 'abandoned') and event['trial'] in trials:
        trials[event['trial']]['caller_events'].append(event)
(root / 'trials.json').write_text(json.dumps(list(trials.values()), indent=2, ensure_ascii=False) + '\n')
print(f'{len(trials)} trials; {sum(len(t["results"]) for t in trials.values())} associated callbacks; audit passed')

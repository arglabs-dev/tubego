# PLA-235 — Media preferences

Approved users GET/PUT `/api/v1/account/preferences`. Only the authenticated user's
preferences can be read or written; an owner ID is never accepted. They are stored
in the existing `settings` table (`scope=user`, `key=media_preferences`) and survive
restarts. PUT replaces the full preference document:

```json
{"ask_every_time":true,"selection":"720","rewind_seconds":10}
```

Selection is exactly `480`, `720`, `1080`, `best`, or `audio`. Initially the app must
ask for each link. When asking is disabled, the saved selection supplies the default;
a per-link choice overrides it without changing preferences. Rewind is 10 seconds
by default, accepts integers 0–120, and is not a promise that external players return
playback position; that capability remains the VLC spike. Preference changes save locally offline, then synchronize through the same
ordered durable command queue as other account actions using any available network.
Server snapshots cannot overwrite a newer local preference or pending change.
Quality selection itself works offline.

`resolve_selection()` must be called before creating a backend job. If asking is on
and no explicit selection is supplied, it rejects submission. Persist
`resource_fields(selection)` with the resource so later changes cannot affect an
already queued request. PLA-233 integrates URL entry and the offline quality picker;
its queue payload must retain the explicit selection.

`download_options(selection)` returns controlled native quality selectors: capped
video heights never upscale, `best` chooses the best available (no 4K promise), and
`audio` extracts 192 kbps MP3 using FFmpeg on downloaded local input. Video prefers
MP4 merging/remuxing when codecs permit it. Use only with `restricted_ytdlp` to
prevent external network downloaders. Unknown video height is not silently advertised
as the requested quality. `quality_notice()` supplies `lower_quality_available` or
`quality_unknown`; the job/result UI must expose it. If no format satisfies a cap,
the job must show an actionable format error rather than download a higher resolution.
FFmpeg is included in the existing backend image.

After conversion, use `verified_output_path(info, media_dir)` to validate the actual
postprocessed `filepath` exists beneath the user's controlled storage root; never
construct `.mp3` or `.mp4` paths from assumptions. Filesystem/conversion failures
must enter the queue's error flow. The tests perform real local FFmpeg WAV→MP3
postprocessing and verify its output, besides API access/isolation and selectors.

Android `MediaPreferencesActivity` can be launched by the authenticated app with
`server_url` and reads the existing session-token store. It edits ask/default/rewind
preferences. `QualityPicker.show(activity, selection, callback)` is reusable without
network by the add/share flow; `MediaSelection.resolve()` mirrors enqueue validation.
The preferences screen is reachable from MainActivity and the add/share screen.
Its values are scoped to the current account/device and synchronize with backend
preferences. The VLC position spike remains separate from these settings.

## Mobile quality notices and source errors (acceptance review)

The library renders `latest_task.quality_notice` in the resource card, including
cached offline history. `lower_quality_available` says the source provided a lower
quality; it does not invent the actual pixel height. `quality_unknown` explicitly
states the displayed quality is a requested preference, not a verified resolution.
The requested variant remains unchanged in history. No completion notification is
created for a quality warning.

Stable backend failure codes map to ES/EN explanations for unsupported sources,
private/authenticated content, removed resources, source restrictions, unavailable
formats, transient failures and conversion errors. Metadata consultation shows that
reason and still permits saving the link for later validation; missing optional
metadata never blocks a valid submission. Raw extractor errors, paths and tokens
are never appended to those messages. Unknown future codes use a safe generic
message. API ownership is unchanged.

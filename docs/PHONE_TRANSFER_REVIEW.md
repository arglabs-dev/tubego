# Maintenance and phone transfer review

## Production investigation — November 10, 2026

The existing local Docker Compose deployment is the live server at
`https://tubego.arglabs.dev`. On inspection, both API and worker had yt-dlp
`2026.8.19`; the supervisor approved only that same version and staged backend
`38b2b4a`. Upstream checks correctly reported `2026.10.26`, but did not approve
or install it.

Times below use America/Mexico_City (UTC−06:00), with seconds retained.

| Received | Request | Outcome |
| --- | --- | --- |
| Nov 8, 22:53:04 | `fb1b7820-74d0-46fb-b4b8-799a0d8482ff`, update yt-dlp to 2026.8.19 | Failed at 22:53:05: `revision_not_staged_for_installed_backend`. The running backend and supervisor's staged revision differed. |
| Nov 8, 22:53:39 | `ce2a0c61-547e-4a3f-a8f3-2ec7ddb17ab5`, update backend | Succeeded at 22:53:56, installing staged revision `38b2b4a`; this was older than previously running `78a297b`. yt-dlp stayed at 2026.8.19. |
| Nov 8, 22:58:01 | `86792eb2-de9c-471e-815a-ff6d52e3395c`, update yt-dlp to 2026.8.19 | Succeeded at 22:58:27, rebuilding the same version. Success meant deployment verification, not a newer version. |
| Nov 10, 08:39:47 | `006e4418-9818-4996-ac91-4f72f1a5245e`, check versions | Succeeded; query only, no installation. |
| Nov 10, 08:47:29 | `4fb844f3-f021-4a19-b11d-e3b1fdb7248b`, check versions | Succeeded; query only, no installation. |

At the user's request, the operator configuration now approves **2026.10.26**.
Operator request `a6966964-235a-4d91-b3aa-a58f700f112a` successfully rebuilt and
verified the deployment at the same backend revision. API and worker independently
report **2026.10.26**; API/database health is OK and the maintenance gate is clear.
The existing Deno 2.9.5 exceeds this yt-dlp release's runtime minimum of 2.3.0;
EJS remains 0.8.0. Configuration persists in the staging deployment `.env`.
The operator job was recorded without impersonating an administrator session.

[Sanitized server evidence](evidence/maintenance-update-2026-11-10.json) includes
complete IDs, UTC times, requested targets, error codes and observed versions.

## Android feedback

APK 0.5.10 records creation, first/last send attempt, attempt count and response
receipt in an account/device-scoped journal. Retries retain one UUID. A request
without an acknowledgement is distinguished from a request never attempted.
HTTP 429 responses appear as a deferred retry. The latest local request appears
above the buttons, which disable while an acknowledgement is pending.

History uses creation time, newest first. Previously sorting by `updated_at`
moved older operations when they completed. Read queries and deployment changes
run on separate processors, so submission order does not guarantee finish order.
New backend code records phase transitions and start/finish times; that code is
pending deployment with the branch. The new APK's local send/response timestamps
also work against the current server. Older phone attempts have no recoverable
client-side timestamps: only their recorded server acceptance/completion is known.

## Phone transfer scope

The branch also replaces process-local delayed retry callbacks with persistent
JobScheduler backoff and distinguishes queued transfers from active runtime work.
This addresses a code-level recovery weakness. The production update alone does
not prove delivery of the previously stuck videos to the affected phone.

Validation of the final branch: 12 backend maintenance tests, 75 Android unit
tests and three Android 15 instrumentation tests passed, as did lint and ES/EN
catalog verification. The journal test simulates a lost acknowledgement and
verifies stable request identity, distinct attempts, wire-field filtering and
cached terminal status. These are not end-to-end production video-download tests.


## Follow-up: stalled OnePlus transfers — November 10

The four server-available deliveries for the recently active OnePlus are:

| Resource | Bytes | Server ready (UTC) |
| --- | ---: | --- |
| TRUCK OWNERS NEED A 3D PRINTER (`476cbbea`) | 1,992,574 | Nov 9, 04:44:10 |
| Pionero de IA (`9ce68b21`) | 140,295,050 | Nov 8, 17:27:08 |
| Binance (`e95635cc`) | 14,570,888 | Nov 7, 20:41:37 |
| 30 Years of Business Knowledge (`ac1016e1`) | 284,959,149 | Nov 9, 08:06:46 |

All four files exist at their expected sizes; all four deliveries remain pending
without a confirmation. `downloaded_bytes=0` alone is not evidence of zero bytes
received: this implementation updates it on final confirmation, not every chunk.
The current API container's logs show library polling approximately every four
seconds on Nov 10 at 15:20–15:22 UTC, but no `/device/sync`, media download, or
confirmation requests during that interval. This points to absent transfer startup,
not measured low transfer throughput. Logs from replaced containers are unavailable;
no claim is made about every attempt over the preceding four days.

Code review found that an open library still started delivery exclusively through
JobScheduler, and startup exceptions were discarded. APK 0.5.11 now also runs the
same permission-checked TransferDriver directly while the library is visible,
coalesces wakeups, stops its own work on pause, and leaves background delivery to
JobScheduler. The existing transfer locks prevent concurrent file writers. A
local, account-scoped diagnostic records the last completed attempt's phase,
outcome and sanitized error code. It is shown under account Diagnostics.

This is a targeted startup-path fix. The precise local blocker on the OnePlus
(job quota, startup I/O, or another local failure) cannot be established from
server logs, and delivery to that physical phone remains unverified.

The final APK 0.5.11 passed 75 Android unit tests, five targeted emulator tests,
lint and ES/EN verification. The two new emulator tests verify immediate startup
without scheduling a job, coalesced wakeups, lifecycle cancellation, and safe
recording of a simulated startup failure. They do not prove physical-phone delivery.


## Physical OnePlus investigation — November 15

USB debugging confirmed the phone still runs APK **0.5.10 (16)**. The foreground
transfer correction from 0.5.11 was not installed on this device.

The phone and server both showed a resumed transfer of `a34b393e` (56,426,684
bytes). At **16:43:03 Mexico City time**, JobScheduler stopped both TubeGo transfer
jobs with the explicit reason **thermal**. The partial stopped at **32,258,845
bytes**. Android reported thermal status 3, six job failures and a 16-minute
exponential-backoff delay. At inspection the jobs were waiting, not actively
transferring. Library polling every four seconds does not override that deadline:
`wakeAt()` preserves an existing pending job. The UI showed queued work without
the thermal cause or retry deadline; the durable transfer manifest still said
`downloading` after cancellation.

The phone has approximately 13 GB free and an explicitly configured 3% reserve;
this attempt was not stopped by the app's storage threshold. The observed Wi-Fi
was validated and unmetered. Neither this nor the advertised Wi-Fi link rate
proves maximum achievable throughput. A short sample of server file position and
TCP buffering showed downstream backpressure, not a measured Wi-Fi speed test.

Diagnostics used read-only manifests, process/job state and a short debug stack
capture. The temporary debugger connection was resumed and removed. TubeGo was
returned to the library after enabling USB debugging. No APK replacement,
thermal override, battery-policy change or job-force command was performed.
The explicit stop reason confirms today's interruption; it does not prove every
failure over prior days had the same cause.

[Sanitized physical-device evidence](evidence/phone-transfer-2026-11-15.json).


## Other-app check — November 15

Read-only CPU sampling found transient peaks in other applications and an OEM
service. Follow-up sampling was substantially quieter and earlier peaks
disappeared. The installed-app inventory is omitted from public evidence.
Percentages from Android top are relative to one CPU core (eight cores total),
not percentages of the whole device. No app was stopped, disabled or blamed as a
proven source of the earlier heating.

More significantly, live HAL readings reported CPU ~36.9 C, battery ~29.5 C and
skin ~32.7 C, all status 0, while thermalservice retained status 3 and cached
CPU 77.929 C / skin 51.22 C — the same cached readings seen in the earlier
inspection. This is consistent with a stale thermal-state notification, but the
precise system/vendor fault is unproven. A normal user-approved phone restart
followed by checking live/cached thermal state would discriminate this hypothesis
without disabling thermal protection.

[Sanitized measurements](evidence/phone-other-apps-2026-11-15.json).


## Verified phone resolution — November 15, 19:11 Mexico City

A normal user-authorized reboot reset thermalservice from status 3 to status 0.
Initial boot activity still caused high live sensor readings; the recorded global
thermal state stayed 0 during this test. The previous restriction was not overridden.
With 0.5.10 the resumed partial advanced at about 48,446 bytes/second over a
15.2-second sample. A short debug stack captured the transfer thread inside
`SessionStore.token()` / Android Keystore, called by `sameSession()` for each
received buffer. The source also fsynced every network read.

APK **0.5.12 (18)** uses the existing durable session generation to fence every
write against logout/account/token replacement, with the session and generation
captured under the same lock. It avoids repeated hardware-backed decryption.
Partial data is flushed every MiB and in `finally` on pause, network failure or
completion. Final length/SHA verification remains mandatory. The epoch regression
checks logout, same-token account replacement, token replacement and cancellation.

The APK was installed in place on the physical OnePlus, preserving its session,
partials and downloaded files. Both currently available pending videos then
completed and were published in `Download/TubeGo`:

- `a34b393e`: 56,426,684 bytes, resumed and confirmed at **19:11:34**.
- `95f4e639`: 15,878,417 bytes, full download began **19:11:36** and was confirmed
  **19:11:46**, about 10.4 seconds including validation/confirmation.

Device-side SHA-256 of both published files matched their server digests. The
server independently records both deliveries as complete and the physical UI
shows downloaded media as ready. The before/after conditions and files differ;
these observations are not a controlled throughput benchmark or proof that all
future thermal/background interruptions are eliminated.

Final validation: 75 Android unit tests, six targeted emulator tests, lint and
localization checks passed. Emulator tests were explicitly run only on the
emulator, not against the user's phone/account. Temporary debugging forwards were
removed. [Sanitized end-to-end evidence](evidence/phone-transfer-resolved-2026-11-15.json).


## Independent follow-up download — November 15, 19:18–19:19

The subsequently submitted video `cbc15718`, “Así está usando la gente Fable 5.2
(41 casos reales)”, was 110,158,270 bytes. Server preparation completed at
19:16:50; phone synchronization occurred at 19:17:59, and the phone download
started at 19:18:01 with HTTP 200. Its verified delivery was confirmed at
19:19:11, about 70 seconds after the transfer request. The phone manifest has
state `complete`, a published MediaStore URI, and zero retry failures. Android
reported thermal status 0 during the inspection. This was a new user-submitted
video, not the earlier resumed transfer. [Sanitized evidence](evidence/phone-followup-video-2026-11-15.json).

# Persistent Android transfers (PLA-240)

The app registers ordinary `JobScheduler` jobs after approved login and when its
home screen opens. A persisted 15-minute periodic job retrieves authoritative
server delivery snapshots (both delivery and event pagination). A one-off job
starts requested work; a separate pending Wi-Fi-constrained job resumes media
when Wi-Fi returns. Explicit file-specific mobile consent schedules an any-network
job. Server control/sync operations can use any connection. Local media transfer
still obeys the stricter policy for each file.

`TransferDriver` validates account state before reading the queue and consumes
wipe/deletion tombstones before considering downloads. It orders resources by
creation time then ID, persists queue metadata under `LocalLibraryStorage.root`,
and never stores files in cache. `TransferRecord` has `<resource>.properties`,
`<resource>.part`, and an eventual `<resource>.media` file. Properties contain
size, SHA-256, state, title, creation time, offset and a user-readable message;
no bearer token is stored. Manifest replacement is atomic. The actual partial
file length is authoritative on resume. Each written chunk is synced to disk.

Every media connection uses `Network.openConnection` on one specific validated,
permitted network with standard HTTPS certificate/hostname verification. The
worker checks that this network remains the default and remains allowed before
and after every blocking read. A default-network callback disconnects the socket
when Wi-Fi disappears, the default changes, or authorization is removed. It never
opens an unbound media socket or silently falls back to cellular.

Resumption sends Range and If-Range with the file's checksum ETag. A 206 response
must have the expected start/total/end, ETag, checksum and content length. A 200
response after a resume request discards the old partial and records why work
restarted. New server size/checksum also discards incompatible partials. Only an
exact-size file matching the complete SHA-256 is atomically renamed to `.media`;
the authenticated idempotent delivery-confirmation endpoint is then called.
Confirmation can be retried independently without downloading that file again.
A complete server delivery missing locally can request `restore_missing:true`
only if no local deliberate-deletion tombstone exists. It then follows the same
Wi-Fi/explicit-consent policy; this is not a hidden action attached to Play.

Shared storage mutations use `SessionStore.class` and recheck active identity.
Network I/O and long checksum reads do not hold that lock. Logout/revocation
stops the active connection and cancels jobs before wiping files under that lock.
A worker cannot recreate manifests/partials after its session is cleared.

## Android limits

These are ordinary persisted jobs, not an unlimited foreground service. The
system may defer them for battery, Doze, standby, quota or resource constraints.
On busy systems it may stop a long-running job at approximately ten minutes;
`onStopJob` closes the connection and requests rescheduling, preserving synced
bytes. Reconnecting Wi-Fi makes pending jobs eligible, not an immediate execution
guarantee. Background detection of newly ready media uses the periodic snapshot
and may be delayed beyond 15 minutes. There is no push provider in this card.
A force-stopped app cannot execute until it is opened again; opening re-registers
jobs and recovers the manifest/partial. Persisted jobs survive a normal reboot.
An ongoing low-priority working notification is submitted while a job runs and
removed afterward; no completion notification is posted. Display is subject to
Android notification permission/settings; notification UX is finalized in PLA-253.

Official references: [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler)
and [JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder).

## Validation

Pure-Java tests cover persisted offsets, restart/resume, range/size/identity
rejection, restart on a full response, checksum failure, duplicate completion,
changed metadata and deletion during a blocking read. A real API-35 emulator test
logs into a loopback HTTPS backend, pauses a 16-MiB transfer after partial bytes,
resumes via the native job with the screen asleep, verifies the full checksum and
backend completion, then validates revocation cleanup.

The certificate trusted by that test is only in `androidTest` resources. The
instrumentation APK temporarily configures its process's default SSL factory
and restores it afterward; production trusts and hostname verification are not
changed. Reproduce with a local HTTPS fixture at `10.0.2.2:9443`, a certificate
whose SAN includes that IP, the test account/resource specified in the test, and
refresh `android/app/src/androidTest/res/raw/fixture_cert.pem` from that fixture
before building/running it. This test requires that external fixture; it is not
part of device-independent unit tests.

A portable local fixture is provided (requires Python backend dependencies and
OpenSSL). Start it from the repository root in a separate terminal:

```bash
PYTHONPATH=backend:. python backend/tests/emulator_fixture.py
```

Then copy its **public** certificate, rebuild the app and instrumentation APK,
install both onto the API-35 emulator, and run only the fixture-dependent class:

```bash
cp /tmp/tubego-emulator-fixture/cert.pem android/app/src/androidTest/res/raw/fixture_cert.pem
adb -s emulator-5554 shell am instrument -w -e class dev.arglabs.tubego.TransferInstrumentedTest dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

The copy must occur before `assembleDebugAndroidTest` and installation. If the
fixture certificate expires, generate a fresh disposable fixture directory with
`--directory`, copy its new certificate, rebuild and reinstall. Certificates last
two days; private keys remain outside the repository. Do not expose this fixture
server or its fixed test credentials publicly. Device-independent CI should run
`testDebugUnitTest`; this particular connected test requires the explicit fixture.

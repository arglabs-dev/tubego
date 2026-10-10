# Real public-source APK end-to-end verification

This is an opt-in instrumented test, not an ordinary offline CI test. It needs an API35 emulator with validated WiFi, Internet access to the fixed public Blender Sintel trailer, ffmpeg/ffprobe, and the backend requirements. It does not use a fake extractor, resolver, Worker, command transport or transfer HTTP connection.

From this checkout, prepare a **new disposable directory** (never a production data directory):

```
PYTHONPATH=backend:. /home/areyes/.local/share/tubego-toolchain/python-env/bin/python scripts/mobile_source_fixture.py --directory /tmp/tubego-mobile-source-fixture --port 9443
```

The fixture listens only on loopback, creates an approved test-only account and runs a real restricted public-egress proxy plus Worker thread. It accepts only the fixed Blender URL for URL submissions; internal metadata/download redirects still pass normal production SSRF restrictions. The fixture certificate has IP SANs 10.0.2.2 and 127.0.0.1 and expires after two days. Copy its public `cert.pem` to `android/app/src/androidTest/res/raw/fixture_cert.pem` and rebuild the test APK. Only instrumentation installs its test trust factory; production trust and normal hostname verification remain unchanged. A pre-existing fixture certificate may be copied to the new directory along with its private key solely to reuse an already-built test APK; never distribute that key.

Build/install app and instrumentation APK, then execute:

```
adb -s emulator-5554 shell am instrument -w -e class dev.arglabs.tubego.MobileSourceInstrumentedTest dev.arglabs.tubego.test/android.test.InstrumentationTestRunner
```

The test authenticates against the actual API using the already-approved fixture account and installs that response with the production SessionStore. It does not test LoginActivity or MainActivity onboarding. It launches LinkEntryActivity, puts the public URL into its real EditText, presses its Add-to-queue button, and uses accessibility clicks for the quality and submission confirmation dialogs. The production UI persists the command; an actual JobScheduler command job flushes it. Instrumentation forces those real jobs with `cmd jobscheduler run -f`; this proves their execution, not spontaneous OS scheduling or a latency guarantee. The test never posts `/resources` itself. The fixture Worker analyzes/downloads/transcodes/publishes the real public video, then the production network-bound WiFi transfer job downloads and confirms it. Assertions require durable submitted outbox state, completed backend task, completed device delivery, a full-size local media file with SHA256 matching the manifest, and absence of a partial file. Log tag `TubegoMobileSourceE2E` records resource ID/size/SHA for a successful run.

Fixture login bypasses registration/approval **only in disposable seed data**; this test makes no SMTP claim. SMTP verification/password reset need separate deployment tests. The source may become unavailable or restrict this host: that is an explicit test failure, not a reason to silently replace the extractor. This test does not cover all yt-dlp portals, physical device power management, Google login, iPhone or exact VLC position callbacks. It retains the final local fixture file for operator inspection; discard the fixture account/app data when done.

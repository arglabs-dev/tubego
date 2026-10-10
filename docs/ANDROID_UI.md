# Android visual redesign (PLA-261)

Android 0.2.0 adds a shared native visual system: warm paper surfaces, purple navigation, lime accents, rounded cards, vector icons and clearer typography. Home surfaces link entry and the private library; settings groups connection, account, downloads and help. Library cards show availability and quality with primary playback/request actions and secondary actions in an accessible menu. Full resource metadata remains available through Details.

No media thumbnails are fetched: the decorative covers are generated locally. No sample content is shipped. Authentication, session fences, permissions, transfer policy and existing command handlers remain authoritative. Both Spanish and English are supported. System insets and keyboard resizing are handled on API 29 and newer; page bodies scroll to support smaller displays and enlarged text.

## Validation

- Offline Gradle assembleDebug / assembleDebugAndroidTest / lintDebug / testDebugUnitTest successful.
- 568 Spanish/English strings verified; git diff --check clean.
- Existing Android regression suite plus visual tests: 20 tests passed on the emulator.
- Three visual tests passed at 1080x2340, density 420, font scale 1.3, covering navigation, hidden administrator controls, offline cards, Details menu and link submission control.
- Existing public-source test passed: UI link entry and quality confirmation, backend yt-dlp/FFmpeg processing, native download, verified local file and completion acknowledgement (10.728 seconds). Source: Blender's public Sintel trailer. The final subsequent change only widens the library refresh button for enlarged text.

Screenshots in evidence/ui are actual emulator captures with isolated disposable test accounts and metadata fixtures. They demonstrate rendering, not production content or production connectivity. No embedded player, new backend feature or production deployment is included.

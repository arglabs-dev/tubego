# Android network policy (PLA-239)

`DownloadPolicy` contains platform-independent rules used for both video and
audio. Control requests (links, commands, sync, metadata, deletion) may use any
validated network with Internet capability. Media waits for validated Wi-Fi by
default, including metered Wi-Fi hotspots. Unmetered cellular is still cellular.
Offline or unvalidated/captive networks never permit media transfer. Ambiguous
Wi-Fi+cellular capability cannot permit media without an explicit exception.

`NetworkMonitor` observes the default network via `ConnectivityManager` callbacks
and exposes its current capability snapshot. Android has ACCESS_NETWORK_STATE
permission. It does not infer Wi-Fi from NOT_METERED. A worker must react to every
capability/loss callback and also check policy before each read/write chunk. If
Wi-Fi disappears, pause without consuming failure retries unless that exact
transfer has mobile consent. A paused granted transfer remains granted on resume.

`TransferKey` scopes each consent to normalized server origin, authenticated
`user_id`, `device_id`, `resource_id`, and server SHA-256 (the particular file
version). Origin must come from `ApiClient.getBaseUrl`, identity from encrypted
`SessionStore`, and checksum from authenticated device synchronization. Consent
cannot carry over to another rendition of a resource, phone, user, or server.

`AndroidDownloadPermissions.create(context)` returns a durable, app-private
`DownloadPermissions` ledger. Only the explicit consent dialog calls `authorize`.
Retries/restarts/pauses use `authorized` without consuming consent. On completion,
cancellation or revocation, call `revoke(key)`; account/device revocation calls
`revokeAccount`. `SessionStore.clear()` removes grants for that server before
clearing its encrypted session. There is no global allow-mobile-data toggle.
The ledger contains hashed identities/flags, never bearer tokens or passwords.

`NetworkPolicyActivity` is accessible from the home screen and loads paginated
pending server deliveries. The consent dialog identifies the resource, shows
known size (or explicitly unknown size) and explains that permission affects only
that file on this phone until completion/cancellation. Completed deliveries clear
the corresponding consent; tombstones requiring approval are not offered as
automatic mobile downloads. A new session identity invalidates old UI actions.

The service in PLA-240 must consume these helpers. It must bind the actual
connection with `Network.openConnection(...)` on the specific permitted network
rather than allowing silent system fallback to cellular. If that network or its
policy becomes unsuitable, close the connection and preserve the partial file.
Library reproduction is local-only (PLA-241); it never calls the transfer worker
as a hidden response to Play. This card adds consent/policy, not a downloader,
embedded player, push scheduler or physical-device transfer validation.

# Device storage protection (PLA-250)

Each server/account/device incarnation has a minimum-free-percent preference in
app-private SharedPreferences. Default 10%, accepted integers 1–90. Configuration
never changes another device, a backend quota, or its Wi-Fi/data permission.
“Descargas y permisos de red → Almacenamiento de este teléfono” shows free bytes,
managed Tubego storage and this account's managed storage. It offers the existing
account/logout flow for explicit cleanup with its deletion confirmation. Selective
library cleanup can link to this screen once its separate card is integrated.

The adapter samples `StatFs.getAvailableBytes()` on the physical app-private volume.
Below the threshold, unknown volume state, or insufficient remaining bytes above
that reserve pauses a transfer. A known remaining file must fit without spending
the configured reserve. No files are removed automatically. Threshold equality
alone is permitted, but a nonempty remainder must still fit.

A storage guard runs before opening the media socket and immediately before every
write, including after a blocking network read. The app does not hold a network
read under the shared session/file lock. ENOSPC unwinds as a storage pause; progress
and the partial survive, while automatic retry counters and data grants remain
unchanged. Other eligible smaller files can advance. Existing control sync,
confirmations and offline command submission remain independent of this guard.

The ordinary JobScheduler polls space-paused work with a 60-second minimum wait;
Android can defer background jobs. Its periodic persistent job also recovers work
across process/phone restarts. Once both reserve and remaining-size requirements
fit, transfer resumes from the on-disk partial and applies the same network rules.

`TransferRecord.pauseReason` is `device_storage` for these pauses. Durable notice
markers for PLA-253 are in `tubego_device_storage`, scoped by
`TransferKey.accountPrefix(origin,user,device)`: `low_space_active` (boolean),
`low_space_reason`, `low_space_sequence` (increments once per incident),
`available_bytes` and `total_bytes`. A healthy completed queue clears active status.
The storage screen and per-file state display the warning immediately; notification
delivery is handled by the dedicated notifications card. No download-completed
notice is introduced here.

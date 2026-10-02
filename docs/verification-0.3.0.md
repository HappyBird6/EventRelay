# Source publication verification: 0.3.0

The clean public source was tested before its initial commit:

- `:core:coreChecks`: 37 checks passed.
- Gmail-enabled `:app:testDebugUnitTest`: 90 tests, 0 failures/errors, 2 conditional skips.
- `:app:lintDebug`: 0 errors, 18 warnings (dependency/KTX/shared-preference suggestions).
- `:app:assembleDebug`: unsigned build succeeded; package `dev.eventrelay.app`, version 0.3.0, versionCode 4.
- All app/core implementation and test files are byte-identical to the locally validated 0.3.0 source. The build configuration no longer reads private signing files; debug signing is disabled and performed separately.
- All publication files were checked for credential patterns, private artifacts, machine paths and actual email addresses. Test and documentation addresses use reserved example domains. No APK, keystore, token, local properties, device database or runtime account configuration is included.
- Schema JSON syntax checked. A formal JSON Schema validator was not run.

Previous local verification also covered the Gmail-disabled authorization/queue/synthetic safety tests (15 passed) and five synthetic UI previews. Local signed APKs passed v2/v3 signature, ZIP integrity and 16KB alignment verification. These binaries and signing certificates are not part of this source publication.

Physical-device screen-off, reboot and offline stability checks are deferred. Tests use fake transport/data and do not send email or operate on a real account. No CI, automated deployment, release or package publication is configured. No public license has been selected.

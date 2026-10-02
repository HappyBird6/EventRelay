# EventRelay 0.3.0

Android SMS and notification relay to your own Gmail account. Rules run locally; matching events are stored in Room and sent through Gmail using only `gmail.send`. The app does not run AI or infer amounts or transaction details. Package: `dev.eventrelay.app`, versionCode: `4`, minimum Android: 8.0 / API 26, target API 35.

## Features and safety

- Searchable launcher-app picker for notification rules, readable status/history, and diagnostics that exclude message contents, addresses, tokens and raw errors.
- Full original event remains local; outgoing UTF-8 JSON uses [message schema v2](docs/message-v2.md). Subjects retain `[EventRelay] SMS` or `[EventRelay] NOTIFICATION`.
- Gmail delivery starts OFF. Connecting an account never enables sending. Explicit delivery consent applies to future matching events.
- Disabling a rule holds unfinished matching entries. Changing/disconnecting an account holds the old queue; old entries never transfer to a new account. Reconnecting or enabling a rule does not resume old entries.
- Deleting unfinished entries requires count confirmation and an unchanged set of entry IDs. Terminal history remains.
- Upgrading Room v1 to v2 preserves rules, original data and terminal/unknown history; old pending/retry/sending entries are held and delivery starts OFF. See [queue policy](docs/queue-policy.md).
- App selection is limited to launcher-visible applications and excludes EventRelay and Gmail. Synthetic exercises never send or schedule delivery.

## Build and test

Install JDK 21 and Android SDK Platform 35 / Build Tools 35.0.0 with official tools; accept their licenses yourself. Set `JAVA_HOME` and `ANDROID_HOME` for your installation. Gradle 8.13 is provided by the checksum-pinned wrapper. No project-specific absolute paths, keys or account settings are included.

Windows PowerShell:

```powershell
.\gradlew.bat :core:coreChecks -PeventrelayGmailEnabled=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux/macOS:

```sh
chmod +x gradlew
./gradlew :core:coreChecks -PeventrelayGmailEnabled=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug-unsigned.apk`. Debug signing is deliberately disabled; sign a copy with your own long-term key. Omitting `-PeventrelayGmailEnabled=true` produces the default Gmail-disabled build. The flag enables authorization UI, not delivery consent.

## Signing and Google setup

See [setup instructions](docs/build-signing-gmail.md). Keep your existing signing key when updating an installed app. Never commit a keystore, password, token, APK, device data or local configuration. This repository includes the Gradle wrapper JAR as build tooling, but no app binary or signing key.

## Verification and limits

See [verification](docs/verification-0.3.0.md). Automated tests use fake data and transport; they do not send email. Physical-device screen-off, reboot and offline stability checks remain to be performed. API acceptance does not prove inbox delivery or downstream processing. A request already submitted cannot be withdrawn; ambiguous outcomes are not automatically retried.

No public license has been selected. No automated deployment, release or CI workflow is included.

# Build, signing and Gmail configuration

## Long-term signing

Create or reuse your own long-term RSA signing key outside the checkout. If an app is already installed, reuse its signer and increase versionCode for updates. Back up your key securely. Do not put private keys or passwords in source, command arguments, logs or password files.

For a new installation only, an example interactive key-generation command is:

```sh
keytool -genkeypair -keystore eventrelay-release.jks -storetype JKS -alias eventrelay -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=EventRelay"
```

Run this in your private key directory, ensuring that the target does not exist. Enter and confirm the password at the tool prompt; do not add password arguments. Do not generate a replacement key for an existing installation.

Build the Gmail-enabled unsigned APK as described in the README. Use the Android Build Tools `zipalign` and `apksigner` installed on your machine. Work in a private output directory; choose unused output filenames and supply the paths to your unsigned APK and private keystore locally:

```sh
zipalign -P 16 4 app-debug-unsigned.apk eventrelay-aligned.apk
apksigner sign --ks eventrelay-release.jks --ks-type JKS --ks-key-alias eventrelay --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out eventrelay-signed.apk eventrelay-aligned.apk
apksigner verify --verbose --print-certs eventrelay-signed.apk
zipalign -c -P 16 4 eventrelay-signed.apk
```

Enter the password interactively. Publish neither the key nor its password. The certificate digests are public; check them before installing an update. APK distribution is separate from this source repository.

## Google authorization

1. In your Google Cloud project, enable Gmail API and configure Google Auth Platform consent. Configure your own support/privacy details and testing users as needed.
2. Request only `https://www.googleapis.com/auth/gmail.send`. Do not add read, modify or full-mail scopes for this app.
3. Create an Android OAuth client for `dev.eventrelay.app` and the SHA-1 of the certificate that actually signs your APK. Use `apksigner verify --print-certs` to obtain it. Register a different certificate if you use a different signing key.
4. Build with `-PeventrelayGmailEnabled=true`. No backend, client secret, service-account key or API key is embedded in this Android authorization flow.
5. On your device, grant SMS and notification access only if you want those sources. Configure matching rules, connect your own Google account, then explicitly enable future Gmail delivery. Connection alone does not send.

Labels and Gmail filters are optional account-side configuration, separate from app authorization. Example filter: subject contains `[EventRelay]`. The app does not manage labels and does not require `gmail.modify`.

On an update from the old database, delivery starts OFF and old unfinished entries are held. Confirm permissions/rules, reconnect if necessary and enable delivery explicitly; old held entries do not resume. Disconnect removes the app's local connection/token state, not your Google account or the remote OAuth grant. Manage remote grant revocation in Google account settings when needed.

References: [Android authorization](https://developer.android.com/identity/authorization), [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [OAuth credentials](https://developers.google.com/workspace/guides/create-credentials).

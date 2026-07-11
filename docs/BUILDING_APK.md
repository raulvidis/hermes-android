# Building the Bridge APK

## Requirements

- JDK 17+
- Android SDK platform 34 + build-tools 34
- Or Docker image with Android SDK (e.g. community images that ship `sdkmanager` + Gradle)

## Debug build (default Android debug keystore)

```bash
cd hermes-android-bridge
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
# app/build/outputs/apk/debug/hermes-android-<versionName>.apk
```

## Reproducible distribution signing (optional)

Never commit keystores or passwords. Use environment variables:

```bash
export HERMES_ANDROID_KEYSTORE=/secure/path/release.keystore
export HERMES_ANDROID_KEYSTORE_PASSWORD='***'
export HERMES_ANDROID_KEY_ALIAS=hermes-android
export HERMES_ANDROID_KEY_PASSWORD='***'   # optional; defaults to keystore password

./gradlew assembleDebug
# or assembleRelease when configured
```

### Overlay installs

Android allows “update over existing app” only when:

1. `applicationId` matches, and
2. the **signing certificate** matches.

If you switch keystores, users must uninstall first (data/settings may reset).

## Docker sketch

```bash
docker run --rm \
  -v "$PWD/hermes-android-bridge":/project \
  -v "$HOME/.gradle-docker":/root/.gradle \
  -e HERMES_ANDROID_KEYSTORE=/signing/release.keystore \
  -e HERMES_ANDROID_KEYSTORE_PASSWORD \
  -e HERMES_ANDROID_KEY_ALIAS \
  -e HERMES_ANDROID_KEY_PASSWORD \
  -v /secure/path:/signing:ro \
  -w /project YOUR_ANDROID_SDK_IMAGE \
  bash -lc 'echo "sdk.dir=$ANDROID_HOME" > local.properties && ./gradlew assembleDebug --no-daemon'
```

## After install on device

1. Grant **restricted settings** for Accessibility on Android 13+ sideloads.
2. Enable Accessibility service for Hermes Bridge.
3. Grant Camera / Microphone if using capture tools.
4. Set Server to your `ANDROID_PUBLIC_URL`.
5. Pair with the 6-character code via Hermes `android_setup`.

## Version fields

- `versionName` / `versionCode` live in `app/build.gradle.kts`.
- Bump `versionCode` on every public artifact users will install over a previous build.

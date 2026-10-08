# Scrinium Mobile

Android app for [Scrinium](https://github.com/mohak34/scrinium). You need a running Scrinium server.

## Build

You need JDK 17 or later and the Android SDK.

1. Create `keystore.properties` in the repo root with your server's Google web client ID:

   ```properties
   scriniumGoogleClientId=<GOOGLE_CLIENT_ID>
   ```

2. Build and install a debug APK:

   ```bash
   ./gradlew assembleDebug -PscriniumApiUrl="http://10.0.2.2:5173"
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   `10.0.2.2` is your computer as seen from the emulator. To use a real phone, pass your computer's LAN IP and add it to `app/src/debug/res/xml/network_security_config.xml`.

For a release build, add your server URL and signing key to `keystore.properties`, then run `./gradlew assembleRelease`:

```properties
scriniumApiUrl=https://notes.example.com
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

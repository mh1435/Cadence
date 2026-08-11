# Native Android sources

These files are not part of a checked-in Android project. The release workflow
generates a fresh Capacitor project on every build (`npx cap add android`) and
then copies everything in `java/` into `android/app/src/main/java/`, so this
directory is the only place these sources live.

| File | Role |
| --- | --- |
| `MainActivity.java` | Replaces Capacitor's generated activity, only to register the plugin. |
| `VoicePlugin.java` | Bridge the web app calls: `start`, `stop`, `status`, `pending`, `info`, plus a `command` event. |
| `VoiceService.java` | Microphone foreground service running the offline Vosk recogniser and matching the wake word. |
| `HeadlessRunner.java` | Replays a command in an offscreen WebView on the same origin (`https://localhost`) so it shares the app's localStorage. |
| `ModelAssets.java` | Unpacks the bundled model from assets to the files directory the first time it is needed. |
| `PendingStore.java` | Queue for commands that could not be applied immediately; drained by the web app on next launch. |
| `Prefs.java` | Remembers that the user wants background listening, so a reboot can prompt. |
| `BootReceiver.java` | Android 12+ forbids starting a microphone service from the background, so this only posts a "open Cadence to re-arm" notification. |

The workflow also, in `.github/workflows/release.yml`:

- downloads `vosk-model-small-en-us-0.15` (Apache-2.0) into
  `app/src/main/assets/vosk-model/` — this is what makes the APK large;
- adds `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`, and declares the service
  with `foregroundServiceType="microphone"`;
- adds the `vosk-android`, `jna` and `androidx.webkit` dependencies, keeps JNA's
  native libraries extracted (`useLegacyPackaging true`) and leaves the model
  files uncompressed.

## Constraints worth remembering

- Background microphone access is only legal through a foreground service with
  an ongoing notification. That notification cannot be hidden.
- A reboot cannot re-arm listening by itself; the user must open the app once.
- Wake-word recognition is English-only, because each Vosk language is a
  separate ~40 MB model. Tapping the mic still uses the OS recogniser, which
  covers every language the app is translated into.
- Only one writer should touch localStorage at a time, which is why the service
  hands commands to the live page (via the plugin) whenever the app is open and
  only falls back to the offscreen WebView when it is not.

## Checking changes without a device

`javac` against hand-written stubs catches signature and typo errors before a
ten-minute CI round trip; there is no emulator in this environment, so runtime
behaviour on a real phone is the one thing that still has to be verified by
installing the APK.

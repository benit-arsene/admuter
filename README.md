# AdMuter

A lightweight Android utility app that **automatically mutes Spotify audio ads** so you can enjoy uninterrupted music.

## How It Works

1. **Listens** — A `BroadcastReceiver` listens for Spotify's `com.spotify.music.metadatachanged` intent.
2. **Detects** — Checks if the track metadata signals an ad (`id` starts with `"spotify:ad"`, `artist` is blank, or `track` equals `"Advertisement"`).
3. **Mutes** — Uses `AudioManager` to set the music stream volume to `0` the instant an ad is detected.
4. **Restores** — Caches your original volume level and restores it as soon as normal music resumes.

## Architecture

| Component | Role |
|-----------|------|
| **SpotifyReceiver** | `BroadcastReceiver` — detects ad broadcasts from Spotify |
| **MuterService** | `Foreground Service` — manages audio, caches volume, wraps the receiver |
| **MainActivity** | Single-screen UI with a toggle switch to start/stop the service |

## Build

```bash
# Clone and build with Gradle
git clone https://github.com/benit-arsene/admuter.git
cd admuter
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow (`.github/workflows/build.yml`) automatically builds the APK on every push.

## Requirements

- Android 8.0+ (API 26)
- Spotify app installed on the device

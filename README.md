# Cue

An Android overlay that watches your active conversation and surfaces three contextual reply suggestions — without leaving the app.

## How it works

- **Accessibility Service** reads the visible conversation from any supported app
- **Floating overlay** appears at the bottom with 3 reply options
- Tap any option → copied to clipboard → paste and send
- Context is persisted across sessions per conversation

## Supported apps

- WhatsApp
- Hinge

## Setup

1. Install the APK
2. Open **Cue**, paste your Gemini API key, tap Save
3. Grant overlay permission
4. Enable the accessibility service
5. Open a chat — suggestions appear automatically

## Build

```bash
# Requires JDK 17 + Android SDK, or just open in Android Studio
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions CI is configured — push to `main` to get a downloadable APK artifact.

## Stack

- Kotlin, Android Accessibility API
- Gemini 1.5 Flash (via REST)
- OkHttp, Coroutines
- minSdk 26 (Android 8.0+)

# ToonForge Tagalog

On-device **1080x1920 @ 30fps** Tagalog animated storytelling studio built with Kotlin, Jetpack Compose, OkHttp, Jetpack DataStore, Media3 ExoPlayer, and hardware-accelerated **EGL14 + MediaCodec + MediaMuxer** MP4 export.

## Key Features

- **3 Script Source Modes:**
  1. **Direct Script Mode (Sureball / No API Timeout):** Paste full scene JSON or plain Tagalog paragraphs, or import `.json` / `.txt` files directly. Automatically splits plain Tagalog text into balanced ~12–18 word animated scenes with varied camera motions, poses, shot framings, and style-matched visual prompts—bypassing LLM/Horde calls completely.
  2. **Custom LLM API:** Universal OpenAI-compatible director supporting Groq, Gemini OpenAI endpoint, OpenRouter, or custom endpoints with 180s read timeout, SSE streaming, automatic non-streaming fallback, and 4-scene batching.
  3. **AI Horde (`aihorde.net`):** Async community text & art generation with built-in Cloudflare (`1.1.1.1`) and Google (`8.8.8.8`) DNS-over-HTTPS fallback on ISP DNS failures.
- **ElevenLabs TTS + Sample-Accurate PCM Stitching:** Word-level timeline alignment, pronunciation dictionary, custom BGM ducking, and multi-key quota rotation.
- **9 Animation Style Engines & Puppet Rigging:** Paper-Cut Diorama, Stickman Whiteboard, Flat Vector, Dark Motion Comic, Chibi Storytime, Viral HUD Explainer, 2D Cutaway, Viral 2D Story Explainer, and 16-Bit Pixel Cutaway.

## Building Locally (Android Studio / CLI)

1. Ensure a `debug.keystore` exists once after cloning (if `debug.keystore.base64` is absent, generate a standard debug keystore):
   ```bash
   if [ -f debug.keystore.base64 ]; then
     base64 -d debug.keystore.base64 > debug.keystore
   else
     keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   fi
   ```
2. Open the project in **Android Studio** (uses Gradle `9.3.1` from `gradle/wrapper/gradle-wrapper.properties`), or run via CLI with Gradle installed:
   ```bash
   gradle :app:testDebugUnitTest
   gradle :app:assembleDebug
   ```
   *(Optional: run `gradle wrapper` once locally if you prefer using `./gradlew`.)*

   Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Configuration (In-App Secrets)

API keys (ElevenLabs, Groq/Gemini/OpenRouter/Custom LLM, or optional AI Horde key) are configured securely at runtime inside the app's **API & Settings** tab and persisted locally via Jetpack DataStore—never hardcoded in the repository.

## GitHub Actions CI

Pushing or opening a pull request to `main` or `master` automatically runs `.github/workflows/android-build.yml`, executes `:app:testDebugUnitTest`, builds `app-debug.apk`, and uploads it as a downloadable artifact (`toonforge-tagalog-debug-apk`).

Optional repository secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`) enable automatic signed `assembleRelease` builds as well.

# AutoRedial Pro - Android Telecom & Millisecond VAD Engine

Production-ready native Android Kotlin application designed for high-frequency auto-redialing with ultra-fast millisecond Voice Activity Detection (VAD) and Telecom framework integration.

## 🚀 Key Features
1. **Millisecond Cut-Off (<35ms)**: Disconnects within milliseconds upon hearing IVR robot messages or busy tones.
2. **Ringing Tone Lock**: Goertzel algorithm isolates 440Hz/480Hz dual tone cadence, preventing accidental disconnection during normal ring cycles.
3. **Smart Stop Condition**: Automatically halts the loop when the human recipient answers (Call.STATE_ACTIVE).
4. **In-App Dialpad with DTMF**: Full dialpad with audio feedback.
5. **Auto-Speakerphone on Ringing**: Automatically routes audio to hands-free speaker.
6. **Mute Rapid Redials**: Eliminates annoying robot clicks during dialing.
7. **Fine-Tuned Delay (0 - 2000 ms)**: Customizable intervals between dials.
8. **Android 14+ Ready**: Fully configured foreground service with `phoneCall` and `microphone` types.

## 🛠️ How to Build
### Method 1: GitHub Actions (Automated Cloud Build)
This repository includes `.github/workflows/build-and-release-apk.yml`. Every commit automatically compiles and releases `AutoRedialPro-v1.0.0.apk` in GitHub Releases!

### Method 2: Android Studio
1. Open this repository in Android Studio Ladybug or newer.
2. Let Gradle sync and select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

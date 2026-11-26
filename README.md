# VPhoneOS + ClearVoice Live (Virtual Phone with Virtual Microphone Routing)

A standalone Android Virtual Phone APK (`com.vphone.os`) that integrates the **ClearVoice Live v1.1** DSP voice processor directly into the virtual phone host and routes your primary phone's processed microphone audio to every app running inside the virtual phone.

## How the Audio Routing Works
1. **Primary Phone Mic -> Host ClearVoice Engine (`VirtualMicService`)**:
   Captures hardware microphone audio at 48 kHz mono PCM-16, applies real-time Noise Gate, Clarity EQ, Soothing Warmth, Mic Gain, and optional Male-to-Female Pitch + Formant Shifting.
2. **Low-Latency IPC Bus (`@vphone_vmic_bus`)**:
   Broadcasts processed 20 ms PCM frames over a Linux abstract Unix domain socket to every isolated virtual app process (`:vp0` - `:vp3`).
3. **Native Interception Inside Virtual Apps (`libvphone_native.so`)**:
   - Hooks `libaudioclient.so` (`AudioRecord::start`, `stop`, `read`) for all Java `android.media.AudioRecord` and legacy native apps.
   - Hooks `libaaudio.so` (`AAudioStream_requestStart`, `read`, and callback-mode capture) for modern VoIP, WebRTC, Oboe, and game engines.
   - Prevents Android hardware mic conflicts by virtualizing `start()` inside `:vp0` - `:vp3` while resampling 48 kHz mono live to each guest app's requested sample rate, channel count (mono/stereo), and format (PCM-16 / PCM-Float / PCM-8).

## Build the Installable APK in the Cloud (Free, No Android Studio Required)
1. Unzip `VPhoneOS_ClearVoice_AndroidProject.zip` and upload all files (including `.github/workflows/build-apk.yml`) to a new GitHub repository.
2. Open the repository's **Actions** tab -> **Build VPhoneOS + ClearVoice APK**.
3. Download the artifact **VPhoneOS-ClearVoice-APK** (`app-debug.apk`) and install it on your Android phone.

## Build Locally
```bash
gradle assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Quick Usage Guide
1. Install and open **VPhoneOS + ClearVoice** on your primary phone.
2. Open the **ClearVoice Mic** tab -> tap **Start ClearVoice Virtual Mic** and grant Microphone permission.
3. Adjust your voice preset (**Soothing**, **Clear**, or **Female**). Keep **Local Headphone Monitor** OFF unless wearing headphones.
4. Switch to the **Virtual Phone** tab:
   - Tap **Launch Built-in Virtual Mic Tester (:vp0)** to record & play back a quick clip proving `:vp0` receives your ClearVoice audio.
   - Tap **Install APK** or **Clone Host App** to add real apps inside the virtual phone.

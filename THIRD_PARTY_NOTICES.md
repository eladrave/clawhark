# Third-party notices

The native `libvad_jni.so` libraries come from the official **android-vad 2.0.10** WebRTC release by Georgiy Konovalov:
https://github.com/gkonovalov/android-vad/releases/tag/2.0.10

Release AAR: `android-vad-webrtc-v2.0.10-release.aar`
SHA-256: `e4e575614772cbe27c711752d90771b594de601a9c52f69cca7b07c7da1f75e7`

Only its four native ABI libraries are included. Rave AI provides a small Java JNI adapter, avoiding an otherwise unnecessary upgrade of the app's Kotlin/Android build toolchain. The native library exports and ARM64 ELF page alignment are checked during APK qualification.

Licenses included in the APK under `assets/licenses`:
- `Android-VAD-MIT.txt`: Georgiy Konovalov, MIT.
- `WebRTC-VAD-BSD.txt`: The WebRTC project authors, BSD 3-Clause.

No Omi implementation code is copied. Omi's software/hardware detection split and its policy of retaining audio on uncertain VAD results informed the design. Omi has dedicated microphone activity hardware that a generic Wear OS app cannot assume it can control. See:
- https://github.com/BasedHardware/omi/blob/main/app/lib/services/wals/mobile_batch_vad.dart
- https://github.com/BasedHardware/omi/blob/main/backend/utils/stt/vad.py
- https://github.com/BasedHardware/omi/blob/main/omi/firmware/omi/src/t5838_aad.c

# Carlink Native

**A native Android implementation of a Carlink alternative, without Flutter/Dart dependencies.**

[![Kotlin](https://img.shields.io/badge/Kotlin-81.2%25-blue?logo=kotlin)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/Java-18.8%25-orange?logo=java)](https://www.java.com/)
[![Android](https://img.shields.io/badge/Platform-Android-green?logo=android)](https://developer.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

This project is a **lightly updated fork** of the excellent native Android port created by [@lvalen91](https://github.com/lvalen91).  
The majority of the core architecture, protocol implementation, video/audio handling, and USB communication was developed by him in:

- https://github.com/lvalen91/Carlink (Flutter-based rewrite with native branch)
- https://github.com/lvalen91/Carlink_native (pure native Kotlin/Java version)

**Huge thanks to lvalen91** — this fork would not exist without his extensive reverse-engineering and clean native implementation.

This fork adds quality-of-life improvements, increased reliability on GM AAOS head units, and preparation for potential Google Play publishing.

**Work in Progress** – Builds are versioned in commit messages (e.g., Build 52).

## Improvements in This Fork
- **Synced with upstream**: Includes all of lvalen91's work through upstream build 145 (synchronous H.264 decoder rewrite, resume/pixelation fixes, decoder size probe, Media3 MediaSession, instrument-cluster navigation, Android Auto portrait support, Android 10 support, and more). See [`documents/revisions.txt`](documents/revisions.txt) for the full list.
- **Fork defaults**: OpenSourceEV branding and icons, Bluetooth audio by default, Android Auto enabled on every connection.
- **Google Play Readiness**:
  - Harmonized package name, versioning, and build configuration.
  - Clean native-only codebase.
  - Optimized for AAOS 12L/14 (GM Intel-based systems) with hardware-accelerated H.264.
- **Core Features (Thanks to lvalen91's Foundation)**:
  - Full USB protocol for Carlinkit CPC200-CCPA adapters.
  - Hardware-accelerated video (MediaCodec) and multi-stream audio.
  - Multitouch support.
  - Microphone capture for Siri/voice commands and calls.
  - MediaSession integration.
  - Periodic keyframe requests for stable CarPlay sessions.
  - Auto-reconnect with exponential backoff.
  - Detailed logging and performance stats.

Enables **Apple CarPlay** and **Android Auto** projection, with Android Auto forced on every connection.

## Requirements
- Android Studio (latest, JDK 21+ recommended).
- Android SDK: minSdk 29 (Android 10), targetSdk 36.
- Compatible hardware: Carlinkit CPC200-CCPA adapter + GM AAOS head unit (extensively tested on Intel-based systems).

## Important Notes for GM Vehicles
- **Driving restrictions**: A plain sideloaded APK will not run while the vehicle is in motion. To enable full functionality while driving:
  1. Create a Google Play Developer Console account ($25 one-time fee).
  2. Upload the AAB/APK to the **Internal Testing** track.
  3. Add your Google account to the tester list.
  4. Install/update via the Play Store on the head unit.

## Installation & Build

1. **Clone the Repo**:
   git clone https://github.com/MotoInsight/Carlink_native.git
   cd Carlink_native

2. **Customize for Publishing** (Google Play Internal Testing):
   - Open `app/build.gradle.kts`.
   - Change `ownerApplicationId` to your unique package (applicationId and the cluster icon authority follow it).
   - Update `versionCode` / `versionName` in `defaultConfig`.
   - Use the `playRelease` variant for Play uploads (`sideloadDebug` / `sideloadRelease` for direct APK installs).
   - Sign the release build with your keystore.

3. **Build**:
   - Open in Android Studio → Sync Gradle.
   - Build → Build Bundle(s) / APK(s) → `bundlePlayRelease` or `assembleSideloadRelease`.

4. **Deploy**:
   - Upload AAB to Google Play Internal Testing.
   - Install via Play Store on the head unit.

## Usage
- Plug in adapter → auto-connect.
- Settings: Immersive mode, audio routing, mic source, WiFi band.
- **Reset Device** (red button): Full USB session restart.
- **Reboot Adapter**: Reboots the CPC200 adapter itself.

## Contributing
- Fork and open PRs for fixes or enhancements.
- Focus areas: AAOS stability, video reliability, Play Store compliance.
- Include logs when reporting issues.

## Changelog (Recent Builds)
- **Upstream sync** (October 2026): Merged lvalen91/carlink through build 145 + decoder size probe (PR #14). Upstream fixes supersede this fork's Build 52–61 video changes.
- **Build 61** (January 11, 2026): Post-reconnect pixelation/distortion fix.
- **Build 52** (January 4, 2026): Additional keyframe requests on resume; significant black screen reduction.
- See commit history for earlier changes.

## Community & Support
- OLD discussion thread: [XDA Developers - General Motors Google Built-in Tinkering](https://xdaforums.com/t/general-motors-google-built-in-tinkering.4668105/)
- NEW Main discussion thread: [XDA Developers - Carlink](https://xdaforums.com/t/carlink.4774308/)
- Reddit community: [r/SilveradoEV](https://www.reddit.com/r/SilveradoEV/)
- Check out [OpenSourceEV.com](https://OpenSourceEV.com) for upcoming closed beta access to this project, Silverado EV 3D-printed projects, and open-source STL files.

## License
MIT License – Free to use, modify, and distribute.

## Credits & Acknowledgments
- **Primary development & native port**: [@lvalen91](https://github.com/lvalen91) – massive thanks for the foundation!
- This fork: Minor QoL improvements and GM-specific tuning by MotoInsight / OpenSourceEV.
- Community: XDA thread contributors, OpenSourceEV team, and testers across GM vehicle forums.

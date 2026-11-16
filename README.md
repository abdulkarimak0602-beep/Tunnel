# DeshTunnel

Encrypted Bangladesh VPN & Proxy Tunnel Android app (Kotlin + Jetpack Compose + Material 3) with `VpnService`, SOCKS5/HTTPS-TLS tunneling, DNS-over-TLS 1.3 (`1.1.1.1`, `9.9.9.9`, `94.140.14.14`), per-app split tunneling, live public IP verification, and Dark/Light mode in Bangla and English.

## Building an APK

### Option 1: GitHub Actions (Automatic APK Build)
This repository includes `.github/workflows/build-apk.yml`.
1. Push this repository to GitHub (via the AI Studio **Push to GitHub** menu or `git push`).
2. Open the repository's **Actions** tab on GitHub and select **Build DeshTunnel APK** (triggered automatically on push or manually via **Run workflow**).
3. Download **`DeshTunnel-debug-apk`** from the workflow run's **Artifacts** section.

### Option 2: Local Command Line
Requires **JDK 21**, **Android SDK** (`ANDROID_SDK_ROOT` configured), and **Gradle 9.3.1**:

```bash
# Decode debug keystore if not already present
base64 -d debug.keystore.base64 > debug.keystore

# Build debug APK
gradle assembleDebug
```

The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

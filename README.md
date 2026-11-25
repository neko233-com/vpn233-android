# vpn233 (`vpn233-android`)

Modern, ultra-low-power, compact **arm64-v8a** Android proxy client powered by **Kotlin + Jetpack Compose** and **Mihomo (Clash.Meta v1.19.34)** core.

## Highlights

- **Ultra-Low Power & Background Reliability**:
  - Screen-state aware monitoring: automatically halts JNI traffic polling, pauses notification binder updates, trims Go memory, and drops core log overhead when the screen turns off or app is swiped from Recents
  - Zero `WAKE_LOCK` polling — packet forwarding runs purely event-driven in Go/kernel `epoll`
  - `Mixed` / `System` / `gVisor` TUN stack with `MTU 9000`, `UDPTimeout 60s`, and default `find-process-mode: off` to avoid per-connection Binder IPC overhead
  - One-tap battery optimization exemption prompt + Android Quick Settings Tile + boot auto-start
- **Compact arm64-only APK (~16.75 MB)**:
  - `arm64-v8a` ABI only (`libmihomo.so` built with `cmfa,with_gvisor,with_low_memory,no_easytier,no_tailscale,no_zerotier,no_fake_tcp`)
  - R8 full minification + resource shrinking + custom vector icons (zero `material-icons-extended` bloat)
  - Lightweight built-in GeoIP/GeoSite bootstrap (`~246 KB` compressed in APK) with 1-tap online upgrade to full GeoIP / GeoSite / ASN databases via jsDelivr
- **Clash Subscription & Interaction Flow**:
  - **Dashboard**: 1-tap connect orb, live upload/download sparkline, instant `Rule / Global / Direct` mode switching, subscription traffic quota bar, and quick actions
  - **Proxies**: Horizontal strategy-group chips, instant search, sort by latency/name, 1-tap concurrent delay testing, and proxy/rule provider updates
  - **Profiles**: Clash subscription URL import (parses `Subscription-Userinfo`, `Profile-Update-Interval`, `Content-Disposition`), smart clipboard auto-import, local `.yaml`/`.zip` import, `clash://` / `vpn233://` deep link support, built-in YAML editor with live core validation, and batch update
  - **Live Monitor**: Real-time active connections & streaming core logs

## Build

### 1. Build Go Mihomo Core (`arm64-v8a`)

Requires Go 1.25+ and Android NDK 27+.

- Windows (PowerShell):
  ```powershell
  .\scripts\build-core.ps1
  ```
- Linux / macOS:
  ```bash
  ./scripts\build-core.sh
  ```

### 2. Build Release APK

```bash
./gradlew assembleRelease
```

Output APK: `app/build/outputs/apk/release/app-release.apk`

# vpn233 (`vpn233-android`)

English | [简体中文](README-CN.md)

A sleek, ultra-low-power, high-performance **arm64-v8a** Android proxy client written in **Kotlin + Jetpack Compose** and powered by the **Mihomo (Clash.Meta v1.19.34)** core.

---

## Key Features

- **Ultra-Low Power & Background Stability**:
  - **Zero WakeLock architecture**: packet forwarding runs purely event-driven inside Go/Linux `epoll` without holding partial wake locks.
  - **Screen-aware monitoring**: when the screen turns off (`ACTION_SCREEN_OFF`) or the app is swiped from Recents, JNI traffic polling and notification binder updates halt completely, log verbosity trims down to `WARNING`, and Go releases unused heap pages cleanly.
  - **Low-overhead defaults**: `MTU 9000` jumbo TUN frames, `UDPTimeout 60s`, `Mixed` TUN stack (kernel `System` TCP + `gVisor` UDP), and `find-process-mode: off` by default to eliminate per-connection Binder IPC overhead.
  - **Background keep-alive**: 1-tap battery optimization exemption prompt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), Quick Settings Tile (`Vpn233TileService`), and boot auto-start (`Vpn233BootReceiver`).
- **Compact `arm64-v8a` APK (~16.75 MB)**:
  - `arm64-v8a` native ABI only (`libmihomo.so` compiled with `-tags "cmfa,with_gvisor,with_low_memory,no_easytier,no_tailscale,no_zerotier,no_fake_tcp"`, `-trimpath -ldflags="-s -w"`, and `llvm-strip --strip-unneeded`).
  - R8 full minification, resource shrinking, `zh`/`en` locale filtering, and custom `ImageVector` icons (no `material-icons-extended` bloat).
  - Lightweight bootstrap `geoip.metadb` + `geosite.dat` (`~246 KB` compressed in APK) with 1-tap online upgrade to full GeoIP / GeoSite / ASN databases via jsDelivr.
- **Full Protocol, Subscription & Scenario Support**:
  - Imports standard Clash / Mihomo YAML subscriptions (parses `Subscription-Userinfo`, `Profile-Update-Interval`, `Content-Disposition`), Base64 proxy lists, and share links (`vmess://`, `vless://`, `trojan://`, `ss://`, `hy2://`, `hysteria2://`, `tuic://`, `socks5://`) with **idempotent deduplication** (repeated URL, file, ZIP, clipboard, or ADB imports update in-place without duplicates or needless tunnel resets).
  - **Global & Per-Profile YAML Merge & Auto-Update Policies**: Clash Verge style global Merge (`globalMergeYaml`, applied across every profile) plus per-profile `merge.yaml` overrides (`prepend-rules`, `append-rules`, `prepend-proxies`, `prepend-proxy-groups`, `nameserver`, `fake-ip-filter`, `nameserver-policy`). Global Merge is stored in local settings; per-profile Merge survives subscription updates and profile ZIP backups. Supports wake/foreground auto-update intervals, custom `User-Agent`, and `Auto` / `Proxy` / `Direct` fetch routing.
  - **Diagnostics, Rule-Match Tester & Network Resilience**: built-in **Diagnostics** tab to simulate any domain/IP:port against live Mihomo rules & view exact outbound chains, verify exit IP/country, flush DNS & FakeIP caches, display LAN `IP:port` copy pills, switch between **ZH / EN** UI live, use launcher **App Shortcuts**, and auto-close stale half-open sockets on Wi-Fi ↔ 5G switches.
  - Hot-switching active profiles (`activate_profile` / UI), switching proxy nodes (e.g. Korea → USA), or changing routing mode automatically **closes all previous active connections** so no stale sockets remain pinned to the prior route.
  - Built-in YAML editor with live core validation, batch subscription update, and `.zip` full backup export.
  - Supports **Rule / Global / Direct** live mode switching. Global mode initially prefers the `PROXY` group and retains valid user selections per profile. Also supports **Allow LAN** sharing (`0.0.0.0:<port>`), **IPv6** dual-stack, **Per-App Proxy** (allowlist/denylist with app icon search), **DNS Hijack**, **Sniffer**, **FakeIP**, and **Proxy/Rule Providers**.
- **Interactive Rule Editor & Live Connection Actions**:
  - Visual rule builder supporting `DOMAIN`, `DOMAIN-SUFFIX`, `DOMAIN-KEYWORD`, `DOMAIN-REGEX`, `IP-CIDR`, `IP-CIDR6`, `SRC-IP-CIDR`, `GEOIP`, `GEOSITE`, `IP-ASN`, `DST-PORT`, `SRC-PORT`, `NETWORK`, `PROCESS-NAME`, `PROCESS-PATH`, `RULE-SET`, and raw `AND`/`OR`/`SUB-RULE`/`MATCH` expressions with `no-resolve`, ordering (`PREPEND` or `BEFORE_MATCH`), toggle switches, and live hot-reload.
  - Inspect all active core rules and create rules with 1 tap directly from any entry in **Active Connections**.

---

## Backup and restore

The profiles ZIP includes configs, subscription URLs and traffic metadata, update options, per-profile Merge, cached `providers/` files, and the selected profile. Import the ZIP from the profiles screen to restore it; existing profiles are matched where possible. Provider files omitted by older backups cannot be recovered from those archives.

ZIP backups can contain subscription URLs and node credentials in plaintext; store them securely. Global settings, global Merge, custom rules, selected nodes, and Geo databases are not included. Import validates all configs and Merge files before changes and limits archive paths, entry count, and size: 16 MiB per entry, 64 MiB expanded, 1,024 entries, and 70 MiB input.

---

## Agent / ADB Automation API

`vpn233` exposes a synchronous, structured JSON control endpoint via `AgentControlProvider` (`content://com.neko233.vpn233.agent`).  
For security, caller UID is strictly verified (`Process.SHELL_UID` / `ROOT_UID` / self UID only — third-party apps cannot invoke it).

```bash
# Optional on Chinese OEM ROMs (ColorOS/MIUI/OriginOS): if stopped & frozen while screen-off, wake once:
# adb shell am start -n com.neko233.vpn233/.MainActivity

# Check status, traffic, mode, profile & battery whitelist state
adb shell content call --uri content://com.neko233.vpn233.agent --method status

# Import Clash/Base64 subscription URL & activate
adb shell content call --uri content://com.neko233.vpn233.agent --method import_url --arg "https://example.com/sub" --extra name:s:MySub

# Import inline YAML (or app-accessible file path)
adb shell content call --uri content://com.neko233.vpn233.agent --method import_yaml --arg "/sdcard/Android/data/com.neko233.vpn233/files/test.yaml" --extra name:s:LocalTest

# Start / Stop / Reload VPN (requires prior user VPN consent if first launch)
adb shell content call --uri content://com.neko233.vpn233.agent --method start
adb shell content call --uri content://com.neko233.vpn233.agent --method stop
adb shell content call --uri content://com.neko233.vpn233.agent --method reload

# Switch routing mode (rule | global | direct)
adb shell content call --uri content://com.neko233.vpn233.agent --method set_mode --arg rule

# Query proxy groups, test delays, and switch node (automatically closes previous active connections)
adb shell content call --uri content://com.neko233.vpn233.agent --method list_groups
adb shell content call --uri content://com.neko233.vpn233.agent --method test_group --arg PROXY
adb shell content call --uri content://com.neko233.vpn233.agent --method test_proxy --arg US-01
adb shell content call --uri content://com.neko233.vpn233.agent --method select_proxy --arg PROXY --extra proxy:s:US-01

# Hot-switch profile (closes all previous connections and reloads) / Global & Per-Profile Merge YAML / options / backup
adb shell content call --uri content://com.neko233.vpn233.agent --method activate_profile --arg MySub
adb shell content call --uri content://com.neko233.vpn233.agent --method update_profile --arg MySub
adb shell content call --uri content://com.neko233.vpn233.agent --method set_profile_options --arg MySub --extra autoUpdateMinutes:i:720 --extra updateMode:s:PROXY --extra ua:s:clash-verge/v2.2.3
adb shell content call --uri content://com.neko233.vpn233.agent --method set_global_merge_yaml --extra yaml:s:/sdcard/Android/data/com.neko233.vpn233/cache/global_merge.yaml
adb shell content call --uri content://com.neko233.vpn233.agent --method set_merge_yaml --arg MySub --extra yaml:s:"prepend-rules:\n  - DOMAIN-SUFFIX,openai.com,PROXY"
adb shell content call --uri content://com.neko233.vpn233.agent --method export_backup

# Manage rules dynamically (hot-reloads running core & resets old connections)
adb shell content call --uri content://com.neko233.vpn233.agent --method list_rules
adb shell content call --uri content://com.neko233.vpn233.agent --method add_rule --arg "DOMAIN-SUFFIX,openai.com,PROXY" --extra position:s:PREPEND
adb shell content call --uri content://com.neko233.vpn233.agent --method update_rule --arg 0 --extra rule:s:DOMAIN-SUFFIX,openai.com,DIRECT --extra position:s:BEFORE_MATCH
adb shell content call --uri content://com.neko233.vpn233.agent --method move_rule --arg 1 --extra to:i:0
adb shell content call --uri content://com.neko233.vpn233.agent --method toggle_rule --arg 0 --extra enabled:b:false
adb shell content call --uri content://com.neko233.vpn233.agent --method remove_rule --arg 0
adb shell content call --uri content://com.neko233.vpn233.agent --method clear_rules

# Diagnostics, LAN IP discovery, DNS flush, active connections, logs, GC & settings
adb shell content call --uri content://com.neko233.vpn233.agent --method test_match --arg api.openai.com --extra port:i:443 --extra network:s:tcp
adb shell content call --uri content://com.neko233.vpn233.agent --method flush_dns
adb shell content call --uri content://com.neko233.vpn233.agent --method lan_addresses
adb shell content call --uri content://com.neko233.vpn233.agent --method list_providers
adb shell content call --uri content://com.neko233.vpn233.agent --method connections
adb shell content call --uri content://com.neko233.vpn233.agent --method close_all_connections
adb shell content call --uri content://com.neko233.vpn233.agent --method logs
adb shell content call --uri content://com.neko233.vpn233.agent --method force_gc
adb shell content call --uri content://com.neko233.vpn233.agent --method set_setting --arg allowLan --extra value:s:true
adb shell content call --uri content://com.neko233.vpn233.agent --method set_setting --arg language --extra value:s:en

# Jump directly to a UI tab or Rules dialog
adb shell am start -n com.neko233.vpn233/.MainActivity --es tab proxies
adb shell am start -n com.neko233.vpn233/.MainActivity --ez open_rules true
```

---

## Build & Test

### Prerequisites
- JDK 17+
- Android SDK (`compileSdk 36`, `build-tools 36.0.0`) & Android NDK 27+
- Go 1.25+ (only needed if rebuilding `go-core` into `libmihomo.so`)

### 1. Run Go Core Unit Tests & Build `libmihomo.so` (`arm64-v8a`)

```bash
cd go-core
CGO_ENABLED=0 go test -v -tags "cmfa,with_gvisor,with_low_memory,no_easytier,no_tailscale,no_zerotier,no_fake_tcp" ./...
```

Rebuild native shared library:
- **Windows**: `.\scripts\build-core.ps1`
- **Linux / macOS**: `./scripts/build-core.sh`

### 2. Run Android Unit Tests & Build Release APK

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Release APK output: `app/build/outputs/apk/release/app-release.apk`

Production signing requires a local `keystore.properties` and its keystore, neither of which is tracked in Git. Without production signing credentials, the release build produces `app-release-unsigned.apk` instead of silently using a debug key. Local unit tests and builds do not replace an on-device VPN connection test.

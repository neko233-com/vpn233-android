# vpn233 (`vpn233-android`)

[English](README.md) | 简体中文

基于 **Kotlin + Jetpack Compose** 与 **Mihomo (Clash.Meta v1.19.34)** 内核打造的高颜值、超低功耗、极致精简包体（仅 `arm64-v8a`）Android 独立代理客户端。

---

## 核心特性

- **超低功耗与后台稳定运行**：
  - **零 WakeLock 设计**：绝不持有唤醒锁，数据包转发完全由 Go / Linux 内核 `epoll` 事件驱动唤醒。
  - **息屏感知休眠**：监听系统亮灭屏（`ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`），息屏或划掉后台卡片（`onTaskRemoved`）后自动停止 JNI 速率轮询与通知栏高频 Binder 刷新，并将核心日志自动降级为 `WARNING`、释放空闲堆内存。
  - **极致低开销默认策略**：TUN 默认 `MTU 9000`、`UDPTimeout 60s`、`Mixed` 栈（TCP 走内核 System 栈、UDP 走 gVisor），且默认关闭每连接进程 UID 查询（`find-process-mode: off`），大幅减少 Binder IPC 损耗。
  - **完善保活能力**：支持首页/设置页一键申请忽略电池优化（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）、系统下拉快捷开关瓷贴（`Vpn233TileService`）与开机自动连接（`Vpn233BootReceiver`）。
- **极致精简 `arm64-v8a` 包体（约 16.75 MB）**：
  - 仅保留 `arm64-v8a` 架构，单库 `libmihomo.so` 使用 `-tags "cmfa,with_gvisor,with_low_memory,no_easytier,no_tailscale,no_zerotier,no_fake_tcp"` 与 `-trimpath -ldflags="-s -w"` + `llvm-strip` 裁剪。
  - 开启 R8 全量混淆与资源收缩、语言资源仅保留 `zh`/`en`、自定义精简矢量图标（零 `material-icons-extended` 臃肿依赖）。
  - 内置精简版 `geoip.metadb` + `geosite.dat`（APK 内仅占用约 `246 KB`），支持设置页一键在线升级为完整版 GeoIP / GeoSite / ASN 数据库。
- **全场景协议、订阅与网络适配**：
  - 支持标准 Clash / Mihomo YAML 订阅链接导入（自动解析 `Subscription-Userinfo` 流量配额、`Profile-Update-Interval` 与文件名）、Base64 订阅自动解码、常见节点分享链接直转 YAML（`vmess://`、`vless://`、`trojan://`、`ss://`、`hy2://`、`hysteria2://`、`tuic://`、`socks5://`），并支持 **幂等去重导入**（重复导入同 URL、同文件、ZIP、剪贴板或 ADB 配置不会重复生成条目）。
  - **全局与单配置 YAML Merge 及更新策略**：支持 Clash Verge 风格 **全局 Merge 覆写**（对所有订阅生效）以及每配置独立 `merge.yaml`（支持 `prepend-rules`、`append-rules`、`prepend-proxies`、`prepend-proxy-groups`、`nameserver`、`fake-ip-filter`、`nameserver-policy`），订阅更新与 `.zip` 备份均持久保留；支持亮屏/前台自动更新周期、自定义 `User-Agent` 及 `自动 / 仅经代理 / 仅直连` 拉取通道。
  - **规则命中诊断、出口 IP 检测与切网自愈**：监控弹窗内置 **诊断** 分页，可模拟测试任意域名/IP 命中的规则与最终出站链路、检测公网出口 IP、清理 DNS 与 FakeIP 缓存；开启 Allow LAN 自动展示并可 1 键复制局域网共享地址；Wi-Fi ↔ 5G 切换自动清理旧连接；支持中英实时切换与桌面 **App Shortcuts** 快捷入口。
  - 热切换配置文件、切换服务器节点（如韩国 → 美国）或切换路由模式时，自动 **断开先前所有活跃连接**，确保长连接与 UDP/QUIC 会话立即切换无残留。
  - 支持剪贴板智能识别、本地文件导入、内置 YAML 编辑器与内核级校验、全部订阅批量更新与 `.zip` 备份导出。
  - 完整支持 **规则 / 全局 / 直连** 实时切换、**允许局域网共享（Allow LAN）**、**IPv6 双栈**、**分应用代理（黑白名单 + 应用图标搜索）**、**DNS 劫持与系统物理 DNS 自动跟踪**、**Sniffer 流量嗅探**、**FakeIP** 与外部 **Proxy/Rule Providers**。
- **可视化规则编辑与实时连接快捷加规则**：
  - 内置可视化**分流规则编辑器**：支持 `DOMAIN`、`DOMAIN-SUFFIX`、`DOMAIN-KEYWORD`、`DOMAIN-REGEX`、`IP-CIDR`、`IP-CIDR6`、`SRC-IP-CIDR`、`GEOIP`、`GEOSITE`、`IP-ASN`、`DST-PORT`、`SRC-PORT`、`NETWORK`、`PROCESS-NAME`、`PROCESS-PATH`、`RULE-SET` 以及原始 `AND/OR/SUB-RULE/MATCH` 表达式；支持 `no-resolve`、`前置最高优先` / `兜底规则之前` 优先级调节、单条开关、上下移动与保存即热重载。
  - 支持查看运行中核心的全部生效规则；在**实时连接监控**中点击任意连接即可 1 键生成直连/代理/拦截规则。

---

## Agent / ADB 自动化控制接口

`vpn233` 内置同步结构化 JSON 控制端点 `AgentControlProvider`（`content://com.neko233.vpn233.agent`）。  
接口内部严格校验调用者 UID，仅允许 `adb shell`（`SHELL_UID`）、`root` 或应用自身调用，第三方应用无法越权调用。

```bash
# 部分国产系统（ColorOS/MIUI/OriginOS）在未启动 VPN 且息屏冻结时，首次可先唤醒一次主进程：
# adb shell am start -n com.neko233.vpn233/.MainActivity

# 1. 查询当前状态、流量、模式、配置与电池优化白名单状态
adb shell content call --uri content://com.neko233.vpn233.agent --method status

# 2. 导入 Clash / Base64 订阅 URL 并激活
adb shell content call --uri content://com.neko233.vpn233.agent --method import_url --arg "https://example.com/sub" --extra name:s:我的订阅

# 3. 导入本地 YAML 文件或 YAML 文本
adb shell content call --uri content://com.neko233.vpn233.agent --method import_yaml --arg "/sdcard/Android/data/com.neko233.vpn233/files/test.yaml" --extra name:s:测试配置

# 4. 启动 / 停止 / 热重载 VPN（首次启动需系统已授权 VPN 权限）
adb shell content call --uri content://com.neko233.vpn233.agent --method start
adb shell content call --uri content://com.neko233.vpn233.agent --method stop
adb shell content call --uri content://com.neko233.vpn233.agent --method reload

# 5. 实时切换路由模式 (rule | global | direct)
adb shell content call --uri content://com.neko233.vpn233.agent --method set_mode --arg rule

# 6. 查询策略组、并发/单节点测速与切换节点（切换时自动断开先前回话连接）
adb shell content call --uri content://com.neko233.vpn233.agent --method list_groups
adb shell content call --uri content://com.neko233.vpn233.agent --method test_group --arg PROXY
adb shell content call --uri content://com.neko233.vpn233.agent --method test_proxy --arg US-01
adb shell content call --uri content://com.neko233.vpn233.agent --method select_proxy --arg PROXY --extra proxy:s:US-01

# 7. 热切换配置文件（自动断开所有旧连接）/ 全局与单配置 Merge 覆写 / 更新策略 / 备份导出
adb shell content call --uri content://com.neko233.vpn233.agent --method activate_profile --arg 我的订阅
adb shell content call --uri content://com.neko233.vpn233.agent --method update_profile --arg 我的订阅
adb shell content call --uri content://com.neko233.vpn233.agent --method set_profile_options --arg 我的订阅 --extra autoUpdateMinutes:i:720 --extra updateMode:s:PROXY --extra ua:s:clash-verge/v2.2.3
adb shell content call --uri content://com.neko233.vpn233.agent --method set_global_merge_yaml --extra yaml:s:/sdcard/Android/data/com.neko233.vpn233/cache/global_merge.yaml
adb shell content call --uri content://com.neko233.vpn233.agent --method set_merge_yaml --arg 我的订阅 --extra yaml:s:"prepend-rules:\n  - DOMAIN-SUFFIX,openai.com,PROXY"
adb shell content call --uri content://com.neko233.vpn233.agent --method export_backup

# 8. 动态管理分流规则（添加/编辑/移动/开关/清空后自动断开旧连接并热重载核心）
adb shell content call --uri content://com.neko233.vpn233.agent --method list_rules
adb shell content call --uri content://com.neko233.vpn233.agent --method add_rule --arg "DOMAIN-SUFFIX,openai.com,PROXY" --extra position:s:PREPEND
adb shell content call --uri content://com.neko233.vpn233.agent --method update_rule --arg 0 --extra rule:s:DOMAIN-SUFFIX,openai.com,DIRECT --extra position:s:BEFORE_MATCH
adb shell content call --uri content://com.neko233.vpn233.agent --method move_rule --arg 1 --extra to:i:0
adb shell content call --uri content://com.neko233.vpn233.agent --method toggle_rule --arg 0 --extra enabled:b:false
adb shell content call --uri content://com.neko233.vpn233.agent --method remove_rule --arg 0
adb shell content call --uri content://com.neko233.vpn233.agent --method clear_rules

# 9. 规则预检诊断、局域网地址查询、清理 DNS 缓存、实时连接、日志与动态设置
adb shell content call --uri content://com.neko233.vpn233.agent --method test_match --arg api.openai.com --extra port:i:443 --extra network:s:tcp
adb shell content call --uri content://com.neko233.vpn233.agent --method flush_dns
adb shell content call --uri content://com.neko233.vpn233.agent --method lan_addresses
adb shell content call --uri content://com.neko233.vpn233.agent --method list_providers
adb shell content call --uri content://com.neko233.vpn233.agent --method connections
adb shell content call --uri content://com.neko233.vpn233.agent --method close_all_connections
adb shell content call --uri content://com.neko233.vpn233.agent --method logs
adb shell content call --uri content://com.neko233.vpn233.agent --method force_gc
adb shell content call --uri content://com.neko233.vpn233.agent --method set_setting --arg allowLan --extra value:s:true
adb shell content call --uri content://com.neko233.vpn233.agent --method set_setting --arg language --extra value:s:zh

# 10. 直接跳转指定页面或规则编辑器
adb shell am start -n com.neko233.vpn233/.MainActivity --es tab proxies
adb shell am start -n com.neko233.vpn233/.MainActivity --ez open_rules true
```

---

## 本地编译与测试

### 环境要求
- JDK 17+
- Android SDK（`compileSdk 36`、`build-tools 36.0.0`）与 Android NDK 27+
- Go 1.25+（仅重新编译 `go-core` 时需要）

### 1. 运行 Go 核心测试与编译 `libmihomo.so`

```bash
cd go-core
CGO_ENABLED=0 go test -v -tags "cmfa,with_gvisor,with_low_memory,no_easytier,no_tailscale,no_zerotier,no_fake_tcp" ./...
```

编译 `arm64-v8a` 核心库：
- **Windows**: `.\scripts\build-core.ps1`
- **Linux / macOS**: `./scripts/build-core.sh`

### 2. 运行 Android 单元测试与构建 Release APK

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Release APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

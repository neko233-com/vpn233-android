package main

/*
#include "bridge.h"
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	_ "time/tzdata"
	"unsafe"

	"github.com/dlclark/regexp2"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener/mixed"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var (
	tunLock   sync.Mutex
	tunCloser io.Closer

	mixedLock     sync.Mutex
	mixedListener *mixed.Listener

	logOnce      sync.Once
	logStreaming atomic.Bool
)

type OverrideOptions struct {
	Mode                *string  `json:"mode,omitempty"`
	IPv6                *bool    `json:"ipv6,omitempty"`
	UnifiedDelay        *bool    `json:"unifiedDelay,omitempty"`
	TCPConcurrent       *bool    `json:"tcpConcurrent,omitempty"`
	GeodataMode         *bool    `json:"geodataMode,omitempty"`
	FindProcessMode     *string  `json:"findProcessMode,omitempty"`
	AppendSystemDns     *bool    `json:"appendSystemDns,omitempty"`
	ForceDnsMapping     *bool    `json:"forceDnsMapping,omitempty"`
	Sniffing            *bool    `json:"sniffing,omitempty"`
	OverrideDestination *bool    `json:"overrideDestination,omitempty"`
	FakeIPRange         *string  `json:"fakeIpRange,omitempty"`
	NameServers         []string `json:"nameServers,omitempty"`
	FallbackNameServers []string `json:"fallbackNameServers,omitempty"`
	PrependRules        []string `json:"prependRules,omitempty"`
	LogLevel            *string  `json:"logLevel,omitempty"`
}

var defaultNameServers = []string{
	"223.5.5.5",
	"119.29.29.29",
	"8.8.4.4",
	"1.0.0.1",
}

var defaultFakeIPFilter = []string{
	"+.stun.*.*",
	"+.stun.*.*.*",
	"+.stun.*.*.*.*",
	"+.stun.*.*.*.*.*",
	"lens.l.google.com",
	"*.n.n.srv.nintendo.net",
	"+.stun.playstation.net",
	"xbox.*.*.microsoft.com",
	"*.*.xboxlive.com",
	"*.msftncsi.com",
	"*.msftconnecttest.com",
	"*.mcdn.bilivideo.cn",
	"WORKGROUP",
}

func init() {
	debug.SetMemoryLimit(128 * 1024 * 1024)
}

func startLogLoop() {
	logOnce.Do(func() {
		sub := log.Subscribe()
		go func() {
			for ev := range sub {
				if !logStreaming.Load() {
					continue
				}
				clvl := C.CString(ev.LogLevel.String())
				cmsg := C.CString(ev.Payload)
				C.c_on_log(clvl, cmsg)
				C.free(unsafe.Pointer(clvl))
				C.free(unsafe.Pointer(cmsg))
			}
		}()
	})
}

//export goInit
func goInit(homeDirC, versionC *C.char, sdkInt C.int) *C.char {
	homeDir := C.GoString(homeDirC)
	version := C.GoString(versionC)
	_ = os.MkdirAll(homeDir, 0o755)
	C2.SetHomeDir(homeDir)
	C2.Version = version

	geodata.SetMmdbUrl("https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.metadb")
	geodata.SetGeoIpUrl("https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.dat")
	geodata.SetGeoSiteUrl("https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat")
	geodata.SetASNUrl("https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/GeoLite2-ASN.mmdb")

	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		var hookErr error
		err := conn.Control(func(fd uintptr) {
			if C.c_protect_socket(C.int(fd)) == 0 {
				hookErr = errors.New("protect socket failed")
			}
		})
		if err != nil {
			return err
		}
		return hookErr
	}

	process.DefaultPackageNameResolver = func(metadata *C2.Metadata) (string, error) {
		if int(sdkInt) < 29 || metadata.RawSrcAddr == nil || metadata.RawDstAddr == nil {
			return "", process.ErrInvalidNetwork
		}
		var proto int
		switch metadata.RawSrcAddr.Network() {
		case "udp", "udp4", "udp6":
			proto = syscall.IPPROTO_UDP
		case "tcp", "tcp4", "tcp6":
			proto = syscall.IPPROTO_TCP
		default:
			return "", process.ErrInvalidNetwork
		}
		csrc := C.CString(metadata.RawSrcAddr.String())
		cdst := C.CString(metadata.RawDstAddr.String())
		cpkg := C.c_query_socket_pkg(C.int(proto), csrc, cdst)
		C.free(unsafe.Pointer(csrc))
		C.free(unsafe.Pointer(cdst))
		if cpkg == nil {
			return "", process.ErrNotFound
		}
		pkg := C.GoString(cpkg)
		C.free(unsafe.Pointer(cpkg))
		if pkg == "" {
			return "", process.ErrNotFound
		}
		return pkg, nil
	}

	startLogLoop()
	return C.CString("")
}

func resolveAndPatchConfig(profileDir string, overrideJson string) (*config.RawConfig, error) {
	configPath := filepath.Join(profileDir, "config.yaml")
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("read config.yaml: %w", err)
	}
	rawCfg, err := config.UnmarshalRawConfig(data)
	if err != nil {
		return nil, fmt.Errorf("parse yaml: %w", err)
	}

	var ov OverrideOptions
	if strings.TrimSpace(overrideJson) != "" {
		if err := json.Unmarshal([]byte(overrideJson), &ov); err != nil {
			log.Warnln("Invalid override json: %s", err.Error())
		}
	}

	rawCfg.ExternalController = ""
	rawCfg.ExternalControllerTLS = ""
	rawCfg.Port = 0
	rawCfg.SocksPort = 0
	rawCfg.RedirPort = 0
	rawCfg.TProxyPort = 0
	rawCfg.MixedPort = 0
	rawCfg.Interface = ""
	rawCfg.RoutingMark = 0
	rawCfg.Tun.Enable = false
	rawCfg.Tun.AutoRoute = false
	rawCfg.Tun.AutoDetectInterface = false
	rawCfg.Profile.StoreSelected = true
	rawCfg.Profile.StoreFakeIP = true

	// Apply user UI overrides
	if ov.Mode != nil {
		if m, ok := tunnel.ModeMapping[strings.ToLower(*ov.Mode)]; ok {
			rawCfg.Mode = m
		}
	}
	if ov.IPv6 != nil {
		rawCfg.IPv6 = *ov.IPv6
	}
	if ov.UnifiedDelay != nil {
		rawCfg.UnifiedDelay = *ov.UnifiedDelay
	}
	if ov.TCPConcurrent != nil {
		rawCfg.TCPConcurrent = *ov.TCPConcurrent
	}
	if ov.GeodataMode != nil {
		rawCfg.GeodataMode = *ov.GeodataMode
	}
	if ov.FindProcessMode != nil {
		_ = rawCfg.FindProcessMode.Set(*ov.FindProcessMode)
	}
	if ov.LogLevel != nil {
		if lvl, ok := log.LogLevelMapping[strings.ToLower(*ov.LogLevel)]; ok {
			rawCfg.LogLevel = lvl
		}
	}
	if ov.Sniffing != nil {
		rawCfg.Sniffer.Enable = *ov.Sniffing
	}
	if ov.OverrideDestination != nil {
		rawCfg.Sniffer.OverrideDest = *ov.OverrideDestination
	}

	forceDns := ov.ForceDnsMapping != nil && *ov.ForceDnsMapping
	if !rawCfg.DNS.Enable || forceDns {
		if !rawCfg.DNS.Enable {
			rawCfg.DNS = config.DefaultRawConfig().DNS
			rawCfg.DNS.Enable = true
			rawCfg.DNS.NameServer = append([]string{}, defaultNameServers...)
			rawCfg.DNS.FakeIPFilter = append([]string{}, defaultFakeIPFilter...)
		}
		rawCfg.DNS.EnhancedMode = C2.DNSFakeIP
		if ov.FakeIPRange != nil && *ov.FakeIPRange != "" {
			rawCfg.DNS.FakeIPRange = *ov.FakeIPRange
		} else if rawCfg.DNS.FakeIPRange == "" {
			rawCfg.DNS.FakeIPRange = "28.0.0.0/8"
		}
	}
	if len(ov.NameServers) > 0 {
		rawCfg.DNS.NameServer = append([]string{}, ov.NameServers...)
	}
	if len(ov.FallbackNameServers) > 0 {
		rawCfg.DNS.Fallback = append([]string{}, ov.FallbackNameServers...)
	}

	appendSysDns := rawCfg.ClashForAndroid.AppendSystemDNS
	if ov.AppendSystemDns != nil {
		appendSysDns = *ov.AppendSystemDns
	}
	if appendSysDns {
		hasSystem := false
		for _, ns := range rawCfg.DNS.NameServer {
			if strings.EqualFold(strings.TrimSpace(ns), "system://") {
				hasSystem = true
				break
			}
		}
		if !hasSystem {
			rawCfg.DNS.NameServer = append(rawCfg.DNS.NameServer, "system://")
		}
	}

	if len(ov.PrependRules) > 0 {
		rawCfg.Rule = append(append([]string{}, ov.PrependRules...), rawCfg.Rule...)
	}

	// Remove unsupported raw listeners
	if len(rawCfg.Listeners) > 0 {
		filtered := make([]map[string]any, 0, len(rawCfg.Listeners))
		for _, l := range rawCfg.Listeners {
			if t, ok := l["type"].(string); ok {
				switch strings.ToLower(t) {
				case "tproxy", "redir", "tun":
					continue
				}
			}
			filtered = append(filtered, l)
		}
		rawCfg.Listeners = filtered
	}

	// Scope provider paths safely inside profileDir/providers/
	patchProviderMap := func(m map[string]map[string]any, prefix string) {
		for _, item := range m {
			p, _ := item["path"].(string)
			if p != "" {
				cleaned := strings.TrimLeft(filepath.ToSlash(filepath.Clean(p)), "./")
				item["path"] = filepath.Join(profileDir, "providers", cleaned)
			} else if urlStr, ok := item["url"].(string); ok && urlStr != "" {
				item["path"] = filepath.Join(profileDir, "providers", prefix, utils.MakeHash([]byte(urlStr)).String())
			}
		}
	}
	patchProviderMap(rawCfg.ProxyProvider, "proxy")
	patchProviderMap(rawCfg.RuleProvider, "rule")

	if len(rawCfg.Proxy) == 0 && len(rawCfg.ProxyProvider) == 0 {
		return nil, errors.New("profile contains neither proxies nor proxy-providers")
	}
	if rawCfg.ClashForAndroid.UiSubtitlePattern != "" {
		if _, err := regexp2.Compile(rawCfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
			return nil, fmt.Errorf("invalid ui-subtitle-pattern: %w", err)
		}
	}
	return rawCfg, nil
}

//export goValidateConfig
func goValidateConfig(profileDirC *C.char) *C.char {
	profileDir := C.GoString(profileDirC)
	rawCfg, err := resolveAndPatchConfig(profileDir, "")
	if err != nil {
		return C.CString(err.Error())
	}
	if _, err := config.ParseRawConfig(rawCfg); err != nil {
		return C.CString(err.Error())
	}
	runtime.GC()
	return C.CString("")
}

//export goLoadConfig
func goLoadConfig(profileDirC, overrideJsonC *C.char) *C.char {
	profileDir := C.GoString(profileDirC)
	overrideJson := C.GoString(overrideJsonC)
	rawCfg, err := resolveAndPatchConfig(profileDir, overrideJson)
	if err != nil {
		return C.CString(err.Error())
	}
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		return C.CString(err.Error())
	}
	executor.ApplyConfig(cfg, true)
	runtime.GC()
	return C.CString("")
}

//export goStartTun
func goStartTun(fd C.int, stackC, gatewayC, dnsC *C.char, mtu C.int) *C.char {
	tunLock.Lock()
	defer tunLock.Unlock()

	if tunCloser != nil {
		_ = tunCloser.Close()
		tunCloser = nil
	}

	stackStr := strings.ToLower(strings.TrimSpace(C.GoString(stackC)))
	tunStack, ok := C2.StackTypeMapping[stackStr]
	if !ok {
		tunStack = C2.TunSystem
	}

	var prefix4 []netip.Prefix
	var prefix6 []netip.Prefix
	for _, part := range strings.Split(C.GoString(gatewayC), ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		pfx, err := netip.ParsePrefix(part)
		if err != nil {
			return C.CString("invalid gateway prefix: " + err.Error())
		}
		if pfx.Addr().Is4() {
			prefix4 = append(prefix4, pfx)
		} else {
			prefix6 = append(prefix6, pfx)
		}
	}

	var dnsHijack []string
	for _, part := range strings.Split(C.GoString(dnsC), ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		dnsHijack = append(dnsHijack, net.JoinHostPort(part, "53"))
	}

	m := uint32(mtu)
	if m < 1280 {
		m = 9000
	}

	options := LC.Tun{
		Enable:              true,
		Device:              sing_tun.InterfaceName,
		Stack:               tunStack,
		DNSHijack:           dnsHijack,
		AutoRoute:           false,
		AutoDetectInterface: false,
		Inet4Address:        prefix4,
		Inet6Address:        prefix6,
		MTU:                 m,
		UDPTimeout:          60,
		FileDescriptor:      int(fd),
	}

	l, err := sing_tun.New(options, tunnel.Tunnel)
	if err != nil {
		return C.CString(err.Error())
	}
	tunCloser = l
	return C.CString("")
}

//export goStopTun
func goStopTun() {
	tunLock.Lock()
	defer tunLock.Unlock()
	if tunCloser != nil {
		_ = tunCloser.Close()
		tunCloser = nil
	}
}

//export goStartMixed
func goStartMixed(listenAddrC *C.char) *C.char {
	mixedLock.Lock()
	defer mixedLock.Unlock()

	if mixedListener != nil {
		_ = mixedListener.Close()
		mixedListener = nil
	}
	addr := strings.TrimSpace(C.GoString(listenAddrC))
	if addr == "" {
		return C.CString("")
	}
	l, err := mixed.New(addr, tunnel.Tunnel)
	if err != nil {
		return C.CString("ERR:" + err.Error())
	}
	mixedListener = l
	return C.CString(l.Address())
}

//export goStopMixed
func goStopMixed() {
	mixedLock.Lock()
	defer mixedLock.Unlock()
	if mixedListener != nil {
		_ = mixedListener.Close()
		mixedListener = nil
	}
}

//export goReset
func goReset() {
	goStopTun()
	goStopMixed()
	cfg, err := config.Parse([]byte{})
	if err == nil {
		executor.ApplyConfig(cfg, true)
	}
	statistic.DefaultManager.ResetStatistic()
	runtime.GC()
	debug.FreeOSMemory()
}

//export goSetMode
func goSetMode(modeC *C.char) {
	if m, ok := tunnel.ModeMapping[strings.ToLower(strings.TrimSpace(C.GoString(modeC)))]; ok {
		tunnel.SetMode(m)
	}
}

//export goGetMode
func goGetMode() *C.char {
	return C.CString(tunnel.Mode().String())
}

type proxyNodeDTO struct {
	Name    string `json:"name"`
	Type    string `json:"type"`
	Delay   int    `json:"delay"`
	IsGroup bool   `json:"isGroup"`
	Alive   bool   `json:"alive"`
}

type proxyGroupDTO struct {
	Name       string         `json:"name"`
	Type       string         `json:"type"`
	Now        string         `json:"now"`
	Icon       string         `json:"icon"`
	Selectable bool           `json:"selectable"`
	Proxies    []proxyNodeDTO `json:"proxies"`
}

type groupsPayloadDTO struct {
	Mode   string          `json:"mode"`
	Groups []proxyGroupDTO `json:"groups"`
}

func resolveLastDelay(p C2.Proxy, testUrl string) (int, bool) {
	alive := false
	if testUrl != "" && p.AliveForTestUrl(testUrl) {
		d := p.LastDelayForTestUrl(testUrl)
		if d > 0 && d != 0xffff {
			return int(d), true
		}
		alive = true
	}
	for _, h := range p.DelayHistory() {
		if h.Delay > 0 {
			return int(h.Delay), true
		}
	}
	for urlKey, st := range p.ExtraDelayHistories() {
		if st.Alive && len(st.History) > 0 {
			last := st.History[len(st.History)-1]
			if last.Delay > 0 {
				return int(last.Delay), true
			}
			_ = urlKey
		}
	}
	return -1, alive
}

//export goQueryGroupsJson
func goQueryGroupsJson(testUrlC *C.char) *C.char {
	testUrl := strings.TrimSpace(C.GoString(testUrlC))
	if testUrl == "" {
		testUrl = "https://www.gstatic.com/generate_204"
	}
	mode := tunnel.Mode()
	allProxies := tunnel.Proxies()
	payload := groupsPayloadDTO{
		Mode:   mode.String(),
		Groups: []proxyGroupDTO{},
	}
	if mode == tunnel.Direct {
		b, _ := json.Marshal(payload)
		return C.CString(string(b))
	}

	orderedNames := make([]string, 0, 16)
	if mode == tunnel.Global {
		orderedNames = append(orderedNames, "GLOBAL")
	}
	if globalProxy := allProxies["GLOBAL"]; globalProxy != nil {
		if g, ok := globalProxy.Adapter().(outboundgroup.ProxyGroup); ok && len(g.Providers()) > 0 {
			for _, px := range g.Providers()[0].Proxies() {
				if subGroup, isGrp := px.Adapter().(outboundgroup.ProxyGroup); isGrp && !subGroup.Hidden() {
					orderedNames = append(orderedNames, px.Name())
				}
			}
		}
	}

	for _, name := range orderedNames {
		p := allProxies[name]
		if p == nil {
			continue
		}
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}
		children := g.Proxies()
		nodes := make([]proxyNodeDTO, 0, len(children))
		for _, child := range children {
			_, isGrp := child.Adapter().(outboundgroup.ProxyGroup)
			delay, alive := resolveLastDelay(child, testUrl)
			nodes = append(nodes, proxyNodeDTO{
				Name:    child.Name(),
				Type:    child.Type().String(),
				Delay:   delay,
				IsGroup: isGrp,
				Alive:   alive,
			})
		}
		payload.Groups = append(payload.Groups, proxyGroupDTO{
			Name:       p.Name(),
			Type:       p.Type().String(),
			Now:        g.Now(),
			Icon:       g.Icon(),
			Selectable: p.Type() == C2.Selector,
			Proxies:    nodes,
		})
	}
	b, _ := json.Marshal(payload)
	return C.CString(string(b))
}

//export goSelectProxy
func goSelectProxy(groupC, proxyC *C.char) C.int {
	groupName := C.GoString(groupC)
	proxyName := C.GoString(proxyC)
	p := tunnel.Proxies()[groupName]
	if p == nil {
		return 0
	}
	s, ok := p.Adapter().(outboundgroup.SelectAble)
	if !ok {
		return 0
	}
	if err := s.Set(proxyName); err != nil {
		return 0
	}
	// Close connections routed via this group so new selection takes effect immediately
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		for _, ch := range c.Info().Chain {
			if ch == groupName {
				_ = c.Close()
				break
			}
		}
		return true
	})
	return 1
}

//export goTestGroupDelay
func goTestGroupDelay(groupC, testUrlC *C.char, timeoutMs C.int) *C.char {
	groupName := C.GoString(groupC)
	testUrl := strings.TrimSpace(C.GoString(testUrlC))
	if testUrl == "" {
		testUrl = "https://www.gstatic.com/generate_204"
	}
	tmo := int(timeoutMs)
	if tmo <= 0 {
		tmo = 4000
	}
	p := tunnel.Proxies()[groupName]
	if p == nil {
		return C.CString("{}")
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return C.CString("{}")
	}
	expected, _ := utils.NewUnsignedRanges[uint16]("")
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(tmo)*time.Millisecond)
	defer cancel()
	delays, _ := g.URLTest(ctx, testUrl, expected)
	if delays == nil {
		delays = map[string]uint16{}
	}
	b, _ := json.Marshal(delays)
	return C.CString(string(b))
}

//export goTestProxyDelay
func goTestProxyDelay(proxyC, testUrlC *C.char, timeoutMs C.int) C.int {
	proxyName := C.GoString(proxyC)
	testUrl := strings.TrimSpace(C.GoString(testUrlC))
	if testUrl == "" {
		testUrl = "https://www.gstatic.com/generate_204"
	}
	tmo := int(timeoutMs)
	if tmo <= 0 {
		tmo = 4000
	}
	p := tunnel.Proxies()[proxyName]
	if p == nil {
		return -1
	}
	expected, _ := utils.NewUnsignedRanges[uint16]("")
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(tmo)*time.Millisecond)
	defer cancel()
	delay, err := p.URLTest(ctx, testUrl, expected)
	if err != nil || delay == 0 {
		return -1
	}
	return C.int(delay)
}

//export goQueryTrafficJson
func goQueryTrafficJson() *C.char {
	upNow, downNow := statistic.DefaultManager.Now()
	upTotal, downTotal := statistic.DefaultManager.Total()
	mem := statistic.DefaultManager.Memory()
	resp := struct {
		UpNow     int64  `json:"upNow"`
		DownNow   int64  `json:"downNow"`
		UpTotal   int64  `json:"upTotal"`
		DownTotal int64  `json:"downTotal"`
		Memory    uint64 `json:"memory"`
	}{upNow, downNow, upTotal, downTotal, mem}
	b, _ := json.Marshal(resp)
	return C.CString(string(b))
}

//export goQueryConnectionsJson
func goQueryConnectionsJson() *C.char {
	snap := statistic.DefaultManager.Snapshot()
	b, _ := json.Marshal(snap)
	return C.CString(string(b))
}

//export goCloseConnection
func goCloseConnection(idC *C.char) C.int {
	id := C.GoString(idC)
	if c := statistic.DefaultManager.Get(id); c != nil {
		_ = c.Close()
		return 1
	}
	return 0
}

//export goCloseAllConnections
func goCloseAllConnections() {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
}

type providerDTO struct {
	Name        string `json:"name"`
	Kind        string `json:"kind"`
	VehicleType string `json:"vehicleType"`
	Count       int    `json:"count"`
	UpdatedAt   int64  `json:"updatedAt"`
}

type updatableProvider interface {
	UpdatedAt() time.Time
}

//export goQueryProvidersJson
func goQueryProvidersJson() *C.char {
	res := make([]providerDTO, 0, 8)
	for _, p := range tunnel.Providers() {
		if p.VehicleType() == provider.Compatible {
			continue
		}
		var ts int64
		if u, ok := p.(updatableProvider); ok && !u.UpdatedAt().IsZero() {
			ts = u.UpdatedAt().UnixMilli()
		}
		res = append(res, providerDTO{
			Name:        p.Name(),
			Kind:        "Proxy",
			VehicleType: p.VehicleType().String(),
			Count:       p.Count(),
			UpdatedAt:   ts,
		})
	}
	for _, r := range tunnel.RuleProviders() {
		if r.VehicleType() == provider.Compatible {
			continue
		}
		var ts int64
		if u, ok := r.(updatableProvider); ok && !u.UpdatedAt().IsZero() {
			ts = u.UpdatedAt().UnixMilli()
		}
		res = append(res, providerDTO{
			Name:        r.Name(),
			Kind:        "Rule",
			VehicleType: r.VehicleType().String(),
			Count:       r.Count(),
			UpdatedAt:   ts,
		})
	}
	b, _ := json.Marshal(res)
	return C.CString(string(b))
}

//export goUpdateProvider
func goUpdateProvider(kindC, nameC *C.char) *C.char {
	kind := C.GoString(kindC)
	name := C.GoString(nameC)
	switch kind {
	case "Proxy":
		p := tunnel.Providers()[name]
		if p == nil {
			return C.CString("proxy provider not found: " + name)
		}
		if err := p.Update(); err != nil {
			return C.CString(err.Error())
		}
	case "Rule":
		r := tunnel.RuleProviders()[name]
		if r == nil {
			return C.CString("rule provider not found: " + name)
		}
		if err := r.Update(); err != nil {
			return C.CString(err.Error())
		}
	default:
		return C.CString("unknown provider kind: " + kind)
	}
	return C.CString("")
}

//export goNotifyDnsChanged
func goNotifyDnsChanged(dnsCsvC *C.char) {
	csv := strings.TrimSpace(C.GoString(dnsCsvC))
	var addrs []string
	if csv != "" {
		for _, item := range strings.Split(csv, ",") {
			item = strings.TrimSpace(item)
			if item != "" {
				addrs = append(addrs, item)
			}
		}
	}
	dns.UpdateSystemDNS(addrs)
	dns.FlushCacheWithDefaultResolver()
}

//export goNotifyTimeZoneChanged
func goNotifyTimeZoneChanged(tzNameC *C.char, offsetSec C.int) {
	time.Local = time.FixedZone(C.GoString(tzNameC), int(offsetSec))
}

//export goSetLogLevel
func goSetLogLevel(levelC *C.char) {
	if lvl, ok := log.LogLevelMapping[strings.ToLower(strings.TrimSpace(C.GoString(levelC)))]; ok {
		log.SetLevel(lvl)
	}
}

//export goSetLogStreaming
func goSetLogStreaming(enabled C.int) {
	logStreaming.Store(enabled != 0)
}

//export goForceGc
func goForceGc() {
	runtime.GC()
	debug.FreeOSMemory()
}

//export goSetIdleMode
func goSetIdleMode(idle C.int) {
	if idle != 0 {
		if !logStreaming.Load() {
			log.SetLevel(log.WARNING)
		}
		runtime.GC()
		debug.FreeOSMemory()
	}
}

//export goCoreVersion
func goCoreVersion() *C.char {
	return C.CString("Mihomo " + C2.Version + " (Go " + runtime.Version() + ", API " + strconv.Itoa(runtime.NumCPU()) + "c)")
}

func main() {}

//go:build android

package main

/*
#include "bridge.h"
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
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

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/features"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/hub/executor"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/mixed"
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

//export goValidateConfigWithOverride
func goValidateConfigWithOverride(profileDirC, overrideJsonC *C.char) *C.char {
	profileDir := C.GoString(profileDirC)
	overrideJson := C.GoString(overrideJsonC)
	rawCfg, err := resolveAndPatchConfig(profileDir, overrideJson)
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
	applyConfigAndCloseConnections(cfg)
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
	setModeAndCloseConnections(C.GoString(modeC))
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
	LeafNow    string         `json:"leafNow"`
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
	for _, st := range p.ExtraDelayHistories() {
		if st.Alive && len(st.History) > 0 {
			last := st.History[len(st.History)-1]
			if last.Delay > 0 {
				return int(last.Delay), true
			}
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
			LeafNow:    resolveLeafNow(allProxies, p.Name()),
			Icon:       g.Icon(),
			Selectable: p.Type() == C2.Selector,
			Proxies:    nodes,
		})
	}
	b, _ := json.Marshal(payload)
	return C.CString(string(b))
}

//export goMatchRuleJson
func goMatchRuleJson(hostC, dstIpC, dstPortC, networkC, processC *C.char) *C.char {
	res := matchRuleForQuery(
		C.GoString(hostC),
		C.GoString(dstIpC),
		C.GoString(dstPortC),
		C.GoString(networkC),
		C.GoString(processC),
	)
	b, _ := json.Marshal(res)
	return C.CString(string(b))
}

//export goFlushDnsCache
func goFlushDnsCache() *C.char {
	dns.FlushCacheWithDefaultResolver()
	if err := flushDnsAndFakeIPInternal(); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("")
}

//export goQueryRulesJson
func goQueryRulesJson() *C.char {
	res := buildRulesDTO(tunnel.Rules())
	b, _ := json.Marshal(res)
	return C.CString(string(b))
}

//export goSelectProxy
func goSelectProxy(groupC, proxyC *C.char) C.int {
	if selectProxyAndCloseConnections(C.GoString(groupC), C.GoString(proxyC)) {
		return 1
	}
	return 0
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
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	connCount := 0
	statistic.DefaultManager.Range(func(statistic.Tracker) bool {
		connCount++
		return true
	})
	resp := struct {
		UpNow     int64  `json:"upNow"`
		DownNow   int64  `json:"downNow"`
		UpTotal   int64  `json:"upTotal"`
		DownTotal int64  `json:"downTotal"`
		Memory    uint64 `json:"memory"`
		HeapBytes uint64 `json:"heapBytes"`
		ConnCount int    `json:"connCount"`
	}{upNow, downNow, upTotal, downTotal, mem, ms.HeapInuse, connCount}
	b, _ := json.Marshal(resp)
	return C.CString(string(b))
}

//export goQueryRatesJson
func goQueryRatesJson() *C.char {
	up, down := statistic.DefaultManager.Now()
	b, _ := json.Marshal(struct {
		UpNow   int64 `json:"upNow"`
		DownNow int64 `json:"downNow"`
	}{up, down})
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
	closeAllConnectionsInternal()
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
	debug.FreeOSMemory()
}

//export goSetIdleMode
func goSetIdleMode(idle C.int) {
	if idle != 0 {
		if !logStreaming.Load() && log.Level() < log.WARNING {
			log.SetLevel(log.WARNING)
		}
	}
}

//export goCoreVersion
func goCoreVersion() *C.char {
	return C.CString("Mihomo " + C2.Version + " (Go " + runtime.Version() + ", API " + strconv.Itoa(runtime.NumCPU()) + "c; tags=" + strings.Join(features.Tags(), ",") + ")")
}

func main() {}

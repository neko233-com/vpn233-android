package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/dlclark/regexp2"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/orderedmap"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	"go.yaml.in/yaml/v3"
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
	AppendRules         []string `json:"appendRules,omitempty"`
	FakeIPFilterExtra   []string `json:"fakeIpFilterExtra,omitempty"`
	LogLevel            *string  `json:"logLevel,omitempty"`
	GlobalMergeYaml     *string  `json:"globalMergeYaml,omitempty"`
	MergeYaml           *string  `json:"mergeYaml,omitempty"`
}

type MergeConfig struct {
	PrependRules       []string                              `yaml:"prepend-rules"`
	AppendRules        []string                              `yaml:"append-rules"`
	PrependProxies     []map[string]any                      `yaml:"prepend-proxies"`
	AppendProxies      []map[string]any                      `yaml:"append-proxies"`
	PrependProxyGroups []map[string]any                      `yaml:"prepend-proxy-groups"`
	AppendProxyGroups  []map[string]any                      `yaml:"append-proxy-groups"`
	Rules              []string                              `yaml:"rules"`
	NameServers        []string                              `yaml:"nameserver"`
	FakeIPFilter       []string                              `yaml:"fake-ip-filter"`
	NameServerPolicy   *orderedmap.OrderedMap[string, any]   `yaml:"nameserver-policy"`
}

type matchResultDTO struct {
	Matched    bool     `json:"matched"`
	Index      int      `json:"index"`
	Mode       string   `json:"mode"`
	RuleType   string   `json:"ruleType"`
	Payload    string   `json:"payload"`
	Adapter    string   `json:"adapter"`
	Raw        string   `json:"raw"`
	ResolvedIP string   `json:"resolvedIp"`
	Chain      []string `json:"chain"`
}

type ruleDTO struct {
	Index   int    `json:"index"`
	Type    string `json:"type"`
	Payload string `json:"payload"`
	Adapter string `json:"adapter"`
	Raw     string `json:"raw"`
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
	"connectivitycheck.gstatic.com",
	"captive.apple.com",
	"*.mcdn.bilivideo.cn",
	"WORKGROUP",
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

	if ov.Mode != nil {
		if m, ok := tunnel.ModeMapping[strings.ToLower(*ov.Mode)]; ok {
			rawCfg.Mode = m
		}
	}
	if ov.IPv6 != nil {
		rawCfg.IPv6 = *ov.IPv6
		resolver.DisableIPv6 = !(*ov.IPv6)
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

	if len(ov.FakeIPFilterExtra) > 0 {
		rawCfg.DNS.FakeIPFilter = append(rawCfg.DNS.FakeIPFilter, ov.FakeIPFilterExtra...)
	}

	cleanNonBlank := func(list []string) []string {
		out := make([]string, 0, len(list))
		for _, item := range list {
			if trimmed := strings.TrimSpace(item); trimmed != "" {
				out = append(out, trimmed)
			}
		}
		return out
	}

	var globalPrepend, globalAppend, profilePrepend, profileAppend []string
	var errMerge error
	if ov.GlobalMergeYaml != nil && strings.TrimSpace(*ov.GlobalMergeYaml) != "" {
		globalPrepend, globalAppend, errMerge = applyMergeYamlSpec(rawCfg, ov.NameServers, *ov.GlobalMergeYaml, cleanNonBlank)
		if errMerge != nil {
			return nil, fmt.Errorf("parse global merge yaml: %w", errMerge)
		}
	}
	if ov.MergeYaml != nil && strings.TrimSpace(*ov.MergeYaml) != "" {
		profilePrepend, profileAppend, errMerge = applyMergeYamlSpec(rawCfg, ov.NameServers, *ov.MergeYaml, cleanNonBlank)
		if errMerge != nil {
			return nil, fmt.Errorf("parse merge yaml: %w", errMerge)
		}
	}

	prependRules := append(cleanNonBlank(ov.PrependRules), profilePrepend...)
	prependRules = append(prependRules, globalPrepend...)
	if len(prependRules) > 0 {
		rawCfg.Rule = append(prependRules, rawCfg.Rule...)
	}
	appendRules := append([]string{}, globalAppend...)
	appendRules = append(appendRules, profileAppend...)
	appendRules = append(appendRules, cleanNonBlank(ov.AppendRules)...)
	if len(appendRules) > 0 {
		rawCfg.Rule = insertRulesBeforeMatch(rawCfg.Rule, appendRules)
	}

	rawCfg.ProxyGroup = sanitizeBuiltinShadowGroups(rawCfg.ProxyGroup)
	rawCfg.Rule = filterUnavailableGeoSiteRules(rawCfg.Rule)

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

func buildRulesDTO(rules []C2.Rule) []ruleDTO {
	res := make([]ruleDTO, 0, len(rules))
	for i, r := range rules {
		t := r.RuleType().String()
		p := r.Payload()
		a := r.Adapter()
		raw := fmt.Sprintf("%s,%s", t, a)
		if p != "" {
			raw = fmt.Sprintf("%s,%s,%s", t, p, a)
		}
		res = append(res, ruleDTO{
			Index:   i,
			Type:    t,
			Payload: p,
			Adapter: a,
			Raw:     raw,
		})
	}
	return res
}

func applyMergeYamlSpec(
	rawCfg *config.RawConfig,
	overrideNameServers []string,
	yamlText string,
	cleanNonBlank func([]string) []string,
) (prependRules []string, appendRules []string, err error) {
	var spec MergeConfig
	if err := yaml.Unmarshal([]byte(yamlText), &spec); err != nil {
		return nil, nil, err
	}
	if len(spec.PrependProxies) > 0 {
		rawCfg.Proxy = append(append([]map[string]any{}, spec.PrependProxies...), rawCfg.Proxy...)
	}
	if len(spec.AppendProxies) > 0 {
		rawCfg.Proxy = append(rawCfg.Proxy, spec.AppendProxies...)
	}
	if len(spec.PrependProxyGroups) > 0 {
		rawCfg.ProxyGroup = append(append([]map[string]any{}, spec.PrependProxyGroups...), rawCfg.ProxyGroup...)
	}
	if len(spec.AppendProxyGroups) > 0 {
		rawCfg.ProxyGroup = append(rawCfg.ProxyGroup, spec.AppendProxyGroups...)
	}
	if mRules := cleanNonBlank(spec.Rules); len(mRules) > 0 {
		rawCfg.Rule = mRules
	}
	if len(overrideNameServers) == 0 && len(spec.NameServers) > 0 {
		rawCfg.DNS.NameServer = cleanNonBlank(spec.NameServers)
	}
	if extraFilters := cleanNonBlank(spec.FakeIPFilter); len(extraFilters) > 0 {
		seen := make(map[string]bool, len(rawCfg.DNS.FakeIPFilter))
		for _, f := range rawCfg.DNS.FakeIPFilter {
			seen[f] = true
		}
		for _, f := range extraFilters {
			if !seen[f] {
				rawCfg.DNS.FakeIPFilter = append(rawCfg.DNS.FakeIPFilter, f)
				seen[f] = true
			}
		}
	}
	if spec.NameServerPolicy != nil && spec.NameServerPolicy.Len() > 0 {
		if rawCfg.DNS.NameServerPolicy == nil {
			rawCfg.DNS.NameServerPolicy = orderedmap.New[string, any]()
		}
		for pair := spec.NameServerPolicy.Oldest(); pair != nil; pair = pair.Next() {
			rawCfg.DNS.NameServerPolicy.Set(pair.Key, pair.Value)
		}
	}
	return cleanNonBlank(spec.PrependRules), cleanNonBlank(spec.AppendRules), nil
}

// Drop shadowed reserved adapter groups (e.g. legacy subconverter `- name: DIRECT / proxies: [DIRECT]`)
// that trigger Mihomo's ProxyGroup cycle check.
func sanitizeBuiltinShadowGroups(groups []map[string]any) []map[string]any {
	if len(groups) == 0 {
		return groups
	}
	reserved := map[string]bool{
		"DIRECT":      true,
		"REJECT":      true,
		"REJECT-DROP": true,
		"PASS":        true,
		"COMPATIBLE":  true,
	}
	out := make([]map[string]any, 0, len(groups))
	for _, g := range groups {
		name, _ := g["name"].(string)
		if reserved[strings.ToUpper(strings.TrimSpace(name))] {
			continue
		}
		out = append(out, g)
	}
	return out
}

// Skip GEOSITE rules whose category is missing in the bundled lite geosite.dat so
// offline imports and first-time VPN startup do not abort before GeoDB upgrade.
func filterUnavailableGeoSiteRules(rules []string) []string {
	hasGeoSite := false
	for _, r := range rules {
		if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(r)), "GEOSITE,") {
			hasGeoSite = true
			break
		}
	}
	if !hasGeoSite {
		return rules
	}
	if _, err := os.Stat(C2.Path.GeoSite()); err != nil {
		return rules
	}
	if err := geodata.InitGeoSite(); err != nil {
		return rules
	}
	out := make([]string, 0, len(rules))
	for _, r := range rules {
		trimmed := strings.TrimSpace(r)
		parts := strings.Split(trimmed, ",")
		if len(parts) >= 2 && strings.EqualFold(strings.TrimSpace(parts[0]), "GEOSITE") {
			code := strings.TrimSpace(parts[1])
			if _, err := geodata.LoadGeoSiteMatcher(code); err != nil {
				log.Warnln("Skipping unavailable GEOSITE rule %q with current geosite.dat: %v", code, err)
				continue
			}
		}
		out = append(out, r)
	}
	return out
}

func insertRulesBeforeMatch(existing []string, toInsert []string) []string {
	matchIdx := -1
	for i := len(existing) - 1; i >= 0; i-- {
		upper := strings.ToUpper(strings.TrimSpace(existing[i]))
		if strings.HasPrefix(upper, "MATCH,") || upper == "MATCH" {
			matchIdx = i
			break
		}
	}
	if matchIdx >= 0 {
		updated := make([]string, 0, len(existing)+len(toInsert))
		updated = append(updated, existing[:matchIdx]...)
		updated = append(updated, toInsert...)
		updated = append(updated, existing[matchIdx:]...)
		return updated
	}
	return append(append([]string{}, existing...), toInsert...)
}

func resolveLeafNow(allProxies map[string]C2.Proxy, groupName string) string {
	cur := groupName
	seen := make(map[string]bool, 8)
	for i := 0; i < 16; i++ {
		if seen[cur] {
			break
		}
		seen[cur] = true
		p := allProxies[cur]
		if p == nil {
			break
		}
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			break
		}
		next := g.Now()
		if next == "" || next == cur {
			break
		}
		cur = next
	}
	return cur
}

func resolveAdapterChain(allProxies map[string]C2.Proxy, startAdapter string) []string {
	if startAdapter == "" {
		return nil
	}
	chain := []string{startAdapter}
	cur := startAdapter
	seen := map[string]bool{startAdapter: true}
	for i := 0; i < 16; i++ {
		p := allProxies[cur]
		if p == nil {
			break
		}
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			break
		}
		next := g.Now()
		if next == "" || seen[next] {
			break
		}
		seen[next] = true
		chain = append(chain, next)
		cur = next
	}
	return chain
}

func matchRuleForQuery(host, dstIp, dstPort, network, proc string) matchResultDTO {
	mode := tunnel.Mode()
	allProxies := tunnel.Proxies()
	res := matchResultDTO{
		Mode: mode.String(),
	}
	if mode == tunnel.Direct {
		res.Matched = true
		res.Index = -1
		res.RuleType = "ModeDirect"
		res.Adapter = "DIRECT"
		res.Raw = "MODE,DIRECT"
		res.Chain = []string{"DIRECT"}
		return res
	}
	if mode == tunnel.Global {
		res.Matched = true
		res.Index = -1
		res.RuleType = "ModeGlobal"
		res.Adapter = "GLOBAL"
		res.Raw = "MODE,GLOBAL"
		res.Chain = resolveAdapterChain(allProxies, "GLOBAL")
		return res
	}

	netType := C2.TCP
	if strings.EqualFold(strings.TrimSpace(network), "udp") {
		netType = C2.UDP
	}
	var portNum uint16 = 443
	if pVal, err := strconv.Atoi(strings.TrimSpace(dstPort)); err == nil && pVal > 0 && pVal <= 65535 {
		portNum = uint16(pVal)
	} else if netType == C2.UDP {
		portNum = 53
	}
	var parsedIp netip.Addr
	if ipTrim := strings.TrimSpace(dstIp); ipTrim != "" {
		if ip, err := netip.ParseAddr(ipTrim); err == nil {
			parsedIp = ip
		}
	}
	hostTrim := strings.TrimSpace(host)
	if !parsedIp.IsValid() && hostTrim != "" {
		if ip, err := netip.ParseAddr(hostTrim); err == nil {
			parsedIp = ip
			hostTrim = ""
		}
	}

	meta := C2.Metadata{
		NetWork: netType,
		Host:    hostTrim,
		DstIP:   parsedIp,
		DstPort: portNum,
		Process: strings.TrimSpace(proc),
	}
	resolved := false
	helper := C2.RuleMatchHelper{
		ResolveIP: func() {
			if resolved || meta.Host == "" || meta.DstIP.IsValid() {
				return
			}
			resolved = true
			ctx, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
			defer cancel()
			if ip, err := resolver.ResolveIP(ctx, meta.Host); err == nil && ip.IsValid() {
				meta.DstIP = ip
				res.ResolvedIP = ip.String()
			}
		},
		FindProcess:   func() {},
		CheckPassRule: func(string) bool { return false },
	}

	for i, rule := range tunnel.Rules() {
		if ok, adapter := rule.Match(&meta, helper); ok {
			t := rule.RuleType().String()
			pl := rule.Payload()
			if adapter == "" {
				adapter = rule.Adapter()
			}
			raw := fmt.Sprintf("%s,%s", t, adapter)
			if pl != "" {
				raw = fmt.Sprintf("%s,%s,%s", t, pl, adapter)
			}
			res.Matched = true
			res.Index = i
			res.RuleType = t
			res.Payload = pl
			res.Adapter = adapter
			res.Raw = raw
			if meta.DstIP.IsValid() && res.ResolvedIP == "" {
				res.ResolvedIP = meta.DstIP.String()
			}
			res.Chain = resolveAdapterChain(allProxies, adapter)
			return res
		}
	}
	return res
}

func flushDnsAndFakeIPInternal() error {
	resolver.ClearCache()
	return resolver.FlushFakeIP()
}

func closeAllConnectionsInternal() {
	var trackers []statistic.Tracker
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		trackers = append(trackers, c)
		return true
	})
	for _, c := range trackers {
		_ = c.Close()
	}
}

func applyConfigAndCloseConnections(cfg *config.Config) {
	closeAllConnectionsInternal()
	executor.ApplyConfig(cfg, true)
	runtime.GC()
}

func setModeAndCloseConnections(modeStr string) {
	if m, ok := tunnel.ModeMapping[strings.ToLower(strings.TrimSpace(modeStr))]; ok {
		prev := tunnel.Mode()
		tunnel.SetMode(m)
		if prev != m {
			closeAllConnectionsInternal()
		}
	}
}

func selectProxyAndCloseConnections(groupName, proxyName string) bool {
	p := tunnel.Proxies()[groupName]
	if p == nil {
		return false
	}
	s, ok := p.Adapter().(outboundgroup.SelectAble)
	if !ok {
		return false
	}
	var prevNow string
	if pg, okPg := p.Adapter().(outboundgroup.ProxyGroup); okPg {
		prevNow = pg.Now()
	}
	if err := s.Set(proxyName); err != nil {
		return false
	}
	if prevNow != proxyName {
		closeAllConnectionsInternal()
	}
	return true
}

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/dlclark/regexp2"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
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

	prependRules := cleanNonBlank(ov.PrependRules)
	if len(prependRules) > 0 {
		rawCfg.Rule = append(prependRules, rawCfg.Rule...)
	}
	appendRules := cleanNonBlank(ov.AppendRules)
	if len(appendRules) > 0 {
		matchIdx := -1
		for i := len(rawCfg.Rule) - 1; i >= 0; i-- {
			upper := strings.ToUpper(strings.TrimSpace(rawCfg.Rule[i]))
			if strings.HasPrefix(upper, "MATCH,") || upper == "MATCH" {
				matchIdx = i
				break
			}
		}
		if matchIdx >= 0 {
			updated := make([]string, 0, len(rawCfg.Rule)+len(appendRules))
			updated = append(updated, rawCfg.Rule[:matchIdx]...)
			updated = append(updated, appendRules...)
			updated = append(updated, rawCfg.Rule[matchIdx:]...)
			rawCfg.Rule = updated
		} else {
			rawCfg.Rule = append(rawCfg.Rule, appendRules...)
		}
	}

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

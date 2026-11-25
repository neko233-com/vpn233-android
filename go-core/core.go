package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"

	"github.com/dlclark/regexp2"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"os"
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

	if len(ov.PrependRules) > 0 {
		rawCfg.Rule = append(append([]string{}, ov.PrependRules...), rawCfg.Rule...)
	}
	if len(ov.AppendRules) > 0 {
		matchIdx := -1
		for i := len(rawCfg.Rule) - 1; i >= 0; i-- {
			upper := strings.ToUpper(strings.TrimSpace(rawCfg.Rule[i]))
			if strings.HasPrefix(upper, "MATCH,") || upper == "MATCH" {
				matchIdx = i
				break
			}
		}
		if matchIdx >= 0 {
			updated := make([]string, 0, len(rawCfg.Rule)+len(ov.AppendRules))
			updated = append(updated, rawCfg.Rule[:matchIdx]...)
			updated = append(updated, ov.AppendRules...)
			updated = append(updated, rawCfg.Rule[matchIdx:]...)
			rawCfg.Rule = updated
		} else {
			rawCfg.Rule = append(rawCfg.Rule, ov.AppendRules...)
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

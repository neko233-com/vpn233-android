package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func TestResolveAndPatchConfigRulesAndOverrides(t *testing.T) {
	dir := t.TempDir()
	C2.SetHomeDir(dir)
	t.Cleanup(func() {
		executor.Shutdown()
		_ = cachefile.Cache().Close()
	})

	yamlContent := `
mixed-port: 7890
proxies:
  - name: "NodeA"
    type: socks5
    server: 127.0.0.1
    port: 10808
proxy-groups:
  - name: "PROXY"
    type: select
    proxies:
      - NodeA
      - DIRECT
rules:
  - DOMAIN-SUFFIX,google.com,PROXY
  - MATCH,DIRECT
`
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yamlContent), 0o644); err != nil {
		t.Fatal(err)
	}

	overrideJson := `{
		"mode": "rule",
		"ipv6": false,
		"appendSystemDns": true,
		"prependRules": ["DOMAIN-SUFFIX,openai.com,PROXY"],
		"appendRules": ["DOMAIN-KEYWORD,lan-test,DIRECT"],
		"fakeIpFilterExtra": ["*.custom.local"]
	}`

	rawCfg, err := resolveAndPatchConfig(dir, overrideJson)
	if err != nil {
		t.Fatalf("resolveAndPatchConfig failed: %v", err)
	}

	if rawCfg.MixedPort != 0 {
		t.Fatalf("expected MixedPort cleared to 0, got %d", rawCfg.MixedPort)
	}
	if !rawCfg.DNS.Enable {
		t.Fatalf("expected DNS auto-enabled when missing from YAML")
	}
	if len(rawCfg.Rule) != 4 {
		t.Fatalf("expected 4 rules, got %d: %v", len(rawCfg.Rule), rawCfg.Rule)
	}
	if rawCfg.Rule[0] != "DOMAIN-SUFFIX,openai.com,PROXY" {
		t.Fatalf("expected prepended rule at index 0, got %s", rawCfg.Rule[0])
	}
	if rawCfg.Rule[2] != "DOMAIN-KEYWORD,lan-test,DIRECT" {
		t.Fatalf("expected appended rule before MATCH at index 2, got %s", rawCfg.Rule[2])
	}
	if !strings.HasPrefix(rawCfg.Rule[3], "MATCH,") {
		t.Fatalf("expected MATCH rule preserved at end, got %s", rawCfg.Rule[3])
	}

	hasSystemDns := false
	for _, ns := range rawCfg.DNS.NameServer {
		if ns == "system://" {
			hasSystemDns = true
			break
		}
	}
	if !hasSystemDns {
		t.Fatalf("expected system:// injected into DNS servers: %v", rawCfg.DNS.NameServer)
	}

	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		t.Fatalf("ParseRawConfig failed: %v", err)
	}
	executor.ApplyConfig(cfg, true)
	dtos := buildRulesDTO(cfg.Rules)
	if len(dtos) != 4 || dtos[0].Payload != "openai.com" || dtos[3].Type != "Match" {
		t.Fatalf("unexpected rule DTOs: %+v", dtos)
	}
}

type mockTracker struct {
	info   *statistic.TrackerInfo
	closed bool
}

func (m *mockTracker) ID() string                       { return m.info.UUID.String() }
func (m *mockTracker) Info() *statistic.TrackerInfo     { return m.info }
func (m *mockTracker) Close() error                     { m.closed = true; statistic.DefaultManager.Leave(m); return nil }
func (m *mockTracker) Chains() C2.Chain                 { return m.info.Chain }
func (m *mockTracker) ProviderChains() C2.Chain         { return nil }
func (m *mockTracker) AppendToChains(_ C2.ProxyAdapter) {}
func (m *mockTracker) RemoteDestination() string        { return "" }

func registerMockTracker(t *testing.T, chain []string) *mockTracker {
	t.Helper()
	uuid := utils.NewUUIDV4()
	mt := &mockTracker{
		info: &statistic.TrackerInfo{
			UUID:  uuid,
			Chain: chain,
		},
	}
	statistic.DefaultManager.Join(mt)
	return mt
}

func TestSelectProxyModeAndConfigCloseConnections(t *testing.T) {
	dir := t.TempDir()
	C2.SetHomeDir(dir)
	t.Cleanup(func() {
		closeAllConnectionsInternal()
		executor.Shutdown()
		_ = cachefile.Cache().Close()
	})

	yamlContent := `
mixed-port: 7890
proxies:
  - name: "Korea-01"
    type: socks5
    server: 127.0.0.1
    port: 10808
  - name: "USA-01"
    type: socks5
    server: 127.0.0.1
    port: 10809
proxy-groups:
  - name: "PROXY"
    type: select
    proxies:
      - Korea-01
      - USA-01
      - DIRECT
rules:
  - MATCH,PROXY
`
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yamlContent), 0o644); err != nil {
		t.Fatal(err)
	}
	rawCfg, err := resolveAndPatchConfig(dir, `{"mode":"rule"}`)
	if err != nil {
		t.Fatalf("resolveAndPatchConfig failed: %v", err)
	}
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		t.Fatalf("ParseRawConfig failed: %v", err)
	}
	applyConfigAndCloseConnections(cfg)

	// 1. Switch node Korea-01 -> USA-01 should close all active connections (even other chains/direct).
	trKorea := registerMockTracker(t, []string{"Korea-01", "PROXY"})
	trOther := registerMockTracker(t, []string{"DIRECT"})
	if ok := selectProxyAndCloseConnections("PROXY", "USA-01"); !ok {
		t.Fatalf("expected selectProxyAndCloseConnections to succeed")
	}
	if !trKorea.closed || !trOther.closed {
		t.Fatalf("expected all connections closed on node switch: korea=%v other=%v", trKorea.closed, trOther.closed)
	}

	// Selecting the exact same node again should not disrupt active connections.
	trSame := registerMockTracker(t, []string{"USA-01", "PROXY"})
	if ok := selectProxyAndCloseConnections("PROXY", "USA-01"); !ok {
		t.Fatalf("expected repeated select to succeed")
	}
	if trSame.closed {
		t.Fatalf("expected no disconnect when re-selecting identical node")
	}

	// 2. Switching routing mode rule -> global should close active connections.
	setModeAndCloseConnections("global")
	if !trSame.closed {
		t.Fatalf("expected connection closed on mode change rule->global")
	}

	// 3. Hot-reloading config (with freshly parsed Config) should close all active connections.
	trReload := registerMockTracker(t, []string{"USA-01", "PROXY"})
	rawCfg2, err := resolveAndPatchConfig(dir, `{"mode":"rule"}`)
	if err != nil {
		t.Fatalf("resolveAndPatchConfig 2 failed: %v", err)
	}
	cfg2, err := config.ParseRawConfig(rawCfg2)
	if err != nil {
		t.Fatalf("ParseRawConfig 2 failed: %v", err)
	}
	applyConfigAndCloseConnections(cfg2)
	if !trReload.closed {
		t.Fatalf("expected connection closed on config hot-reload")
	}

	// Invalid selections must leave the working node and its connections intact.
	trInvalid := registerMockTracker(t, []string{"Korea-01", "PROXY"})
	if selectProxyAndCloseConnections("PROXY", "missing-node") || trInvalid.closed {
		t.Fatalf("invalid node selection must fail without closing connections")
	}
	setModeAndCloseConnections("rule")
	if trInvalid.closed {
		t.Fatalf("reselecting the current routing mode must not close connections")
	}
	setModeAndCloseConnections("direct")
	if !trInvalid.closed {
		t.Fatalf("switching rule -> direct must close existing connections")
	}

	// A different subscription must replace the live group membership, and switching
	// back must make the original nodes selectable again.
	secondDir := t.TempDir()
	secondYaml := strings.ReplaceAll(yamlContent, "Korea-01", "Second-01")
	secondYaml = strings.ReplaceAll(secondYaml, "USA-01", "Second-02")
	if err := os.WriteFile(filepath.Join(secondDir, "config.yaml"), []byte(secondYaml), 0o644); err != nil {
		t.Fatal(err)
	}
	secondRaw, err := resolveAndPatchConfig(secondDir, `{"mode":"rule"}`)
	if err != nil {
		t.Fatal(err)
	}
	secondCfg, err := config.ParseRawConfig(secondRaw)
	if err != nil {
		t.Fatal(err)
	}
	trBeforeSwitch := registerMockTracker(t, []string{"DIRECT"})
	applyConfigAndCloseConnections(secondCfg)
	if !trBeforeSwitch.closed || !selectProxyAndCloseConnections("PROXY", "Second-02") {
		t.Fatal("second subscription did not replace the active configuration")
	}
	if selectProxyAndCloseConnections("PROXY", "USA-01") {
		t.Fatal("old subscription node remained selectable after the switch")
	}
	applyConfigAndCloseConnections(cfg2)
	if !selectProxyAndCloseConnections("PROXY", "USA-01") {
		t.Fatal("original subscription node was not restored")
	}
}

func TestMergeYamlAndRuleMatchTester(t *testing.T) {
	dir := t.TempDir()
	C2.SetHomeDir(dir)
	t.Cleanup(func() {
		closeAllConnectionsInternal()
		executor.Shutdown()
		_ = cachefile.Cache().Close()
	})

	yamlContent := `
proxies:
  - name: "KR-01"
    type: socks5
    server: 127.0.0.1
    port: 10808
proxy-groups:
  - name: "DIRECT"
    type: select
    proxies:
      - DIRECT
  - name: "AUTO"
    type: select
    proxies:
      - KR-01
  - name: "PROXY"
    type: select
    proxies:
      - AUTO
      - DIRECT
rules:
  - DOMAIN-SUFFIX,base.example,PROXY
  - MATCH,PROXY
`
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yamlContent), 0o644); err != nil {
		t.Fatal(err)
	}

	mergeYaml := `
prepend-proxies:
  - name: "Custom-US"
    type: socks5
    server: 127.0.0.1
    port: 10809
prepend-rules:
  - DOMAIN-SUFFIX,openai.com,PROXY
append-rules:
  - DOMAIN-KEYWORD,internal,DIRECT
`
	globalMerge := `
prepend-rules:
  - DOMAIN-SUFFIX,global.example,DIRECT
fake-ip-filter:
  - "*.global.local"
nameserver-policy:
  "+.corp.example": "114.114.114.114"
`
	ovBytes, _ := json.Marshal(map[string]any{
		"mode":            "rule",
		"globalMergeYaml": globalMerge,
		"mergeYaml":       mergeYaml,
		"prependRules":    []string{"DOMAIN,top.example,REJECT"},
	})
	rawCfg, err := resolveAndPatchConfig(dir, string(ovBytes))
	if err != nil {
		t.Fatalf("resolveAndPatchConfig with MergeYaml failed: %v", err)
	}
	if len(rawCfg.Proxy) != 2 || rawCfg.Proxy[0]["name"] != "Custom-US" {
		t.Fatalf("expected prepended proxy Custom-US, got %+v", rawCfg.Proxy)
	}
	if rawCfg.DNS.NameServerPolicy == nil {
		t.Fatalf("expected nameserver-policy merged from globalMergeYaml")
	}
	if _, ok := rawCfg.DNS.NameServerPolicy.Get("+.corp.example"); !ok {
		t.Fatalf("expected +.corp.example in NameServerPolicy")
	}
	hasGlobalFakeIP := false
	for _, f := range rawCfg.DNS.FakeIPFilter {
		if f == "*.global.local" {
			hasGlobalFakeIP = true
			break
		}
	}
	if !hasGlobalFakeIP {
		t.Fatalf("expected *.global.local in FakeIPFilter: %v", rawCfg.DNS.FakeIPFilter)
	}
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		t.Fatalf("ParseRawConfig failed: %v", err)
	}
	applyConfigAndCloseConnections(cfg)

	// Leaf resolution across nested groups: PROXY -> AUTO -> KR-01
	leaf := resolveLeafNow(tunnel.Proxies(), "PROXY")
	if leaf != "KR-01" {
		t.Fatalf("expected nested leaf KR-01, got %s", leaf)
	}

	// Match rule tester
	hit := matchRuleForQuery("api.openai.com", "", "443", "tcp", "")
	if !hit.Matched || hit.Adapter != "PROXY" || hit.Payload != "openai.com" || len(hit.Chain) < 2 {
		t.Fatalf("unexpected rule match result: %+v", hit)
	}
	hitAppend := matchRuleForQuery("git.internal.corp", "", "443", "tcp", "")
	if !hitAppend.Matched || hitAppend.Adapter != "DIRECT" || hitAppend.RuleType != "DomainKeyword" {
		t.Fatalf("expected append-rule hit before MATCH, got: %+v", hitAppend)
	}

	// Invalid merge YAML must return an error cleanly
	badOv, _ := json.Marshal(map[string]any{"mergeYaml": "prepend-rules: [broken"})
	if _, err := resolveAndPatchConfig(dir, string(badOv)); err == nil {
		t.Fatalf("expected error on invalid mergeYaml")
	}
}

func TestResolveAndPatchConfigRejectsEmptyProxies(t *testing.T) {
	dir := t.TempDir()
	yamlContent := `
rules:
  - MATCH,DIRECT
`
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yamlContent), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveAndPatchConfig(dir, ""); err == nil {
		t.Fatalf("expected error when neither proxies nor proxy-providers exist")
	}
}

func TestLiteGeoSiteAndShadowDirectGroupTolerance(t *testing.T) {
	dir := t.TempDir()
	C2.SetHomeDir(dir)
	t.Cleanup(func() {
		closeAllConnectionsInternal()
		executor.Shutdown()
		_ = cachefile.Cache().Close()
	})

	assetsDir := filepath.Join("..", "app", "src", "main", "assets")
	for _, asset := range []string{"geosite.dat", "geoip.metadb"} {
		data, err := os.ReadFile(filepath.Join(assetsDir, asset))
		if err != nil {
			t.Skipf("bundled asset %s not found: %v", asset, err)
		}
		if err := os.WriteFile(filepath.Join(dir, asset), data, 0o644); err != nil {
			t.Fatal(err)
		}
	}

	yamlContent := `
proxies:
  - name: "Node-01"
    type: socks5
    server: 127.0.0.1
    port: 10808
proxy-groups:
  - name: "DIRECT"
    type: select
    proxies:
      - DIRECT
  - name: "PROXY"
    type: select
    proxies:
      - Node-01
      - DIRECT
rules:
  - GEOSITE,netflix,PROXY
  - GEOSITE,hbo,PROXY
  - GEOSITE,mytvsuper,PROXY
  - GEOSITE,CN,DIRECT
  - GEOIP,CN,DIRECT
  - MATCH,PROXY
`
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(yamlContent), 0o644); err != nil {
		t.Fatal(err)
	}
	rawCfg, err := resolveAndPatchConfig(dir, `{"mode":"rule"}`)
	if err != nil {
		t.Fatalf("resolveAndPatchConfig failed: %v", err)
	}
	if _, err := config.ParseRawConfig(rawCfg); err != nil {
		t.Fatalf("ParseRawConfig failed with lite geosite.dat: %v", err)
	}
}

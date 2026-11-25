package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
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

func (m *mockTracker) ID() string                     { return m.info.UUID.String() }
func (m *mockTracker) Info() *statistic.TrackerInfo   { return m.info }
func (m *mockTracker) Close() error                   { m.closed = true; statistic.DefaultManager.Leave(m); return nil }
func (m *mockTracker) Chains() C2.Chain               { return m.info.Chain }
func (m *mockTracker) ProviderChains() C2.Chain       { return nil }
func (m *mockTracker) AppendToChains(_ C2.ProxyAdapter) {}
func (m *mockTracker) RemoteDestination() string      { return "" }

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

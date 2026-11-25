package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
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

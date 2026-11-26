package main

import (
	"testing"

	"github.com/metacubex/mihomo/constant/features"
)

func TestFullMihomoBuildFeatures(t *testing.T) {
	if !features.CMFA || !features.WithGVisor {
		t.Fatal("Android integration and gVisor support must be enabled")
	}
	if features.NoEasyTier || features.NoTailscale || features.NoZeroTier || features.NoFakeTCP {
		t.Fatalf("a Mihomo feature was disabled in this build: %v", features.Tags())
	}
}

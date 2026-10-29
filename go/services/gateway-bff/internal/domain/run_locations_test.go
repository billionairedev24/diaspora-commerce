package domain_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestRunLocationSlug(t *testing.T) {
	for in, want := range map[[2]string]string{{"Bastrop", "TX"}: "bastrop-tx", {" San  Marcos ", ""}: "san-marcos", {"!!", ""}: ""} {
		if got := domain.RunLocationSlug(in[0], in[1]); got != want {
			t.Errorf("slug(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRunLocationSwitchBody(t *testing.T) {
	if _, err := domain.RunLocationSwitchBody(map[string]any{}, true); err == nil {
		t.Error("switching off without a reason is accepted")
	}
	if out, err := domain.RunLocationSwitchBody(map[string]any{}, false); err != nil || len(out) != 0 {
		t.Errorf("switching on needs no reason: %v %v", out, err)
	}
	out, err := domain.RunLocationSwitchBody(map[string]any{"reason": " moved "}, true)
	if err != nil || out["reason"] != "moved" {
		t.Errorf("reason = %v %v", out, err)
	}
}

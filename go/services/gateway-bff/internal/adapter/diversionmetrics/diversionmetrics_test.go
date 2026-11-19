package diversionmetrics_test

import (
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/diversionmetrics"
)

func TestCountsByField(t *testing.T) {
	reg := prometheus.NewRegistry()
	hook := diversionmetrics.New(reg)
	hook("about.body")
	hook("about.body")
	hook("hero.tagline")
	n, err := testutil.GatherAndCount(reg, "diversion_text_rejected_total")
	if err != nil || n != 2 {
		t.Fatalf("series=%d err=%v", n, err)
	}
}

package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestDeliveryLocationIsDetectedFromEdgeHeaders: a first visit gets an approximate city silently (flagged detected,
// never the ZIP as the label); a chosen location replaces it and is not overwritten by later detection.
func TestDeliveryLocationIsDetectedFromEdgeHeaders(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	b.headers["CF-IPCountry"], b.headers["CF-IPPostalCode"], b.headers["CF-IPCity"] = "US", "10456", "New York"
	got := b.do("GET", "/v1/delivery-location", nil).json(t)
	if got["label"] != "Bronx" || got["source"] != "detected" || got["zip"] != "10456" {
		t.Fatalf("detected = %v", got)
	}
	if home, ok := b.do("GET", "/v1/home", nil).json(t)["deliverTo"].(map[string]any); !ok || home["label"] != "Bronx" {
		t.Fatalf("home deliverTo = %v", home)
	}
	got = b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "77042"}).json(t)
	if got["source"] != "chosen" {
		t.Fatalf("chosen = %v", got)
	}
	if got = b.do("GET", "/v1/delivery-location", nil).json(t); got["label"] != "Houston" || got["source"] != "chosen" {
		t.Fatalf("a chosen location was replaced by detection: %v", got)
	}
}

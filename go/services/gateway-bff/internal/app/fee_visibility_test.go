package app

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// failingFees is a FeesClient whose rate card payments refuses (403 for a seller role without payouts:read, or an
// outage): only RateCard is used by rateCardOf.
type failingFees struct{ port.FeesClient }

func (failingFees) RateCard(context.Context, string, string, time.Time) (port.RateCardResponse, error) {
	return port.RateCardResponse{}, errors.New("payments: 403 forbidden")
}

// Roles without payouts:read lose the rate card, negotiated rates and plan fee lines (payments refuses them). The seller
// screens must hide those blocks, not fail: the card is nil (omitted), the lists are empty arrays, never null.
func TestRefusedRateCardAndPlanFeesHideGracefully(t *testing.T) {
	if got := rateCardOf(context.Background(), failingFees{}, "org-1", "PRODUCT", time.Time{}); got != nil {
		t.Fatalf("a refused rate card must be omitted, got %+v", got)
	}
	if got := rateCardOf(context.Background(), nil, "org-1", "PRODUCT", time.Time{}); got != nil {
		t.Fatalf("no fees client: %+v", got)
	}
	raw, err := json.Marshal(planFeeDeductionsOf(nil))
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != "[]" {
		t.Fatalf("plan fee deductions must serialise as [], got %s", raw)
	}
}

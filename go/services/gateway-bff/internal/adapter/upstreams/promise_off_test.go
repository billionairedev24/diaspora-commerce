package upstreams

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func TestNoPromiseKnowsNoDatesAndNeverFails(t *testing.T) {
	days, err := NewNoPromise().Promises(context.Background(), "77002", []port.PromiseItem{{ATIN: "B0001"}})
	if err != nil || len(days) != 0 {
		t.Fatalf("no promise service: want no dates and no error, got %v %v", days, err)
	}
}

func TestDeliveryFactsWithoutPromiseServiceAnswersNothing(t *testing.T) {
	quotes, err := NewDeliveryFacts(nil, nil, nil, nil).Promises(context.Background(), "77002",
		[]port.PromiseOffer{{OfferID: "p|ship"}})
	if err != nil || len(quotes) != 0 {
		t.Fatalf("a nil promise client must not be called: %v %v", quotes, err)
	}
}

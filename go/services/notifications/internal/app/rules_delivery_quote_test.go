package app_test

import (
	"log/slog"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
)

// Delivery quotes (listing rules D20): the shopper is emailed when the amount is agreed (with the pay link back to
// checkout) or the request is declined; the staff-facing events send nothing.
func TestDeliveryQuoteEventsEmailTheShopper(t *testing.T) {
	n := &recordingNotifier{}
	ing := app.NewIngest(n, app.IngestConfig{Resolver: stubResolver{}, Links: sampleLinks}, slog.New(slog.DiscardHandler))
	evs := app.SampleEvents(sampleLinks)

	handle(t, ing, "orders.delivery-quote.ready", evs["orders.delivery-quote.ready"][0])
	if len(n.reqs) != 1 {
		t.Fatalf("ready sent %d notifications", len(n.reqs))
	}
	r := n.reqs[0]
	if r.TemplateKey != "delivery_quote.ready" || r.Data["amount"] != "$22.50" {
		t.Fatalf("ready = %+v", r)
	}
	url, _ := r.Data["payUrl"].(string)
	if !strings.HasPrefix(url, sampleLinks.Storefront+"/checkout?quote=0e5a6c2c-1b2d-4c3e-8f40-5a6b7c8d9e01") {
		t.Fatalf("the pay link goes back to checkout with the quote: %q", url)
	}
	if r.Data["expiresDisplay"] != "Fri, Nov 13 3:00 PM" {
		t.Fatalf("expiresDisplay = %v", r.Data["expiresDisplay"])
	}

	n.reqs = nil
	handle(t, ing, "orders.delivery-quote.declined", evs["orders.delivery-quote.declined"][0])
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "delivery_quote.declined" {
		t.Fatalf("declined = %+v", n.reqs)
	}
	for k := range n.reqs[0].Data {
		if strings.Contains(strings.ToLower(k), "reason") {
			t.Fatalf("the staff reason must never reach the shopper: %v", k)
		}
	}

	// Redelivery of staff events sends nothing.
	n.reqs = nil
	for _, typ := range []string{"orders.delivery-quote.requested", "orders.delivery-quote.assigned", "orders.delivery-quote.escalated", "orders.delivery-quote.expired"} {
		handle(t, ing, typ, map[string]any{"quoteId": "q", "reference": "DQ-1", "customerId": "cust-1"})
	}
	if len(n.reqs) != 0 {
		t.Fatalf("staff events notified the shopper: %+v", n.reqs)
	}
}

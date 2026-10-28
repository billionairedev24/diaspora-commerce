package localtest

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func TestAllCarrierLabelsGenerateValidPDF(t *testing.T) {
	shipment := domain.Shipment{
		OriginNode: "fc_houston_tx",
		Reference:  "AT-2026-TEST-001",
		From: domain.Address{
			Name:       "Ade's Kitchen LLC",
			Company:    "Ade's Kitchen LLC",
			Phone:      "+17135550199",
			Line1:      "9800 Westheimer Rd",
			City:       "Houston",
			State:      "TX",
			PostalCode: "77042",
			Country:    "US",
		},
		To: domain.Address{
			Name:       "Adaeze Okafor",
			Line1:      "10200 Richmond Ave",
			Line2:      "Suite 400",
			City:       "Houston",
			State:      "TX",
			PostalCode: "77042",
			Country:    "US",
		},
		Parcels: []domain.Parcel{{WeightLb: 3.5, LengthIn: 10, WidthIn: 8, HeightIn: 4}},
	}

	carriers := []domain.CarrierCode{domain.USPS, domain.UPS, domain.FedEx, domain.DHL}
	for _, code := range carriers {
		c := New(code, func() time.Time { return time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC) })
		quotes, err := c.Rate(context.Background(), shipment)
		if err != nil || len(quotes) == 0 {
			t.Fatalf("%s rate failed: %v", code, err)
		}
		label, err := c.BuyLabel(context.Background(), shipment, quotes[0].Service)
		if err != nil {
			t.Fatalf("%s buy label failed: %v", code, err)
		}
		if len(label.Data) < 100 {
			t.Fatalf("%s label data too short: %d", code, len(label.Data))
		}
		// Write out FedEx label for inspection
		if code == domain.FedEx {
			_ = os.WriteFile("/tmp/test_fedex_label.pdf", label.Data, 0644)
		}
	}
}

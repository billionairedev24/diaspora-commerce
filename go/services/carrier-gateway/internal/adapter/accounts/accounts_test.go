package accounts_test

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/accounts"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func TestAccountsPerNodeWithDefaults(t *testing.T) {
	r := accounts.New(accounts.Config{
		Defaults: []accounts.Entry{{Carrier: "usps"}, {Carrier: "ups", Negotiated: true}},
		Nodes:    map[string][]accounts.Entry{"fc_edison_nj": {{Carrier: "fedex", Number: "740561073", Negotiated: true, AdjustmentPct: -3}, {Carrier: "dhl"}}},
	}, []domain.CarrierCode{domain.USPS, domain.UPS, domain.FedEx})
	fc, _ := r.Accounts(context.Background(), "fc_edison_nj")
	if len(fc) != 1 || fc[0].Carrier != domain.FedEx || fc[0].Number != "740561073" || fc[0].AdjustmentPct != -3 {
		t.Fatalf("FC accounts (DHL disabled) %+v", fc)
	}
	seller, _ := r.Accounts(context.Background(), "org_seller_1")
	if len(seller) != 2 || !seller[1].Negotiated {
		t.Fatalf("defaults %+v", seller)
	}
}

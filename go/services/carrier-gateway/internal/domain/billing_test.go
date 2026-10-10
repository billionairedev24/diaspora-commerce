package domain_test

import (
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func TestBillingDecidesWhoPaysForALabel(t *testing.T) {
	p := domain.BillingPolicy{RetailOrgIDs: []string{"org_retail"}, PlatformNodes: []string{"fc_houston_tx"}}
	seller := domain.LabelPayer{OrgID: "org_s"}
	cases := []struct {
		name   string
		payer  domain.LabelPayer
		origin string
		kind   domain.LabelKind
		f      domain.Fulfilment
		bill   domain.BillTo
		reason string
	}{
		{"seller-shipped from its own origin", seller, "org_s", domain.KindOutbound, domain.FulfilmentSellerShipped, domain.BillSeller, domain.BillingSellerShipped},
		{"retail is merchant of record", domain.LabelPayer{OrgID: "org_retail"}, "org_retail", domain.KindOutbound, domain.FulfilmentSellerShipped, domain.BillAfroTransact, domain.BillingRetail},
		{"FBA", seller, "org_s", domain.KindOutbound, domain.FulfilmentFBA, domain.BillAfroTransact, domain.BillingFBA},
		{"AfroTransact Delivery", seller, "org_s", domain.KindOutbound, domain.FulfilmentAfroTransactDelivery, domain.BillAfroTransact, domain.BillingAfroTransactDelivery},
		{"returns", seller, "org_s", domain.KindReturn, domain.FulfilmentSellerShipped, domain.BillAfroTransact, domain.BillingReturn},
		{"staff or service", domain.LabelPayer{OrgID: "org_s", Platform: true}, "org_s", domain.KindOutbound, domain.FulfilmentSellerShipped, domain.BillAfroTransact, domain.BillingPlatformCaller},
		{"no organisation", domain.LabelPayer{}, "org_s", domain.KindOutbound, domain.FulfilmentSellerShipped, domain.BillAfroTransact, domain.BillingPlatformCaller},
		{"fulfilment centre origin", seller, "fc_houston_tx", domain.KindOutbound, domain.FulfilmentSellerShipped, domain.BillAfroTransact, domain.BillingPlatformOrigin},
		{"another organisation's origin", seller, "org_other", domain.KindOutbound, domain.FulfilmentSellerShipped, domain.BillAfroTransact, domain.BillingPlatformOrigin},
	}
	for _, c := range cases {
		bill, reason := p.Decide(c.payer, c.origin, c.kind, c.f)
		if bill != c.bill || reason != c.reason {
			t.Errorf("%s: got %s/%s, want %s/%s", c.name, bill, reason, c.bill, c.reason)
		}
	}
}

func TestHandlingMarkup(t *testing.T) {
	if got := (domain.BillingPolicy{}).Handling(1234); got != 0 {
		t.Fatalf("no markup by default: %d", got)
	}
	if got := (domain.BillingPolicy{HandlingPct: 5, HandlingFixedMinor: 10}).Handling(1250); got != 73 { // 62.5 → 63 + 10
		t.Fatalf("pct + fixed: %d", got)
	}
	if err := (domain.BillingPolicy{HandlingPct: -1}).Validate(); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("negative markup: %v", err)
	}
	if f, err := domain.ParseFulfilment(""); err != nil || f != domain.FulfilmentSellerShipped {
		t.Fatalf("default fulfilment %s %v", f, err)
	}
	if _, err := domain.ParseFulfilment("drone"); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("unknown fulfilment %v", err)
	}
}

func TestLabelChargeOnlyForSellerBilledLabels(t *testing.T) {
	l := domain.LabelRecord{CostMinor: 900, HandlingMinor: 50, BillTo: domain.BillSeller}
	if l.Charge() != 950 {
		t.Fatalf("seller charge %d", l.Charge())
	}
	l.BillTo = domain.BillAfroTransact
	if l.Charge() != 0 {
		t.Fatalf("AfroTransact-paid label %d", l.Charge())
	}
}

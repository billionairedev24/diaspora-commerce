package app

import (
	"strings"
	"testing"
)

func checkValue(o jobj, label string) string {
	for _, f := range payoutChecks(o) {
		if f.Label == label {
			return f.Value
		}
	}
	return ""
}

func TestPayoutChecksSayWhyAPayoutCannotBePaid(t *testing.T) {
	noAccount := jobj{"orgId": "org_a"}
	if got := checkValue(noAccount, "Stripe account on file"); !strings.HasPrefix(got, "No") {
		t.Errorf("no connected account: got %q", got)
	}

	unverified := jobj{"stripeAccountId": "acct_1", "complianceKnown": true, "stripeStatus": "ACTION_NEEDED", "payoutsEnabled": false, "payoutsPaused": true}
	if got := checkValue(unverified, "Stripe verified"); !strings.Contains(got, "Action needed") {
		t.Errorf("unverified: got %q", got)
	}
	if got := checkValue(unverified, "No compliance hold"); !strings.HasPrefix(got, "No") {
		t.Errorf("paused: got %q", got)
	}

	compliant := jobj{"stripeAccountId": "acct_1", "complianceKnown": true, "stripeStatus": "COMPLIANT", "payoutsEnabled": true}
	if got := checkValue(compliant, "Stripe verified"); got != "Yes" {
		t.Errorf("a compliant account is verified: got %q", got)
	}

	ready := jobj{"stripeAccountId": "acct_1", "complianceKnown": true, "stripeStatus": "VERIFIED", "payoutsEnabled": true,
		"bankName": "Chase", "bankLast4": "9921", "bankStatus": "VERIFIED"}
	if got := checkValue(ready, "Stripe can pay out"); got != "Yes" {
		t.Errorf("ready: got %q", got)
	}
	if got := checkValue(ready, "Bank account"); !strings.Contains(got, "9921") {
		t.Errorf("bank: got %q", got)
	}
	if got := checkValue(jobj{"stripeAccountId": "acct_1"}, "Stripe verification"); !strings.HasPrefix(got, "Not confirmed") {
		t.Errorf("unreadable account: got %q", got)
	}
}

func TestPayoutSectionsNameTheSeller(t *testing.T) {
	secs := payoutSections(jobj{"orgId": "org_a", "sellerName": "Malo Branding", "requestedByName": "Emalo D.", "amountMinor": 50413})
	var seller []string
	for _, s := range secs {
		if s.Title == "Seller" {
			for _, f := range s.Fields {
				seller = append(seller, f.Label+"="+f.Value)
			}
		}
	}
	joined := strings.Join(seller, "|")
	if !strings.Contains(joined, "Business=Malo Branding") || !strings.Contains(joined, "Requested by=Emalo D.") {
		t.Errorf("seller section: %s", joined)
	}
}

func TestAPayoutSheetShowsTheTransfersAndOpensThemInStripe(t *testing.T) {
	o := jobj{"transfers": []any{map[string]any{"id": "t-1", "orderId": "ord-1", "stripeTransferId": "tr_1ABC123", "amountMinor": 50000,
		"dashboardUrl": "https://dashboard.stripe.com/test/connect/transfers/tr_1ABC123"}},
		"stripe": map[string]any{"dashboardUrl": "https://dashboard.stripe.com/test/search?query=po_1"}}

	found := false
	for _, tb := range payoutTables(o) {
		if strings.Contains(tb.Title, "transfers") {
			found = true
		}
	}
	if !found {
		t.Error("the transfers table is missing")
	}
	links := stripeLinks(o)
	if len(links) != 2 || !strings.Contains(links[1].Href, "connect/transfers/tr_1ABC123") {
		t.Errorf("links = %v", links)
	}
}

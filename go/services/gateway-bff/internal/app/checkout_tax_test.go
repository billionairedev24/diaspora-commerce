package app

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// fakeReturns answers tax-returns' quote per line with the given function and records the request.
type fakeReturns struct {
	answer func(l port.UpTaxQuoteLine) port.UpTaxLineQuote
	err    error
	req    port.UpTaxQuoteRequest
}

func (f *fakeReturns) TaxQuote(_ context.Context, req port.UpTaxQuoteRequest) (port.UpTaxQuote, error) {
	f.req = req
	if f.err != nil {
		return port.UpTaxQuote{}, f.err
	}
	q := port.UpTaxQuote{Jurisdiction: "US-TX"}
	for _, l := range req.Lines {
		if f.answer != nil {
			q.Lines = append(q.Lines, f.answer(l))
		}
	}
	return q, nil
}

func taxable(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
	base := l.UnitPriceMinor*int64(l.Quantity) - l.DiscountMinor + l.ShippingMinor
	return port.UpTaxLineQuote{LineID: l.LineID, TaxMinor: (base*825 + 5000) / 10000, Rate: 0.0825, DecisionStatus: "RESOLVED", Outcome: "TAXABLE"}
}

func exempt(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
	return port.UpTaxLineQuote{LineID: l.LineID, DecisionStatus: "RESOLVED", Outcome: "EXEMPT"}
}

func undecided(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
	return port.UpTaxLineQuote{LineID: l.LineID, DecisionStatus: "NEEDS_APPROVAL", Outcome: "BLOCKED", BlockedReason: "awaiting review"}
}

var houstonAddr = &port.IdentityAddress{State: "TX", PostalCode: "77002", City: "Houston", Country: "US"}

func pkg(id string, ship int64, lines ...domain.CartLine) domain.CheckoutPackage {
	return domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: id, Seller: domain.Seller{ID: "org_" + id}, Lines: lines,
		DeliveryOptions: []domain.DeliveryOption{{Key: "carrier", FeeMinor: ship}}}}
}

func cl(id, title string, qty int, price int64) domain.CartLine {
	return domain.CartLine{LineID: id, ATIN: "ATIN-" + id, OfferID: "of-" + id, Title: title, Quantity: qty, PriceMinor: price}
}

func price(t *testing.T, f *fakeReturns, alloc ShippingAllocation, sum domain.CheckoutSummary) domain.CheckoutSummary {
	t.Helper()
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: f, ShippingAllocation: alloc}}
	sum.CanPlaceOrder, sum.BlockedLines, sum.TaxLines = true, []domain.CheckoutBlockedLine{}, []domain.CheckoutTaxLine{}
	s.priceTax(context.Background(), &sum, houstonAddr, nil)
	return sum
}

func TestMixedBasketSplitsDeliveryAndExemptLinesCarryNoTax(t *testing.T) {
	f := &fakeReturns{answer: func(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
		if l.LineID == "ex" {
			return exempt(l)
		}
		return taxable(l)
	}}
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD",
		Packages: []domain.CheckoutPackage{pkg("a", 800, cl("ex", "Egusi", 1, 1000), cl("tx", "Sauce pan", 1, 3000))}})
	// variant ATIN and seller go to tax-returns, and the delivery is split 200 / 600 (never all on the first line)
	if got := f.req.Lines[0]; got.ATIN != "ATIN-ex" || got.SellerOrgID != "org_a" || got.ShippingMinor != 200 {
		t.Fatalf("first quote line: %+v", got)
	}
	if got := f.req.Lines[1].ShippingMinor; got != 600 {
		t.Fatalf("second line shipping %d", got)
	}
	// (3000 + 600) x 8.25% = 297; the exempt line carries none
	if sum.TaxMinor != 297 || sum.TaxRateLabel != "8.25%" || sum.TaxJurisdictionLabel != "Houston, TX" {
		t.Fatalf("estimate: %+v", sum)
	}
	if len(sum.TaxLines) != 2 || sum.TaxLines[0].Status != domain.TaxLineExempt || sum.TaxLines[1].Status != domain.TaxLineTaxable ||
		sum.TaxLines[0].ShippingMinor != 200 || sum.TaxLines[1].TaxMinor != 297 {
		t.Fatalf("tax lines: %+v", sum.TaxLines)
	}
	if !sum.CanPlaceOrder || len(sum.BlockedLines) != 0 {
		t.Fatalf("nothing is blocked: %+v", sum)
	}
	if a := sum.ShippingTaxAllocation; a == nil || a.Method != "PROPORTIONAL" || a.Approved || !strings.Contains(a.Note, "open legal decision") {
		t.Fatalf("the summary must flag the open decision: %+v", a)
	}
}

func TestDeliveryTiedOnlyToExemptLinesIsNotTaxed(t *testing.T) {
	f := &fakeReturns{answer: func(l port.UpTaxQuoteLine) port.UpTaxLineQuote { return exempt(l) }}
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD",
		Packages: []domain.CheckoutPackage{pkg("a", 499, cl("l1", "Egusi", 1, 2500), cl("l2", "Groundnuts", 1, 1500))}})
	if sum.TaxMinor != 0 || sum.TaxRateLabel != "0.00%" {
		t.Fatalf("exempt-only basket: %+v", sum)
	}
	if got := f.req.Lines[0].ShippingMinor + f.req.Lines[1].ShippingMinor; got != 499 {
		t.Fatalf("the whole delivery goes to the quote, split over the lines: %d", got)
	}
}

func TestFirstLineIsTheRollback(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	price(t, f, AllocateFirstLine, domain.CheckoutSummary{Currency: "USD",
		Packages: []domain.CheckoutPackage{pkg("a", 599, cl("l1", "Rice", 1, 1000), cl("l2", "Oil", 1, 1000))}})
	if f.req.Lines[0].ShippingMinor != 599 || f.req.Lines[1].ShippingMinor != 0 {
		t.Fatalf("first-line rollback: %+v", f.req.Lines)
	}
}

func TestDiscountsReduceTheAllocationWeights(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	price(t, f, "", domain.CheckoutSummary{Currency: "USD",
		Coupon:   &domain.CheckoutCoupon{Applicable: true, OfferDiscounts: []domain.CheckoutCouponOfferDiscount{{OfferID: "of-l1", DiscountMinor: 500}}},
		Packages: []domain.CheckoutPackage{pkg("a", 1000, cl("l1", "Rice", 1, 1000), cl("l2", "Oil", 1, 1500))}})
	// net 500 and 1500: 250 / 750
	if f.req.Lines[0].ShippingMinor != 250 || f.req.Lines[1].ShippingMinor != 750 {
		t.Fatalf("allocation by net value: %+v", f.req.Lines)
	}
}

func TestLinesWithoutADefiniteDecisionAreBlockedNotGuessed(t *testing.T) {
	for name, answer := range map[string]func(port.UpTaxQuoteLine) port.UpTaxLineQuote{
		"needs approval": undecided,
		"no status (older tax-returns)": func(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
			return port.UpTaxLineQuote{LineID: l.LineID, TaxMinor: 82, Rate: 0.0825}
		},
		"resolved without an outcome": func(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
			return port.UpTaxLineQuote{LineID: l.LineID, TaxMinor: 82, DecisionStatus: "RESOLVED"}
		},
	} {
		f := &fakeReturns{answer: func(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
			if l.LineID == "gc" {
				return answer(l)
			}
			return taxable(l)
		}}
		sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD",
			Packages: []domain.CheckoutPackage{pkg("a", 0, cl("rice", "Rice", 1, 1000), cl("gc", "Gift card", 1, 2500))}})
		if sum.CanPlaceOrder || len(sum.BlockedLines) != 1 {
			t.Fatalf("%s: %+v", name, sum)
		}
		b := sum.BlockedLines[0]
		if b.LineID != "gc" || b.Title != "Gift card" || b.Action != "REMOVE" || b.PackageID != "a" {
			t.Fatalf("%s: blocked line %+v", name, b)
		}
		if !strings.Contains(b.Message, "We can't sell Gift card yet") || strings.Contains(strings.ToLower(b.Message), "tax") ||
			strings.Contains(b.Message, "ATIN") || strings.Contains(b.Message, "NEEDS_APPROVAL") {
			t.Fatalf("%s: message must be plain, name the item, and carry no ids or tax terms: %q", name, b.Message)
		}
		// the blocked line contributes no tax figure; the rest is still estimated
		if sum.TaxMinor != 83 || sum.TaxLines[1].Status != domain.TaxLineBlocked || sum.TaxLines[1].TaxMinor != 0 {
			t.Fatalf("%s: estimate %+v %+v", name, sum.TaxMinor, sum.TaxLines)
		}
	}
}

func TestAMissingAnswerForALineBlocksIt(t *testing.T) {
	f := &fakeReturns{} // answers nothing
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{pkg("a", 0, cl("l1", "Rice", 1, 1000))}})
	if sum.CanPlaceOrder || len(sum.BlockedLines) != 1 || sum.TaxMinor != 0 {
		t.Fatalf("%+v", sum)
	}
}

func TestRemovingTheBlockedLineClearsTheBlock(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{pkg("a", 599, cl("rice", "Rice", 1, 1000))}})
	if !sum.CanPlaceOrder || len(sum.BlockedLines) != 0 || len(sum.TaxLines) != 1 {
		t.Fatalf("%+v", sum)
	}
}

func TestCreditNeverEntersTheTaxQuote(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	// credit and gift cards are tenders (orders' useCredit), not lines: the quote holds the items only
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD", Credit: &domain.CheckoutCredit{},
		Packages: []domain.CheckoutPackage{pkg("a", 0, cl("l1", "Rice", 2, 1000))}})
	if len(f.req.Lines) != 1 || f.req.Lines[0].LineID != "l1" || sum.TaxMinor != 165 {
		t.Fatalf("%+v %+v", f.req.Lines, sum)
	}
}

func TestAnUnavailableQuoteLeavesTheEstimateEmptyAndBlocksNothing(t *testing.T) {
	f := &fakeReturns{err: errors.New("down")}
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{pkg("a", 0, cl("l1", "Rice", 1, 1000))}})
	if !sum.CanPlaceOrder || sum.TaxMinor != 0 || sum.TaxJurisdictionLabel != "" || len(sum.TaxLines) != 0 || sum.ShippingTaxAllocation != nil {
		t.Fatalf("%+v", sum)
	}
}

func TestPlaceOrderMapsABlockedLineToAStableProblem(t *testing.T) {
	err := orderErr(&port.UpstreamProblem{Upstream: "orders", Status: http.StatusUnprocessableEntity, Code: "orders.tax-line-blocked",
		Detail: "We can't sell Gift card yet. We're still checking its details. Remove it from your cart to continue, or try again later."})
	p, ok := domain.AsProblem(err)
	if !ok || p.Code != domain.CodeCheckoutItemBlocked || p.Status != http.StatusUnprocessableEntity || !strings.Contains(p.Detail, "Gift card") {
		t.Fatalf("%+v", p)
	}
}

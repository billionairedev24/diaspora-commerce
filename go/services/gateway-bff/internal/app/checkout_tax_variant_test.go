package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// variantOnlyReturns behaves like tax-returns' LineDecisionServiceImpl: classification records are keyed by VARIANT ATIN,
// so a quote line carrying any other id (a product ATIN) has "No tax classification record" and is blocked.
func variantOnlyReturns(approvedVariants ...string) *fakeReturns {
	known := map[string]bool{}
	for _, v := range approvedVariants {
		known[v] = true
	}
	return &fakeReturns{answer: func(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
		if !known[l.ATIN] {
			return port.UpTaxLineQuote{LineID: l.LineID, DecisionStatus: "MISSING_FACTS", Outcome: "BLOCKED",
				BlockedReason: "No tax classification record for " + l.ATIN}
		}
		return exempt(l)
	}}
}

// variantCatalog answers catalog facts for variant ATINs only (as the real catalog does), so facts are loaded.
type variantCatalog struct{ stubCatalog }

func (variantCatalog) Variant(_ context.Context, atin string) (port.CatalogVariantView, error) {
	return port.CatalogVariantView{Product: port.CatalogProduct{ATIN: "ATH1GHCM73", ProductType: "rice-grains"}, Variant: port.CatalogVariant{ATIN: atin}}, nil
}

// A product whose product ATIN differs from its variant ATIN (the real catalog: every product has its own id) must be
// quoted under the variant ATIN, the id the classification record is stored under, never the product ATIN used for links.
func TestQuoteLineCarriesVariantATINNotProductATIN(t *testing.T) {
	store := memSelection{}
	svc := NewCartService(CartDeps{Selection: store, Estimator: est, Catalog: variantCatalog{}}).(*cartServiceImpl)
	c := &domain.Caller{Session: &domain.Session{ID: "s", GuestCartID: "g1"}}
	up := port.UpstreamCart{Currency: "USD", Packages: []port.UpstreamCartPackage{
		{SellerOrgID: "org_yooy9r2djppxly9zffmu", SellerName: "Yooy", Fulfilment: "SELLER", Lines: []port.UpstreamCartLine{
			{VariantATIN: "ATH0ZXZ594", ProductATIN: "ATH1GHCM73", OfferID: "49d0ae10", Title: "Basmati Rice", SellerKind: "SELLER",
				PriceMinor: 1299, Quantity: 1, Available: 9},
		}},
	}}
	cart, err := svc.present(context.Background(), c, up)
	if err != nil {
		t.Fatal(err)
	}
	if got := cart.Packages[0].Lines[0]; got.LineID != "ATH0ZXZ594" || got.ATIN != "ATH1GHCM73" {
		t.Fatalf("the cart keeps the variant as line id and the product ATIN for links: %+v", got)
	}
	f := variantOnlyReturns("ATH0ZXZ594")
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD",
		Packages: []domain.CheckoutPackage{{CartPackage: cart.Packages[0]}}})

	if got := f.req.Lines[0]; got.ATIN != "ATH0ZXZ594" || got.LineID != "ATH0ZXZ594" {
		t.Fatalf("quote line must carry the variant ATIN, got %+v", got)
	}
	if len(sum.BlockedLines) != 0 || !sum.CanPlaceOrder || sum.TaxLines[0].Status != domain.TaxLineExempt {
		t.Fatalf("an approved variant must not be blocked: %+v", sum)
	}
}

// When a line really is blocked, the blocked entry still links to the product page (product ATIN).
func TestBlockedLineKeepsProductATINForTheLink(t *testing.T) {
	line := domain.CartLine{LineID: "ATV0000001", VariantATIN: "ATV0000001", ATIN: "ATP0000001", OfferID: "of1", Title: "Rice", Quantity: 1, PriceMinor: 500}
	f := variantOnlyReturns() // knows nothing
	sum := price(t, f, "", domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{pkg("a", 0, line)}})
	if len(sum.BlockedLines) != 1 || sum.BlockedLines[0].ATIN != "ATP0000001" || sum.BlockedLines[0].LineID != "ATV0000001" {
		t.Fatalf("blocked: %+v", sum.BlockedLines)
	}
}

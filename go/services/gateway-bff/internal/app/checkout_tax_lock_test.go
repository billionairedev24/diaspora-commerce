package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var lockKey = QuoteKey("0123456789abcdef0123456789abcdef")

type countingTaxMetrics struct{ unavailable, locks map[string]int }

func newCounting() *countingTaxMetrics {
	return &countingTaxMetrics{unavailable: map[string]int{}, locks: map[string]int{}}
}
func (m *countingTaxMetrics) EstimateUnavailable(r string) { m.unavailable[r]++ }
func (m *countingTaxMetrics) QuoteLock(r string)           { m.locks[r]++ }

type fakeCarts struct {
	CartService
	cart domain.Cart
}

func (f fakeCarts) Get(context.Context, *domain.Caller) (domain.Cart, error) { return f.cart, nil }

type fakeAddresses struct {
	port.IdentityClient
	addrs []port.IdentityAddress
	err   error
}

func (f fakeAddresses) Addresses(context.Context, string) ([]port.IdentityAddress, error) {
	return f.addrs, f.err
}

type fakeOrders struct {
	port.CustomerOrdersClient
	req  port.UpCheckoutRequest
	fail error
}

func (f *fakeOrders) Checkout(_ context.Context, _, _ string, req port.UpCheckoutRequest) (port.UpCheckout, int, error) {
	f.req = req
	if f.fail != nil {
		return port.UpCheckout{}, 0, f.fail
	}
	return port.UpCheckout{OrderNumber: "AT-1", Status: "awaiting_payment", TotalMinor: 1000}, http.StatusCreated, nil
}

var shopper = &domain.Caller{Subject: "cust-1", BearerToken: "tok"}

func TestQuoteLockIsSignedBoundToTheShopperAndExpires(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	tok := signQuote(lockKey, taxQuoteClaims{Subject: "cust-1", Inputs: "abc", TaxMinor: 214, TotalMinor: 2813, Currency: "USD", ExpiresAt: now.Add(TaxQuoteTTL).Unix()})
	c, err := verifyQuote(lockKey, tok, "cust-1", now.Add(time.Minute))
	if err != nil || c.TaxMinor != 214 || c.TotalMinor != 2813 || c.Inputs != "abc" {
		t.Fatalf("%+v %v", c, err)
	}
	if _, err := verifyQuote(lockKey, tok, "cust-2", now); !errors.Is(err, errQuoteSubject) {
		t.Fatalf("another shopper must not use it: %v", err)
	}
	if _, err := verifyQuote(lockKey, tok, "cust-1", now.Add(TaxQuoteTTL+time.Second)); !errors.Is(err, errQuoteExpired) {
		t.Fatalf("expiry: %v", err)
	}
	payload, sig, _ := strings.Cut(tok, ".")
	forged := signQuote(lockKey, taxQuoteClaims{Subject: "cust-1", TaxMinor: 1, TotalMinor: 1, ExpiresAt: now.Add(time.Hour).Unix()})
	forgedPayload, _, _ := strings.Cut(forged, ".")
	if _, err := verifyQuote(lockKey, forgedPayload+"."+sig, "cust-1", now); !errors.Is(err, errQuoteSignature) {
		t.Fatalf("an edited payload keeps no valid signature: %v", err)
	}
	if _, err := verifyQuote(QuoteKey("another-signing-key-another-signing-key"), payload+"."+sig, "cust-1", now); !errors.Is(err, errQuoteSignature) {
		t.Fatalf("another key: %v", err)
	}
	for _, bad := range []string{"", "x", "a.b", payload} {
		if _, err := verifyQuote(lockKey, bad, "cust-1", now); err == nil {
			t.Fatalf("%q must not verify", bad)
		}
	}
}

func basketPkg() domain.CheckoutPackage {
	p := pkg("a", 599, cl("l1", "Rice", 2, 1000))
	p.SellerOrgRaw = "org_a"
	return p
}

func TestTheSummaryLocksTheShownTotalAndTheLockCoversTheInputs(t *testing.T) {
	m := newCounting()
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: &fakeReturns{answer: taxable}, TaxMetrics: m, TaxQuoteKey: lockKey,
		Clock: func() time.Time { return time.Unix(1_800_000_000, 0) }}}
	addr := &port.IdentityAddress{ID: "addr-1", State: "TX", PostalCode: "77002", City: "Houston", Country: "US"}
	sum := domain.CheckoutSummary{Currency: "USD", SubtotalMinor: 2000, Packages: []domain.CheckoutPackage{basketPkg()}, CanPlaceOrder: true,
		BlockedLines: []domain.CheckoutBlockedLine{}, TaxLines: []domain.CheckoutTaxLine{}}
	applyCheckoutShipping(&sum, nil)
	s.priceTax(context.Background(), &sum, addr, nil)
	recalcCheckoutTotal(&sum)
	s.issueTaxQuote(shopper, &sum, addr, nil)
	if sum.TaxEstimateStatus != domain.TaxEstimated || sum.TaxQuoteID == "" || sum.TaxQuoteExpiresAt != "2027-01-15T08:15:00Z" {
		t.Fatalf("%+v", sum)
	}
	c, err := verifyQuote(lockKey, sum.TaxQuoteID, "cust-1", time.Unix(1_800_000_000, 0))
	if err != nil || c.TaxMinor != sum.TaxMinor || c.TotalMinor != sum.TotalMinor || sum.TotalMinor != 2000+599+sum.TaxMinor {
		t.Fatalf("lock %+v total %d tax %d: %v", c, sum.TotalMinor, sum.TaxMinor, err)
	}
	// the same inputs hash the same, a different coupon, address or delivery option does not
	same := quoteInputsFor(sum.Packages, addr, "", func(p domain.CheckoutPackage) string { return "carrier" })
	if same.hash() != c.Inputs {
		t.Fatalf("Place order must reproduce the lock's inputs")
	}
	for name, changed := range map[string]taxQuoteInputs{
		"coupon":   quoteInputsFor(sum.Packages, addr, "SAVE5", func(domain.CheckoutPackage) string { return "carrier" }),
		"delivery": quoteInputsFor(sum.Packages, addr, "", func(domain.CheckoutPackage) string { return "pickup" }),
		"address":  quoteInputsFor(sum.Packages, &port.IdentityAddress{ID: "addr-1", State: "NY", PostalCode: "10001", City: "New York"}, "", func(domain.CheckoutPackage) string { return "carrier" }),
	} {
		if changed.hash() == c.Inputs {
			t.Fatalf("a changed %s must change the lock's inputs", name)
		}
	}
	if m.locks["issued"] != 1 {
		t.Fatalf("%v", m.locks)
	}
	// no estimate, no lock
	sum.TaxEstimateStatus = domain.TaxUnavailable
	s.issueTaxQuote(shopper, &sum, addr, nil)
	if sum.TaxQuoteID != "" {
		t.Fatal("an order without an estimate has no lock")
	}
}

func TestAMissingEstimateIsNeverSilentAndNeverSaysLater(t *testing.T) {
	cases := map[string]struct {
		d      ShopperDeps
		addr   *port.IdentityAddress
		reason string
		metric string
		status string
		label  string
	}{
		"no address": {ShopperDeps{Returns: &fakeReturns{answer: taxable}}, nil, domain.TaxReasonNeedsZip, "needs_zip", domain.TaxNeedsAddress, taxLabelNeedsAddress},
		"address without a state": {ShopperDeps{Returns: &fakeReturns{answer: taxable}}, &port.IdentityAddress{PostalCode: "77002"},
			domain.TaxReasonAddressIncomplete, "address_incomplete", domain.TaxUnavailable, taxLabelAddressIncomplete},
		"address without a ZIP": {ShopperDeps{Returns: &fakeReturns{answer: taxable}}, &port.IdentityAddress{State: "TX"},
			domain.TaxReasonAddressIncomplete, "address_incomplete", domain.TaxUnavailable, taxLabelAddressIncomplete},
		"returns not configured": {ShopperDeps{}, houstonAddr, domain.TaxReasonServiceUnavailable, "returns_not_configured", domain.TaxUnavailable, taxLabelTryAgain},
		"timeout":                {ShopperDeps{Returns: &fakeReturns{err: context.DeadlineExceeded}}, houstonAddr, domain.TaxReasonServiceUnavailable, "tax_returns_timeout", domain.TaxUnavailable, taxLabelTryAgain},
		"breaker open": {ShopperDeps{Returns: &fakeReturns{err: &port.UpstreamProblem{Upstream: "tax-returns", Status: 503, Detail: "tax-returns unavailable: circuit breaker open"}}},
			houstonAddr, domain.TaxReasonServiceUnavailable, "tax_returns_breaker_open", domain.TaxUnavailable, taxLabelTryAgain},
		"refused": {ShopperDeps{Returns: &fakeReturns{err: &port.UpstreamProblem{Upstream: "tax-returns", Status: 400, Code: "tax-returns.invalid-quote"}}},
			houstonAddr, domain.TaxReasonServiceUnavailable, "tax_returns_rejected", domain.TaxUnavailable, taxLabelTryAgain},
		"no scope": {ShopperDeps{Returns: &fakeReturns{err: &port.UpstreamProblem{Upstream: "tax-returns", Status: 403}}},
			houstonAddr, domain.TaxReasonServiceUnavailable, "tax_returns_unauthorised", domain.TaxUnavailable, taxLabelTryAgain},
		"tax-returns down": {ShopperDeps{Returns: &fakeReturns{err: &port.UpstreamProblem{Upstream: "tax-returns", Status: 500}}},
			houstonAddr, domain.TaxReasonServiceUnavailable, "tax_returns_server_error", domain.TaxUnavailable, taxLabelTryAgain},
		"service token": {ShopperDeps{Returns: &fakeReturns{err: errors.New("service token for tax-returns: identity down")}},
			houstonAddr, domain.TaxReasonServiceUnavailable, "service_token_failed", domain.TaxUnavailable, taxLabelTryAgain},
	}
	for name, c := range cases {
		m := newCounting()
		c.d.TaxMetrics = m
		s := &checkoutPaymentServiceImpl{d: c.d}
		sum := domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{pkg("a", 0, cl("l1", "Rice", 1, 1000))}, CanPlaceOrder: true,
			BlockedLines: []domain.CheckoutBlockedLine{}, TaxLines: []domain.CheckoutTaxLine{}}
		s.priceTax(context.Background(), &sum, c.addr, nil)
		if sum.TaxEstimateStatus != c.status || sum.TaxUnavailableReason != c.reason || sum.TaxRateLabel != c.label || sum.CanPlaceOrder || sum.TaxMinor != 0 {
			t.Errorf("%s: %+v", name, sum)
		}
		if m.unavailable[c.metric] != 1 {
			t.Errorf("%s: tax_estimate_unavailable_total{reason=%q} = %v", name, c.metric, m.unavailable)
		}
		for _, banned := range []string{"place your order", "at checkout", "$0"} {
			if strings.Contains(strings.ToLower(sum.TaxRateLabel), banned) {
				t.Errorf("%s: %q must never reach the shopper: %q", name, banned, sum.TaxRateLabel)
			}
		}
	}
}

func TestAnItemWhoseCatalogFactsAreMissingIsNotQuotedWithoutThem(t *testing.T) {
	m := newCounting()
	f := &fakeReturns{answer: taxable}
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: f, TaxMetrics: m}}
	l := cl("l1", "Cookies", 1, 1000)
	l.FactsMissing = true
	sum := domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{pkg("a", 0, l)}, CanPlaceOrder: true,
		BlockedLines: []domain.CheckoutBlockedLine{}, TaxLines: []domain.CheckoutTaxLine{}}
	s.priceTax(context.Background(), &sum, houstonAddr, nil)
	if sum.TaxUnavailableReason != domain.TaxReasonFactsUnavailable || m.unavailable["catalog_facts_missing"] != 1 || len(f.req.Lines) != 0 {
		t.Fatalf("%+v %v", sum, m.unavailable)
	}
}

// ---------------------------------------------------------------- Place order

func placeSvc(o *fakeOrders, m *countingTaxMetrics, require bool) *checkoutPaymentServiceImpl {
	p := basketPkg()
	cart := domain.Cart{Currency: "USD", Packages: []domain.CartPackage{p.CartPackage}}
	for i := range cart.Packages[0].Lines {
		cart.Packages[0].Lines[i].Selected, cart.Packages[0].Lines[i].MaxQuantity = true, 10
	}
	return &checkoutPaymentServiceImpl{d: ShopperDeps{Orders: o, Carts: fakeCarts{cart: cart}, TaxMetrics: m, TaxQuoteKey: lockKey, RequireTaxQuote: require,
		Identity: fakeAddresses{addrs: []port.IdentityAddress{{ID: "addr-1", State: "TX", PostalCode: "77002", City: "Houston"}}},
		Clock:    func() time.Time { return time.Unix(1_800_000_000, 0) }}}
}

func placeReq(token string) domain.PlaceOrderRequest {
	sel, _ := json.Marshal(map[string]string{"packageId": "a", "option": "carrier"})
	return domain.PlaceOrderRequest{AddressID: "addr-1", PaymentMethodID: "pm_123456", DeliverySelections: []json.RawMessage{sel}, TaxQuoteID: token}
}

const idemKey = "0123456789abcdef0123"

func lockFor(t *testing.T, s *checkoutPaymentServiceImpl, tax, total int64, expires time.Time) string {
	t.Helper()
	addr := &port.IdentityAddress{ID: "addr-1", State: "TX", PostalCode: "77002", City: "Houston"}
	in := quoteInputsFor([]domain.CheckoutPackage{basketPkg()}, addr, "", func(domain.CheckoutPackage) string { return "carrier" })
	return signQuote(lockKey, taxQuoteClaims{Subject: "cust-1", Inputs: in.hash(), TaxMinor: tax, TotalMinor: total, Currency: "USD", ExpiresAt: expires.Unix()})
}

func TestPlaceOrderCarriesTheShownTotalToOrders(t *testing.T) {
	o, m := &fakeOrders{}, newCounting()
	s := placeSvc(o, m, true)
	tok := lockFor(t, s, 214, 2813, time.Unix(1_800_000_000, 0).Add(TaxQuoteTTL))
	if _, err := s.Place(context.Background(), shopper, idemKey, placeReq(tok)); err != nil {
		t.Fatal(err)
	}
	g := o.req.TaxGuard
	if g == nil || g.ExpectedTotalMinor != 2813 || g.ExpectedTaxMinor != 214 || !g.InputsMatch || m.locks["honoured"] != 1 {
		t.Fatalf("%+v %v", g, m.locks)
	}
}

func TestPlaceOrderWithALockForOtherInputsStillGuardsTheShownTotal(t *testing.T) {
	o, m := &fakeOrders{}, newCounting()
	s := placeSvc(o, m, true)
	tok := lockFor(t, s, 214, 2813, time.Unix(1_800_000_000, 0).Add(TaxQuoteTTL))
	req := placeReq(tok)
	req.CouponCode = "SAVE5" // not the coupon the lock was issued for
	if _, err := s.Place(context.Background(), shopper, idemKey, req); err != nil {
		t.Fatal(err)
	}
	if g := o.req.TaxGuard; g == nil || g.ExpectedTotalMinor != 2813 || g.InputsMatch || m.locks["inputs_changed"] != 1 {
		t.Fatalf("%+v %v", g, m.locks)
	}
}

func TestPlaceOrderWithAnExpiredLockKeepsTheGuardButIsNotCountedAsAMatch(t *testing.T) {
	o, m := &fakeOrders{}, newCounting()
	s := placeSvc(o, m, true)
	tok := lockFor(t, s, 214, 2813, time.Unix(1_800_000_000, 0).Add(-time.Minute))
	if _, err := s.Place(context.Background(), shopper, idemKey, placeReq(tok)); err != nil {
		t.Fatal(err)
	}
	if g := o.req.TaxGuard; g == nil || g.ExpectedTotalMinor != 2813 || g.InputsMatch || m.locks["expired"] != 1 {
		t.Fatalf("%+v %v", g, m.locks)
	}
}

func TestPlaceOrderWithoutALockIsRefusedWhenRequiredAndCountedWhenNot(t *testing.T) {
	for _, tok := range []string{"", "garbage", "a.b"} {
		o, m := &fakeOrders{}, newCounting()
		_, err := placeSvc(o, m, true).Place(context.Background(), shopper, idemKey, placeReq(tok))
		p, ok := domain.AsProblem(err)
		if !ok || p.Code != domain.CodeCheckoutQuoteRequired || p.Status != http.StatusUnprocessableEntity || o.req.AddressID != "" {
			t.Fatalf("%q: %v", tok, err)
		}
	}
	o, m := &fakeOrders{}, newCounting()
	if _, err := placeSvc(o, m, false).Place(context.Background(), shopper, idemKey, placeReq("")); err != nil || o.req.TaxGuard != nil || m.locks["missing"] != 1 {
		t.Fatalf("an older client still places: %v %+v %v", err, o.req.TaxGuard, m.locks)
	}
}

func TestPlaceOrderToAnAddressWithoutAStateSaysSoBeforeOrdersIsCalled(t *testing.T) {
	o := &fakeOrders{}
	s := placeSvc(o, newCounting(), false)
	s.d.Identity = fakeAddresses{addrs: []port.IdentityAddress{{ID: "addr-1", PostalCode: "77002", City: "Houston"}}}
	_, err := s.Place(context.Background(), shopper, idemKey, placeReq(""))
	p, ok := domain.AsProblem(err)
	if !ok || p.Errors["addressId"] != "Add your state and ZIP code to this address before you place your order." || o.req.AddressID != "" {
		t.Fatalf("%v", err)
	}
}

func TestATotalThatWouldBeHigherIsRefusedWithTheNewNumbersAndAFreshLock(t *testing.T) {
	o, m := &fakeOrders{}, newCounting()
	o.fail = &port.UpstreamProblem{Upstream: "orders", Status: http.StatusConflict, Code: "orders.total-changed", Extra: map[string]any{
		"expectedTotalMinor": float64(2813), "newTotalMinor": float64(2900), "newTaxMinor": float64(301), "newShippingMinor": float64(599),
		"newItemsMinor": float64(2000), "newDiscountMinor": float64(0), "currency": "USD"}}
	s := placeSvc(o, m, true)
	tok := lockFor(t, s, 214, 2813, time.Unix(1_800_000_000, 0).Add(TaxQuoteTTL))
	_, err := s.Place(context.Background(), shopper, idemKey, placeReq(tok))
	p, ok := domain.AsProblem(err)
	if !ok || p.Status != http.StatusConflict || p.Code != domain.CodeCheckoutTotalChanged {
		t.Fatalf("%v", err)
	}
	if p.Extra["previousTotalMinor"] != int64(2813) || p.Extra["totalMinor"] != int64(2900) || p.Extra["taxMinor"] != int64(301) || p.Extra["currency"] != "USD" {
		t.Fatalf("%v", p.Extra)
	}
	fresh, _ := p.Extra["taxQuoteId"].(string)
	c, verr := verifyQuote(lockKey, fresh, "cust-1", time.Unix(1_800_000_000, 0))
	if verr != nil || c.TotalMinor != 2900 || c.TaxMinor != 301 {
		t.Fatalf("the new lock carries orders' numbers: %+v %v", c, verr)
	}
	if strings.Contains(p.Detail, "tax is calculated") || m.locks["total_changed"] != 1 {
		t.Fatalf("%q %v", p.Detail, m.locks)
	}
}

// ---------------------------------------------------------------- estimate before an address exists

func TestTheCartEstimateForAZipUsesTheSameQuote(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	cart := domain.Cart{Currency: "USD", Packages: []domain.CartPackage{basketPkg().CartPackage}}
	cart.Packages[0].Lines[0].Selected, cart.Packages[0].Lines[0].MaxQuantity = true, 10
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: f, Carts: fakeCarts{cart: cart},
		ZipLookup: func(_ context.Context, zip string) (string, string, error) {
			if zip == "00000" {
				return "", "", port.ErrNotFound
			}
			return "Houston", "tx", nil
		}}}
	got, err := s.TaxEstimate(context.Background(), shopper, "77002", nil)
	if err != nil || got.TaxEstimateStatus != domain.TaxEstimated || got.TaxMinor != 214 || got.TaxJurisdictionLabel != "Houston, TX" || got.TaxZip != "77002" {
		t.Fatalf("%+v %v", got, err)
	}
	if f.req.ShipTo != (port.UpTaxShipTo{Country: "US", State: "TX", PostalCode: "77002", City: "Houston"}) || f.req.Lines[0].ShippingMinor != 599 {
		t.Fatalf("%+v", f.req)
	}
	// nothing typed yet: the answer is the input to show, never $0.00
	if none, err := s.TaxEstimate(context.Background(), shopper, "", nil); err != nil || none.TaxEstimateStatus != domain.TaxNeedsAddress || none.TaxRateLabel != taxLabelNeedsAddress || none.TaxMinor != 0 {
		t.Fatalf("%+v %v", none, err)
	}
	for _, bad := range []string{"7700", "abcde", "00000"} {
		if _, err := s.TaxEstimate(context.Background(), shopper, bad, nil); err == nil {
			t.Fatalf("%q must be refused", bad)
		}
	}
}

func TestTheCartEstimateWithoutAZipUsesTheSavedAddressAndSaysWhyWhenItCannot(t *testing.T) {
	cart := domain.Cart{Currency: "USD", Packages: []domain.CartPackage{basketPkg().CartPackage}}
	cart.Packages[0].Lines[0].Selected, cart.Packages[0].Lines[0].MaxQuantity = true, 10
	mk := func(addrs ...port.IdentityAddress) *checkoutPaymentServiceImpl {
		return &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: &fakeReturns{answer: taxable}, Carts: fakeCarts{cart: cart}, Identity: fakeAddresses{addrs: addrs}}}
	}
	houston := port.IdentityAddress{ID: "a", State: "TX", PostalCode: "77002", City: "Houston"}
	def := houston
	def.IsDefault = true
	got, err := mk(def).TaxEstimate(context.Background(), shopper, "", nil)
	if err != nil || got.TaxEstimateStatus != domain.TaxEstimated || got.TaxZip != "77002" || got.TaxJurisdictionLabel != "Houston, TX" || got.TaxPlaceSource != domain.TaxPlaceSavedAddress {
		t.Fatalf("%+v %v", got, err)
	}
	// no address is marked default: the first one that can be taxed is used, the shopper is not asked for a ZIP code
	if got, err := mk(port.IdentityAddress{ID: "b", City: "Nowhere"}, houston).TaxEstimate(context.Background(), shopper, "", nil); err != nil || got.TaxEstimateStatus != domain.TaxEstimated || got.TaxZip != "77002" {
		t.Fatalf("%+v %v", got, err)
	}
	// an address that cannot be taxed is said to be incomplete (never the ZIP prompt)
	for name, s := range map[string]*checkoutPaymentServiceImpl{
		"default without a state": mk(port.IdentityAddress{ID: "a", PostalCode: "77002", IsDefault: true}, houston),
		"only an incomplete one":  mk(port.IdentityAddress{ID: "a", State: "TX"}),
	} {
		none, err := s.TaxEstimate(context.Background(), shopper, "", nil)
		if err != nil || none.TaxEstimateStatus != domain.TaxUnavailable || none.TaxUnavailableReason != domain.TaxReasonAddressIncomplete || none.TaxMinor != 0 {
			t.Fatalf("%s: %+v %v", name, none, err)
		}
	}
	// the address book could not be read: Retry, never the ZIP prompt
	broken := &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: &fakeReturns{answer: taxable}, Carts: fakeCarts{cart: cart}, Identity: fakeAddresses{err: errors.New("identity down")}}}
	if none, err := broken.TaxEstimate(context.Background(), shopper, "", nil); err != nil || none.TaxEstimateStatus != domain.TaxUnavailable || none.TaxUnavailableReason != domain.TaxReasonServiceUnavailable || none.TaxMinor != 0 {
		t.Fatalf("%+v %v", none, err)
	}
	// a new shopper with no address, and a guest, are asked for a ZIP code
	if none, err := mk().TaxEstimate(context.Background(), shopper, "", nil); err != nil || none.TaxEstimateStatus != domain.TaxNeedsAddress || none.TaxUnavailableReason != domain.TaxReasonNeedsZip || none.TaxMinor != 0 {
		t.Fatalf("%+v %v", none, err)
	}
	if none, _ := mk(def).TaxEstimate(context.Background(), &domain.Caller{}, "", nil); none.TaxEstimateStatus != domain.TaxNeedsAddress {
		t.Fatalf("%+v", none)
	}
}

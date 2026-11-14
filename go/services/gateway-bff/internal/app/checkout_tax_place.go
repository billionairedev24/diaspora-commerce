package app

import (
	"context"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Results of checkout_tax_quote_lock_total{result}.
const (
	lockIssued         = "issued"
	lockHonoured       = "honoured"
	lockInputsChanged  = "inputs_changed"
	lockExpired        = "expired"
	lockInvalid        = "invalid"
	lockMissing        = "missing"
	lockTotalChanged   = "total_changed"
	lockUnreadableAddr = "address_unreadable"
)

// quoteInputsFor is the lock's binding for the current packages, address, coupon and delivery choice per package.
func quoteInputsFor(pkgs []domain.CheckoutPackage, addr *port.IdentityAddress, coupon string, chosen func(domain.CheckoutPackage) string) taxQuoteInputs {
	in := taxQuoteInputs{Lines: quoteLines(pkgs), Coupon: coupon}
	if addr != nil {
		in.AddressID, in.State, in.PostalCode, in.City = addr.ID, addr.State, addr.PostalCode, addr.City
	}
	for _, p := range pkgs {
		in.Delivery = append(in.Delivery, quoteDeliveryChoice(p, chosen(p)))
	}
	return in
}

// issueTaxQuote locks the estimate the summary shows: a signed taxQuoteId for the shown tax and total, valid for
// TaxQuoteTTL, bound to the cart lines, the address, the delivery options and the coupon. Place order carries it.
func (s *checkoutPaymentServiceImpl) issueTaxQuote(c *domain.Caller, sum *domain.CheckoutSummary, addr *port.IdentityAddress, packageDelivery map[string]string) {
	sum.TaxQuoteID, sum.TaxQuoteExpiresAt = "", ""
	if sum.TaxEstimateStatus != domain.TaxEstimated || len(s.d.TaxQuoteKey) == 0 || addr == nil || c == nil || c.Subject == "" {
		return
	}
	coupon := ""
	if sum.Coupon != nil && sum.Coupon.Applicable {
		coupon = sum.Coupon.Code
	}
	in := quoteInputsFor(sum.Packages, addr, coupon, func(p domain.CheckoutPackage) string {
		o, _ := chosenOption(p, packageDelivery)
		return o.Key
	})
	exp := s.d.Clock.now().Add(TaxQuoteTTL)
	sum.TaxQuoteID = signQuote(s.d.TaxQuoteKey, taxQuoteClaims{Subject: c.Subject, Inputs: in.hash(), TaxMinor: sum.TaxMinor,
		TotalMinor: sum.TotalMinor, Currency: sum.Currency, ExpiresAt: exp.Unix()})
	sum.TaxQuoteExpiresAt = exp.UTC().Format(time.RFC3339)
	s.d.taxMetrics().QuoteLock(lockIssued)
}

// placeAddress is the address the order goes to, from the shopper's address book (nil when it can't be read or the id is
// unknown: orders then says so itself).
func (s *checkoutPaymentServiceImpl) placeAddress(ctx context.Context, c *domain.Caller, id string) *port.IdentityAddress {
	addrs, err := s.d.Identity.Addresses(ctx, c.BearerToken)
	if err != nil {
		s.d.log().WarnContext(ctx, "address book unavailable at place order; the tax lock is checked without the address", "error", err)
		return nil
	}
	for i := range addrs {
		if addrs[i].ID == id {
			return &addrs[i]
		}
	}
	return nil
}

// addressIncompleteProblem refuses an order to an address that has no state or ZIP code: orders could not quote its tax.
func addressIncompleteProblem() *domain.Problem {
	return domain.FieldProblem(domain.CodeValidationFailed, "Check your address", "addressId",
		"Add your state and ZIP code to this address before you place your order.")
}

// guardPlace turns the shopper's taxQuoteId into what orders enforces: never charge more than the total shown. It returns
// nil when no lock came with the order (older clients; refused instead when RequireTaxQuote is on). An expired or edited
// lock is still a total the shopper saw, so the guard still holds with it; its expiry only decides how the lock is counted
// (docs/engineering/checkout-tax-estimate.md).
func (s *checkoutPaymentServiceImpl) guardPlace(ctx context.Context, c *domain.Caller, req domain.PlaceOrderRequest, sels []port.UpDeliverySelection,
	pkgs []domain.CheckoutPackage, addr *port.IdentityAddress) (*port.UpTaxGuard, error) {
	m := s.d.taxMetrics()
	token := strings.TrimSpace(req.TaxQuoteID)
	if token == "" {
		m.QuoteLock(lockMissing)
		if s.d.RequireTaxQuote {
			return nil, domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeCheckoutQuoteRequired, "Refresh your total",
				"Reload the checkout to see your current total, then place your order.")
		}
		return nil, nil
	}
	claims, err := verifyQuote(s.d.TaxQuoteKey, token, c.Subject, s.d.Clock.now())
	expired := errors.Is(err, errQuoteExpired)
	if err != nil && !expired {
		m.QuoteLock(lockInvalid)
		s.d.log().WarnContext(ctx, "tax quote lock refused", "reason", err.Error())
		if s.d.RequireTaxQuote {
			return nil, domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeCheckoutQuoteRequired, "Refresh your total",
				"Reload the checkout to see your current total, then place your order.")
		}
		return nil, nil
	}
	chosen := map[string]string{}
	for _, sel := range sels {
		chosen[sel.PackageID] = sel.Option
	}
	in := quoteInputsFor(pkgs, addr, req.CouponCode, func(p domain.CheckoutPackage) string { return chosen[p.PackageID] })
	match := addr != nil && in.hash() == claims.Inputs
	switch {
	case expired:
		m.QuoteLock(lockExpired)
	case !match && addr == nil:
		m.QuoteLock(lockUnreadableAddr)
	case !match:
		m.QuoteLock(lockInputsChanged)
	default:
		m.QuoteLock(lockHonoured)
	}
	return &port.UpTaxGuard{ExpectedTotalMinor: claims.TotalMinor, ExpectedTaxMinor: claims.TaxMinor, InputsMatch: match && !expired}, nil
}

// totalChangedProblem is the 409 for an order that would cost more than the total shown: nothing was charged, the new
// numbers (orders') and, with a key, a fresh lock for them come with it, and the storefront asks the shopper to confirm
// before paying. expected is the total the shopper saw (0 when unknown).
func totalChangedProblem(key []byte, now time.Time, subject string, p *port.UpstreamProblem, expected int64, err error) *domain.Problem {
	num := func(k string) int64 { return numOf(p.Extra[k]) }
	cur := firstNonEmpty(strOf(p.Extra["currency"]), domain.Currency)
	pr := domain.NewProblem(http.StatusConflict, domain.CodeCheckoutTotalChanged, "Your total changed",
		"Your total changed before we could charge you. Check the new total, then place your order again.")
	extra := map[string]any{"previousTotalMinor": firstPositiveMinor(expected, num("expectedTotalMinor")), "totalMinor": num("newTotalMinor"), "taxMinor": num("newTaxMinor"),
		"shippingMinor": num("newShippingMinor"), "subtotalMinor": num("newItemsMinor"), "discountMinor": num("newDiscountMinor"), "currency": cur}
	if len(key) > 0 && subject != "" {
		// The new numbers are orders' own, so a lock for them is what the shopper confirms with. It carries the numbers and
		// the expiry; the storefront refreshes the summary to get a lock bound to the inputs on screen.
		exp := now.Add(TaxQuoteTTL)
		extra["taxQuoteId"] = signQuote(key, taxQuoteClaims{Subject: subject, TaxMinor: num("newTaxMinor"), TotalMinor: num("newTotalMinor"),
			Currency: cur, ExpiresAt: exp.Unix()})
		extra["taxQuoteExpiresAt"] = exp.UTC().Format(time.RFC3339)
	}
	pr.Extra = extra
	return pr.WithCause(err)
}

func firstPositiveMinor(vals ...int64) int64 {
	for _, v := range vals {
		if v > 0 {
			return v
		}
	}
	return 0
}

func numOf(v any) int64 {
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int64:
		return n
	case int:
		return int64(n)
	}
	return 0
}

func strOf(v any) string {
	s, _ := v.(string)
	return s
}

// ---------------------------------------------------------------- estimate before there is an address (cart, new shopper)

var zipOnly = regexp.MustCompile(`^\d{5}(-\d{4})?$`)

// TaxEstimate is the cart page's estimate before an address is chosen: the same tax-returns quote as the checkout, for the
// selected cart lines at each package's first delivery option. The place is the ZIP code the shopper typed, or, with no ZIP
// code, a signed-in shopper's saved default address. No coupon, membership or delivery choice is known yet, so the final tax
// can differ; the checkout shows the estimate that is locked. With no place at all the answer is NEEDS_ADDRESS (the client
// shows a ZIP code input), never an amount.
func (s *checkoutPaymentServiceImpl) TaxEstimate(ctx context.Context, c *domain.Caller, zip string, packageDelivery map[string]string) (domain.CartTaxEstimate, error) {
	zip = strings.TrimSpace(zip)
	out := domain.CartTaxEstimate{TaxEstimateStatus: domain.TaxNeedsAddress, TaxUnavailableReason: domain.TaxReasonNeedsZip, TaxRateLabel: taxLabelNeedsAddress,
		TaxLines: []domain.CheckoutTaxLine{}, Currency: domain.Currency}
	var dest *port.IdentityAddress
	precision := ""
	switch {
	case zip == "":
		var why string
		if dest, why = s.placeResolver().SavedAddress(ctx, c); dest != nil {
			out.TaxPlaceSource = domain.TaxPlaceSavedAddress
			break
		}
		if why == domain.TaxReasonServiceUnavailable {
			out.TaxEstimateStatus, out.TaxUnavailableReason, out.TaxRateLabel = domain.TaxUnavailable, why, taxLabelTryAgain
			s.d.taxMetrics().EstimateUnavailable(reasonServerError)
			return out, nil
		}
		// No usable saved address (a guest, no address book, or an address without a state or ZIP): the place the site
		// already knows, in priority order: the place the shopper chose, then the one detected from the edge, then the
		// market's default. A real ZIP comes with each of them; a place known only by city carries the CITY precision.
		var placeSource string
		if dest, placeSource, precision = s.placeResolver().KnownPlace(ctx, c); dest == nil {
			if why == domain.TaxReasonAddressIncomplete {
				out.TaxEstimateStatus, out.TaxUnavailableReason, out.TaxRateLabel = domain.TaxUnavailable, why, taxLabelAddressIncomplete
				s.d.taxMetrics().EstimateUnavailable(reasonAddressIncomplete)
			}
			return out, nil
		}
		out.TaxPlaceSource = placeSource
	case !zipOnly.MatchString(zip):
		return out, domain.ValidationProblem(map[string]string{"zip": "Enter a 5-digit ZIP code"})
	case s.d.ZipLookup == nil:
		return out, domain.NewProblem(http.StatusServiceUnavailable, domain.CodeUpstreamError, "Try again", "We couldn't look up that ZIP code right now. Try again.")
	}
	cart, err := s.d.Carts.Get(ctx, c)
	if err != nil {
		return out, err
	}
	pkgs, err := selected(cart)
	if err != nil {
		return out, err
	}
	if dest == nil {
		city, state, err := s.d.ZipLookup(ctx, zip[:5])
		if err != nil || strings.TrimSpace(state) == "" {
			if errors.Is(err, port.ErrNotFound) || (err == nil && strings.TrimSpace(state) == "") {
				return out, domain.ValidationProblem(map[string]string{"zip": "We don't recognise that ZIP code"})
			}
			s.d.log().ErrorContext(ctx, "ZIP lookup failed for the tax estimate", "error", err)
			return out, domain.NewProblem(http.StatusServiceUnavailable, domain.CodeUpstreamError, "Try again", "We couldn't look up that ZIP code right now. Try again.")
		}
		dest = &port.IdentityAddress{Country: "US", State: strings.ToUpper(strings.TrimSpace(state)), PostalCode: zip, City: city}
		out.TaxPlaceSource = domain.TaxPlaceZip
	}
	sum := domain.CheckoutSummary{Packages: pkgs, Currency: cart.Currency, CanPlaceOrder: true, BlockedLines: []domain.CheckoutBlockedLine{}, TaxLines: []domain.CheckoutTaxLine{}}
	for _, p := range pkgs {
		sum.SubtotalMinor += p.SubtotalMinor
	}
	// The cart's own delivery estimate is each package's first option; pre-order packages their window fee.
	applyCheckoutShipping(&sum, packageDelivery)
	s.priceTaxAt(ctx, &sum, dest, precision, packageDelivery)
	recalcCheckoutTotal(&sum)
	placePrecision := ""
	if out.TaxPlaceSource != "" {
		placePrecision = firstNonEmpty(precision, domain.PrecisionZip)
	}
	return domain.CartTaxEstimate{TaxEstimateStatus: sum.TaxEstimateStatus, TaxUnavailableReason: sum.TaxUnavailableReason, TaxMinor: sum.TaxMinor,
		TaxRateLabel: sum.TaxRateLabel, TaxJurisdictionLabel: sum.TaxJurisdictionLabel, TaxZip: sum.TaxZip, TaxLines: sum.TaxLines,
		BlockedLines: sum.BlockedLines, TaxPlaceSource: out.TaxPlaceSource, TaxPlacePrecision: placePrecision, SubtotalMinor: sum.SubtotalMinor, ShippingMinor: sum.ShippingMinor, TotalMinor: sum.TotalMinor, Currency: firstNonEmpty(sum.Currency, domain.Currency)}, nil
}

// placeResolver is the shopper's place in the one priority order every surface uses (the injected resolver, else one built
// from this service's own address book, location and ZIP lookup).
func (s *checkoutPaymentServiceImpl) placeResolver() PlaceResolver {
	if s.d.Places != nil {
		return s.d.Places
	}
	return NewPlaceResolver(PlaceDeps{Identity: s.d.Identity, Location: s.d.Location, ZipLookup: s.d.ZipLookup, Log: s.d.log()})
}

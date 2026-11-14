package app

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// TaxMetrics counts what the checkout's tax estimate could not do and how its lock is used. Nil in ShopperDeps is fine
// (a deployment without metrics); the Prometheus implementation is adapter/taxmetrics.
type TaxMetrics interface {
	// EstimateUnavailable is one summary that had no tax estimate (tax_estimate_unavailable_total{reason}).
	EstimateUnavailable(reason string)
	// QuoteLock is one use of a taxQuoteId at Place order (checkout_tax_quote_lock_total{result}).
	QuoteLock(result string)
}

// Reasons of tax_estimate_unavailable_total. "needs_zip", "address_incomplete", "blocked_items" and "no_items" are states
// the shopper fixes; the others are failures somebody must look at (the alert is on those).
const (
	reasonNeedsZip          = "needs_zip"
	reasonAddressIncomplete = "address_incomplete"
	reasonBlockedItems      = "blocked_items"
	reasonNoItems           = "no_items"
	reasonNotConfigured     = "returns_not_configured"
	reasonFactsMissing      = "catalog_facts_missing"
	reasonTokenFailed       = "service_token_failed"
	reasonTimeout           = "tax_returns_timeout"
	reasonBreakerOpen       = "tax_returns_breaker_open"
	reasonUnauthorised      = "tax_returns_unauthorised"
	reasonRejected          = "tax_returns_rejected"
	reasonServerError       = "tax_returns_server_error"
	reasonOther             = "tax_returns_other"
)

// Plain words for the tax row when there is no estimate. Never "calculated when you place your order": the shopper is
// asked for what is missing, or told plainly that it didn't work and to try again (the storefront words the row from
// taxEstimateStatus and taxUnavailableReason; these are the matching labels for clients that only read taxRateLabel).
const (
	taxLabelNeedsAddress      = "Enter your ZIP code to see tax"
	taxLabelAddressIncomplete = "Add your state and ZIP code to this address to see tax"
	taxLabelBlockedItems      = "Remove the items we can't sell yet to see your tax"
	taxLabelNoItems           = "Choose items to see tax"
	taxLabelTryAgain          = "We couldn't work out your tax right now. Try again."
	taxReasonNoItems          = "NO_ITEMS"
)

func (d ShopperDeps) taxMetrics() TaxMetrics {
	if d.TaxMetrics == nil {
		return noTaxMetrics{}
	}
	return d.TaxMetrics
}

type noTaxMetrics struct{}

func (noTaxMetrics) EstimateUnavailable(string) {}
func (noTaxMetrics) QuoteLock(string)           {}

// taxDestination is where tax is worked out for: the delivery address as the address book holds it, which is also what
// orders sends to tax-returns when the order is placed (a pickup order is taxed at the delivery address too; see
// docs/engineering/checkout-tax-estimate.md).
type taxDestination struct {
	Country, State, PostalCode, City string
	// Precision is CITY when the ZIP was resolved from a city name (tax-returns reports it back); empty means ZIP.
	Precision string
}

func destinationOf(addr *port.IdentityAddress) taxDestination {
	return taxDestination{Country: firstNonEmpty(addr.Country, "US"), State: addr.State, PostalCode: addr.PostalCode, City: addr.City}
}

// taxBasis is everything the quote was built from; the same builder serves the summary, the cart estimate and the tests.
type taxBasis struct {
	req            port.UpTaxQuoteRequest
	shippingByLine map[string]int64
	pkgOf          map[string]domain.CheckoutPackage
	lineOf         map[string]domain.CartLine
	method         ShippingAllocation
}

// buildTaxQuote builds the tax-returns request for the summary's packages exactly as orders builds it at order time
// (CheckoutTaxImpl): one line per cart line with the variant ATIN, the seller org as the cart service gave it, the
// product type, quantity, unit price, the coupon discount of the line's offer, the seller's behaviour facts, and the
// line's share of its package's delivery charge (after the membership waiver and the coupon's shipping discount,
// allocated by the configured method). Both sides are pinned to contracts/fixtures/checkout-tax-parity.json.
func (s *checkoutPaymentServiceImpl) buildTaxQuote(sum *domain.CheckoutSummary, dest taxDestination, packageDelivery map[string]string) taxBasis {
	method := s.shippingAllocation()
	byOffer := offerDiscounts(sum.Coupon)
	shipDiscLeft := sum.ShippingDiscountMinor
	b := taxBasis{method: method, shippingByLine: map[string]int64{}, pkgOf: map[string]domain.CheckoutPackage{}, lineOf: map[string]domain.CartLine{}}
	lines := make([]port.UpTaxQuoteLine, 0)
	for _, p := range sum.Packages {
		ship := max(0, packageShippingFee(p, packageDelivery)-p.MembershipWaivedMinor)
		if off := min(shipDiscLeft, ship); off > 0 {
			ship -= off
			shipDiscLeft -= off
		}
		weights := make([]int64, len(p.Lines))
		for i, l := range p.Lines {
			weights[i] = max(0, l.PriceMinor*int64(l.Quantity)-byOffer[l.OfferID])
		}
		alloc := method.Allocate(ship, weights)
		for i, l := range p.Lines {
			b.shippingByLine[l.LineID], b.pkgOf[l.LineID], b.lineOf[l.LineID] = alloc[i], p, l
			behaviour := l.SellerBehaviour
			if behaviour == nil {
				behaviour = map[string]any{}
			}
			lines = append(lines, port.UpTaxQuoteLine{LineID: l.LineID, ATIN: variantATINOf(l), SellerOrgID: quoteSellerOrg(p), ProductType: l.ProductType,
				Quantity: l.Quantity, UnitPriceMinor: l.PriceMinor, DiscountMinor: byOffer[l.OfferID], ShippingMinor: alloc[i], SellerBehaviour: behaviour})
		}
	}
	cur := strings.ToUpper(firstNonEmpty(sum.Currency, domain.Currency))
	b.req = port.UpTaxQuoteRequest{Currency: cur, ShipTo: port.UpTaxShipTo{Country: dest.Country, State: dest.State,
		PostalCode: dest.PostalCode, City: dest.City, Precision: dest.Precision}, Lines: lines}
	return b
}

// variantATINOf is the id a tax quote line carries: the VARIANT ATIN, which tax-returns' classification records (and
// orders' own quote) are keyed by. CartLine.ATIN is the product ATIN used for product-page links and is never sent;
// lines built without a VariantATIN fall back to LineID, which is the variant ATIN by construction.
func variantATINOf(l domain.CartLine) string {
	return firstNonEmpty(l.VariantATIN, l.LineID)
}

// quoteSellerOrg is the seller org orders sends: the cart service's own value, empty for AfroTransact's own items (never
// the display fallback "afrotransact").
func quoteSellerOrg(p domain.CheckoutPackage) string {
	if p.SellerOrgRaw != "" || p.Seller.Kind == domain.SellerKindPlatform {
		return p.SellerOrgRaw
	}
	return p.Seller.ID
}

// taxUnavailable says there is no estimate, why, and stops Place order unless the shopper only has to pick items. It is
// the one place a missing estimate is recorded: a counter by reason and a log line, so an empty tax row is never silent.
// reason is the shopper-facing TaxReason; metric is the counter's label.
func (s *checkoutPaymentServiceImpl) taxUnavailable(ctx context.Context, sum *domain.CheckoutSummary, reason, metric string, cause error) {
	sum.TaxMinor, sum.TaxJurisdictionLabel, sum.TaxZip = 0, "", ""
	sum.TaxQuoteID, sum.TaxQuoteExpiresAt = "", ""
	sum.TaxEstimateStatus, sum.TaxUnavailableReason = domain.TaxUnavailable, reason
	switch reason {
	case domain.TaxReasonNeedsZip:
		sum.TaxEstimateStatus, sum.TaxRateLabel = domain.TaxNeedsAddress, taxLabelNeedsAddress
	case domain.TaxReasonAddressIncomplete:
		sum.TaxRateLabel = taxLabelAddressIncomplete
	case domain.TaxReasonBlockedItems:
		sum.TaxRateLabel = taxLabelBlockedItems
	case taxReasonNoItems:
		sum.TaxRateLabel = taxLabelNoItems
	default:
		sum.TaxRateLabel = taxLabelTryAgain
	}
	sum.CanPlaceOrder = false
	s.d.taxMetrics().EstimateUnavailable(metric)
	log := s.d.log()
	switch metric {
	case reasonNeedsZip, reasonNoItems, reasonBlockedItems, reasonAddressIncomplete:
		log.InfoContext(ctx, "checkout tax estimate not shown", "reason", metric)
	default:
		// A failure, not a shopper state: loud, with the cause, so the empty row has an explanation in the logs.
		log.ErrorContext(ctx, "checkout tax estimate unavailable", "reason", metric, "error", cause)
	}
}

// taxFailureReason classifies a tax-returns call that failed (for the counter and the log).
func taxFailureReason(err error) string {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Status == http.StatusUnauthorized || p.Status == http.StatusForbidden:
			return reasonUnauthorised
		case p.Status == http.StatusBadRequest || p.Status == http.StatusUnprocessableEntity:
			return reasonRejected
		case p.Status >= 500 && strings.Contains(strings.ToLower(p.Detail+err.Error()), "circuit breaker"):
			return reasonBreakerOpen
		case p.Status >= 500:
			return reasonServerError
		}
	}
	switch {
	case strings.Contains(err.Error(), "service token"):
		return reasonTokenFailed
	case errors.Is(err, context.DeadlineExceeded):
		return reasonTimeout
	case strings.Contains(strings.ToLower(err.Error()), "circuit breaker"):
		return reasonBreakerOpen
	case strings.Contains(strings.ToLower(err.Error()), "timeout"), strings.Contains(strings.ToLower(err.Error()), "deadline"):
		return reasonTimeout
	}
	return reasonOther
}

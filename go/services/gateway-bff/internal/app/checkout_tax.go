package app

import (
	"context"
	"fmt"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// blockedAction is the one thing a shopper can do about a blocked line.
const blockedAction = "REMOVE"

// shippingAllocationNote is what the summary says about the delivery attribution method until it is approved.
const shippingAllocationNote = "Delivery charges are attributed to the lines of a package by item value. For packages that mix " +
	"taxable and exempt items this method is an open legal decision and must be approved by the tax adviser before production."

// blockedMessage is the shopper's wording for a line that can't be sold yet: the item's name and what happens next,
// no ids and no tax terms. Orders words its refusal the same way.
func blockedMessage(title string) string {
	if strings.TrimSpace(title) == "" {
		title = "an item"
	}
	return "We can't sell " + title + " yet. We're still checking its details. Remove it from your cart to continue, or try again later."
}

// shippingAllocation is the configured method (PROPORTIONAL unless the rollback is set).
func (s *checkoutPaymentServiceImpl) shippingAllocation() ShippingAllocation {
	if s.d.ShippingAllocation == "" {
		return AllocateProportional
	}
	return s.d.ShippingAllocation
}

// priceTax estimates sales tax for the checkout through tax-returns' stored decision per line. It never guesses: a line
// without a definite decision (decisionStatus other than RESOLVED, or no answer for it) is listed in sum.BlockedLines,
// carries no tax, and sets CanPlaceOrder false until the shopper removes it. Each package's delivery charge is
// allocated over its lines exactly as orders does at place time (ShippingAllocation), so a delivery tied only to
// exempt lines is untaxed. Best effort when tax-returns is unreachable: the estimate is left empty and orders
// quotes again when the order is placed (it refuses a line without a decision there too).
func (s *checkoutPaymentServiceImpl) priceTax(ctx context.Context, sum *domain.CheckoutSummary, addr *port.IdentityAddress,
	packageDelivery map[string]string) {
	if s.d.Returns == nil || addr == nil || strings.TrimSpace(addr.State) == "" || strings.TrimSpace(addr.PostalCode) == "" {
		return
	}
	method := s.shippingAllocation()
	byOffer := offerDiscounts(sum.Coupon)
	shipDiscLeft := sum.ShippingDiscountMinor
	lines := make([]port.UpTaxQuoteLine, 0)
	shippingByLine := map[string]int64{}
	pkgOf := map[string]domain.CheckoutPackage{}
	lineOf := map[string]domain.CartLine{}
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
			shippingByLine[l.LineID], pkgOf[l.LineID], lineOf[l.LineID] = alloc[i], p, l
			lines = append(lines, port.UpTaxQuoteLine{LineID: l.LineID, ATIN: l.ATIN, SellerOrgID: p.Seller.ID, ProductType: l.ProductType,
				Quantity: l.Quantity, UnitPriceMinor: l.PriceMinor, DiscountMinor: byOffer[l.OfferID], ShippingMinor: alloc[i]})
		}
	}
	if len(lines) == 0 {
		return
	}
	cur := strings.ToUpper(firstNonEmpty(sum.Currency, domain.Currency))
	req := port.UpTaxQuoteRequest{Currency: cur, ShipTo: port.UpTaxShipTo{Country: firstNonEmpty(addr.Country, "US"),
		State: addr.State, PostalCode: addr.PostalCode, City: addr.City}, Lines: lines}
	q, err := s.d.Returns.TaxQuote(ctx, req)
	if err != nil {
		s.d.log().WarnContext(ctx, "tax quote unavailable; tax shown at order time", "error", err)
		return
	}
	answers := map[string]port.UpTaxLineQuote{}
	for _, l := range q.Lines {
		if _, seen := answers[l.LineID]; !seen {
			answers[l.LineID] = l
		}
	}
	sum.TaxMinor, sum.TaxRateLabel = 0, ""
	for _, ql := range lines {
		a, answered := answers[ql.LineID]
		tl := domain.CheckoutTaxLine{LineID: ql.LineID, ShippingMinor: shippingByLine[ql.LineID]}
		if !answered || !a.Resolved() {
			tl.Status = domain.TaxLineBlocked
			sum.TaxLines = append(sum.TaxLines, tl)
			l, p := lineOf[ql.LineID], pkgOf[ql.LineID]
			sum.BlockedLines = append(sum.BlockedLines, domain.CheckoutBlockedLine{LineID: l.LineID, OfferID: l.OfferID, ATIN: l.ATIN,
				Title: l.Title, PackageID: p.PackageID, Message: blockedMessage(l.Title), Action: blockedAction})
			continue
		}
		tl.Status, tl.TaxMinor = domain.TaxLineTaxable, a.TaxMinor
		if a.Outcome == "EXEMPT" {
			tl.Status = domain.TaxLineExempt
		}
		sum.TaxMinor += a.TaxMinor
		if sum.TaxRateLabel == "" && a.Rate > 0 {
			sum.TaxRateLabel = fmt.Sprintf("%.2f%%", a.Rate*100)
		}
		sum.TaxLines = append(sum.TaxLines, tl)
	}
	if sum.TaxRateLabel == "" && len(sum.BlockedLines) < len(lines) {
		sum.TaxRateLabel = "0.00%"
	}
	sum.TaxJurisdictionLabel = jurisdictionLabel(addr)
	sum.CanPlaceOrder = len(sum.BlockedLines) == 0
	sum.ShippingTaxAllocation = &domain.ShippingTaxAllocation{Method: string(method), Approved: false, Note: shippingAllocationNote}
}

// jurisdictionLabel names where the estimate was worked out, from the delivery address ("Houston, TX").
func jurisdictionLabel(addr *port.IdentityAddress) string {
	city, state := strings.TrimSpace(addr.City), strings.ToUpper(strings.TrimSpace(addr.State))
	if city == "" {
		return state
	}
	return city + ", " + state
}

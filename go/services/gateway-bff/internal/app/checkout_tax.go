package app

import (
	"context"
	"errors"
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

// priceTax estimates sales tax for the checkout through tax-returns' stored decision per line, with the same request orders
// sends when the order is placed (buildTaxQuote). It never guesses: a line without a definite decision (decisionStatus
// other than RESOLVED, or no answer for it) is listed in sum.BlockedLines, carries no tax, and sets CanPlaceOrder false
// until the shopper removes it. Each package's delivery charge is allocated over its lines exactly as orders does at
// place time (ShippingAllocation), so a delivery tied only to exempt lines is untaxed.
//
// There is no silent fallback: when the estimate cannot be made (no ZIP, an address without a state, tax-returns down or
// refusing, catalog facts missing) the summary says why (taxUnavailableReason), stops Place order, counts it in
// tax_estimate_unavailable_total{reason} and logs the cause.
func (s *checkoutPaymentServiceImpl) priceTax(ctx context.Context, sum *domain.CheckoutSummary, addr *port.IdentityAddress,
	packageDelivery map[string]string) {
	switch {
	case addr == nil:
		s.taxUnavailable(ctx, sum, domain.TaxReasonNeedsZip, reasonNeedsZip, nil)
		return
	case strings.TrimSpace(addr.State) == "" || strings.TrimSpace(addr.PostalCode) == "":
		s.taxUnavailable(ctx, sum, domain.TaxReasonAddressIncomplete, reasonAddressIncomplete, nil)
		return
	case s.d.Returns == nil:
		s.taxUnavailable(ctx, sum, domain.TaxReasonServiceUnavailable, reasonNotConfigured, errors.New("tax-returns client is not configured"))
		return
	}
	basis := s.buildTaxQuote(sum, destinationOf(addr), packageDelivery)
	lines, method := basis.req.Lines, basis.method
	shippingByLine, pkgOf, lineOf := basis.shippingByLine, basis.pkgOf, basis.lineOf
	if len(lines) == 0 {
		s.taxUnavailable(ctx, sum, taxReasonNoItems, reasonNoItems, nil)
		return
	}
	for _, l := range lineOf {
		if l.FactsMissing {
			// orders quotes with catalog's facts for the item; quoting without them could differ from the final tax.
			s.taxUnavailable(ctx, sum, domain.TaxReasonFactsUnavailable, reasonFactsMissing, errors.New("catalog facts for "+variantATINOf(l)+" could not be read"))
			return
		}
	}
	q, err := s.d.Returns.TaxQuote(ctx, basis.req)
	if err != nil {
		s.taxUnavailable(ctx, sum, domain.TaxReasonServiceUnavailable, taxFailureReason(err), err)
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
			// Staff-facing only (logs): why tax-returns would not decide the line. Never part of the response.
			s.d.log().WarnContext(ctx, "checkout tax line blocked", "lineId", ql.LineID, "variantAtin", ql.ATIN, "productAtin", l.ATIN,
				"answered", answered, "decisionStatus", a.DecisionStatus, "outcome", a.Outcome, "blockedReason", a.BlockedReason,
				"packageId", p.PackageID, "shipToState", addr.State)
			sum.BlockedLines = append(sum.BlockedLines, domain.CheckoutBlockedLine{LineID: l.LineID, OfferID: l.OfferID, ATIN: l.ATIN,
				Title: l.Title, PackageID: p.PackageID, Message: blockedMessage(l.Title), Action: blockedAction})
			continue
		}
		tl.Status, tl.TaxMinor = domain.TaxLineTaxable, a.TaxMinor
		if a.Outcome == "EXEMPT" {
			// Resolved() above guarantees RESOLVED + EXEMPT: only then does a line carry a reason for having no tax.
			tl.Status, tl.ExemptReason = domain.TaxLineExempt, strings.TrimSpace(a.Explanation)
		}
		sum.TaxMinor += a.TaxMinor
		if sum.TaxRateLabel == "" && a.Rate > 0 {
			sum.TaxRateLabel = fmt.Sprintf("%.2f%%", a.Rate*100)
		}
		sum.TaxLines = append(sum.TaxLines, tl)
	}
	sum.CanPlaceOrder = len(sum.BlockedLines) == 0
	if len(sum.BlockedLines) > 0 {
		// A line without a definite decision: no figure for the basket, only the blocked lines say why.
		s.taxUnavailable(ctx, sum, domain.TaxReasonBlockedItems, reasonBlockedItems, nil)
		return
	}
	// ESTIMATED: the amount is shown (an all-exempt basket is a real $0.00, each exempt line carries its reason); the rate
	// is the first taxable line's, empty when no line is taxable (never "0%").
	sum.TaxEstimateStatus, sum.TaxUnavailableReason = domain.TaxEstimated, ""
	sum.TaxJurisdictionLabel, sum.TaxZip = jurisdictionLabel(addr), addr.PostalCode
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

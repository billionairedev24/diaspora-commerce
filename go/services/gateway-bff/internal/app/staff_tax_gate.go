package app

import (
	"context"
	"net/url"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The tax gate audit: live listings that have a variant without a stored, resolved tax decision. The owner's rule is that a
// product is never live without one, so this list is always empty; the Tax review page shows a banner with its count when it
// is not (docs/engineering/tax-gate-paths.md). Read from catalog (`GET /v1/tax-gate/live-without-decision`) with the staff
// member's token; sellers and categories by name, the reason for each variant in plain words.

// taxVariantStatusLabel is the plain label of the tax status catalog keeps on a variant.
var taxVariantStatusLabel = map[string]string{
	"READY":          "No decision is stored",
	"MISSING_FACTS":  "Answers still needed",
	"PENDING_REVIEW": "Waiting for tax review",
	"BLOCKED":        "Blocked: a bundle must be itemised",
	"NEEDS_APPROVAL": "Category not approved yet",
	"REFINE_CODE":    "Needs a verified code",
	"CONFLICT_OPEN":  "Category on hold",
	"REJECTED":       "Sent back to the seller",
	// tax-returns, the decision checkout reads, has no record of the variant at all.
	"NO_DECISION": "Tax review has no record yet",
}

func (s *staffTaxImpl) LiveWithoutDecision(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	if sc.up.Catalog == nil {
		return nil, domain.UpstreamUnavailable("catalog")
	}
	page, size, err := pageOf(q, 25)
	if err != nil {
		return nil, err
	}
	up := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	var raw any
	if err := sc.get(ctx, sc.up.Catalog, "/v1/tax-gate/live-without-decision", up, &raw); err != nil {
		return nil, taxErr(err)
	}
	list := jpage(raw)
	nameAll(ctx, sc, list.items, sellerRef("ownerOrgId"))
	rows := make([]jobj, 0, len(list.items))
	for _, it := range list.items {
		rows = append(rows, liveWithoutDecisionRow(it))
	}
	total := int64(len(rows))
	if list.total != nil {
		total = *list.total
	}
	// taxReturnsChecked is false when catalog could not ask tax-returns: the list is then only catalog's own check and may be
	// incomplete, so the console must not read an empty list as "all clear".
	checked := true
	if m, ok := raw.(map[string]any); ok {
		if v, present := m["taxReturnsChecked"].(bool); present {
			checked = v
		}
	}
	return jobj{"items": rows, "total": total, "page": page, "size": size, "taxReturnsChecked": checked}, nil
}

func liveWithoutDecisionRow(it jobj) jobj {
	variants := []jobj{}
	for _, v := range objs(it, "variants") {
		status := jstr(v, "taxStatus")
		variants = append(variants, jobj{"atin": jstr(v, "atin"), "sku": jstr(v, "sellerSku"), "status": status,
			"statusLabel": firstNonEmpty(taxVariantStatusLabel[status], domain.Humanize(status)), "why": jstr(v, "reason")})
	}
	return jobj{"atin": jstr(it, "atin"), "title": jstr(it, "title"), "sellerName": jstr(it, "ownerOrgName"),
		"categoryName": jstr(it, "categoryName"), "updatedAt": jstr(it, "updatedAt"), "variants": variants}
}

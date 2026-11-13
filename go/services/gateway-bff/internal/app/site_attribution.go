package app

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// attributedOrdersRemembered bounds the in-process list of recently counted order numbers (a retried request with the same
// idempotency key answers with the same order, which must not be counted twice).
const attributedOrdersRemembered = 4096

// RecordHomeOrder counts one placed order against the home module the shopper last clicked: an order counter and a revenue
// sum in minor units, labelled only by module, variant and locale. There is no new field on the order, no shopper identity and
// nothing stored: the aggregate lives in the metrics registry. The caller has already checked the Analytics consent.
func (s *siteServiceImpl) RecordHomeOrder(_ context.Context, orderNumber string, in domain.HomeOrderAttribution) {
	if s.d.Metrics == nil || !domain.IsModule(in.Module) || in.TotalMinor < 0 {
		return
	}
	if orderNumber != "" && !s.attributionFirstSight(orderNumber) {
		return
	}
	s.d.Metrics.OrderPlaced(in.Module, variantOrControl(in.Variant), normaliseLocale(in.Locale, nil), in.TotalMinor)
}

// attributionFirstSight is true the first time an order number is seen (bounded memory, oldest forgotten first).
func (s *siteServiceImpl) attributionFirstSight(orderNumber string) bool {
	s.attrMu.Lock()
	defer s.attrMu.Unlock()
	if s.attrSeen == nil {
		s.attrSeen = map[string]bool{}
	}
	if s.attrSeen[orderNumber] {
		return false
	}
	s.attrSeen[orderNumber] = true
	s.attrOrder = append(s.attrOrder, orderNumber)
	if len(s.attrOrder) > attributedOrdersRemembered {
		delete(s.attrSeen, s.attrOrder[0])
		s.attrOrder = s.attrOrder[1:]
	}
	return true
}

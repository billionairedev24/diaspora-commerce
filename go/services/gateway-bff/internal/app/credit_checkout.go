package app

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// minCardMinor is the least Stripe can charge (0.50): credit leaves at least this on the card, or pays everything.
// payments applies the same rule when the order is placed and is the authority; this only previews it.
const minCardMinor = 50

// Summary is the checkout page (see summary) plus what the shopper's AfroTransact credit would pay of the order
// (S320). Credit is best effort: without it the page offers the card only.
func (s *checkoutPaymentServiceImpl) Summary(ctx context.Context, c *domain.Caller, addressID string,
	packageDelivery map[string]string, couponCode string) (domain.CheckoutSummary, error) {
	sum, err := s.summary(ctx, c, addressID, packageDelivery, couponCode)
	if err != nil {
		return sum, err
	}
	s.priceCredit(ctx, c, &sum)
	return sum, nil
}

func (s *checkoutPaymentServiceImpl) priceCredit(ctx context.Context, c *domain.Caller, sum *domain.CheckoutSummary) {
	sum.Credit = nil
	if s.d.Credit == nil || !c.Authenticated() || sum.TotalMinor <= 0 {
		return
	}
	bal, err := s.d.Credit.Balance(ctx, c.BearerToken)
	if err != nil {
		s.d.log().InfoContext(ctx, "credit balance unavailable; checkout offers the card only", "error", err)
		return
	}
	if bal.BalanceMinor <= 0 {
		return
	}
	applied, due := creditSplit(bal.BalanceMinor, sum.TotalMinor)
	sum.Credit = &domain.CheckoutCredit{AvailableMinor: bal.BalanceMinor, AppliedMinor: applied, CardDueMinor: due}
}

// creditSplit is how an order total is paid when the shopper uses credit: credit first (up to the balance), the card for
// the rest, never less than 0.50 on the card.
func creditSplit(balance, total int64) (applied, cardDue int64) {
	applied = min(balance, total)
	if rest := total - applied; rest > 0 && rest < minCardMinor {
		applied = max(0, total-minCardMinor)
	}
	return applied, total - applied
}

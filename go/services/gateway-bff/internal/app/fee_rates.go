package app

import (
	"context"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Rates an organisation pays (payments' rate card and effective fees), shaped for Seller Center and Provider Hub.

func planNameOf(code string) *string {
	if code == "" {
		return nil
	}
	n := domain.PlanName(code)
	return &n
}

func appliedRate(r port.AppliedRateResponse, fallbackLabel string) domain.SellerAppliedRate {
	label := fallbackLabel
	if r.Category != "" && r.Category != domain.AllCategories {
		label = domain.FeeCategoryLabel(r.Category)
	}
	return domain.SellerAppliedRate{Category: r.Category, Label: label, Bps: r.Bps, Source: strings.ToUpper(firstNonEmpty(r.Source, domain.RateSourceDefault)),
		PlanCode: optStr(r.PlanCode), PlanName: planNameOf(r.PlanCode), NegotiatedUntil: r.NegotiatedUntil}
}

func sellerNegotiated(list []port.NegotiatedRateResponse) []domain.SellerNegotiatedRate {
	out := []domain.SellerNegotiatedRate{}
	for _, n := range list {
		out = append(out, domain.SellerNegotiatedRate{Type: n.Type, Category: n.Category, Label: domain.FeeCategoryLabel(n.Category), RateBps: n.RateBps, AmountMinor: n.AmountMinor,
			StartsAt: n.StartsAt, EndsAt: n.EndsAt, Status: n.Status})
	}
	return out
}

// sellerRateCard shapes payments' rate card for the organisation's own screens.
func sellerRateCard(c port.RateCardResponse) *domain.SellerRateCard {
	other := "Other categories"
	if c.Kind == domain.FeeKindService {
		other = "Other services"
	}
	out := &domain.SellerRateCard{Kind: firstNonEmpty(c.Kind, domain.FeeKindProduct), Version: c.Version, EffectiveFrom: c.EffectiveFrom,
		PlanCode: optStr(c.PlanCode), PlanName: planNameOf(c.PlanCode), Other: appliedRate(c.OtherRate, other),
		Categories: []domain.SellerAppliedRate{}, Negotiated: sellerNegotiated(c.Negotiated)}
	for _, r := range c.Categories {
		out.Categories = append(out.Categories, appliedRate(r, other))
	}
	return out
}

// rateCardOf reads one kind's rate card; nil when payments cannot say (old payments, outage): screens then omit it.
func rateCardOf(ctx context.Context, fees port.FeesClient, orgID, kind string, at time.Time) *domain.SellerRateCard {
	if fees == nil {
		return nil
	}
	c, err := fees.RateCard(ctx, orgID, kind, at)
	if err != nil || c.Kind == "" {
		return nil
	}
	return sellerRateCard(c)
}

// bookingTakeHomes fills each booking card's take-home with the provider's connection fee for its service category
// (best effort: cards stay without one when payments cannot say).
func bookingTakeHomes(ctx context.Context, fees port.FeesClient, orgID string, now time.Time, cards []domain.BookingCard) {
	if fees == nil || len(cards) == 0 {
		return
	}
	type rate struct {
		r  port.EffectiveFeesResponse
		ok bool
	}
	rates := map[string]rate{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	cats := map[string]bool{}
	for _, c := range cards {
		cats[cardCategory(c)] = true
	}
	for cat := range cats {
		wg.Go(func() {
			r, err := fees.EffectiveKind(ctx, domain.FeeKindService, cat, orgID, now)
			mu.Lock()
			defer mu.Unlock()
			rates[cat] = rate{r: r, ok: err == nil}
		})
	}
	wg.Wait()
	for i := range cards {
		r := rates[cardCategory(cards[i])]
		if !r.ok {
			continue
		}
		th := domain.TakeHomeOf(cards[i].PriceMinor, cards[i].DepositMinor, r.r.CommissionBps, r.r.CardPctBps, r.r.CardFixedMinor,
			strings.ToUpper(firstNonEmpty(r.r.CommissionSource, domain.RateSourceDefault)), cards[i].Currency)
		cards[i].TakeHome = &th
	}
}

func cardCategory(c domain.BookingCard) string {
	if c.ServiceCategory != nil && strings.TrimSpace(*c.ServiceCategory) != "" {
		return domain.NormalizeFeeCategory(*c.ServiceCategory)
	}
	return "services"
}

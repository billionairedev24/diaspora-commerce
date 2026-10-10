package app

import (
	"context"
	"slices"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerFeesService is the Seller Center / Provider Hub's read-only Fees page: the commission the active organisation
// pays per category (its own negotiated overrides included), the FBA fee, the card fee estimate and the next change.
type SellerFeesService interface {
	Fees(ctx context.Context, c *domain.Caller) (domain.SellerFeesView, error)
}

type sellerFeesImpl struct{ r *orgResolver }

var _ SellerFeesService = (*sellerFeesImpl)(nil)

// NewSellerFeesService returns the SellerFeesService.
func NewSellerFeesService(d SellerDeps) SellerFeesService {
	return &sellerFeesImpl{r: newResolver(d)}
}

// maxFeeCategories caps the per-category effective-fee reads of one page view.
const maxFeeCategories = 40

func (s *sellerFeesImpl) Fees(ctx context.Context, c *domain.Caller) (domain.SellerFeesView, error) {
	sc, err := s.r.require(ctx, c, "", "fees", domain.LevelView)
	if err != nil {
		return domain.SellerFeesView{}, err
	}
	fees := s.r.d.Fees
	if fees == nil {
		return domain.SellerFeesView{}, domain.UpstreamUnavailable("payments")
	}
	all, err := fees.Schedules(ctx)
	if err != nil {
		return domain.SellerFeesView{}, mapErr("payments", err)
	}
	now := s.r.d.Clock.now()
	orgID := sc.org.ID
	var active, upcoming *port.FeeScheduleSummary
	for i := range all {
		p := &all[i]
		if p.CancelledAt != nil {
			continue
		}
		if !p.EffectiveFrom.After(now) {
			if active == nil || p.EffectiveFrom.After(active.EffectiveFrom) || (p.EffectiveFrom.Equal(active.EffectiveFrom) && p.Version > active.Version) {
				active = p
			}
		} else if upcoming == nil || p.EffectiveFrom.Before(upcoming.EffectiveFrom) || (p.EffectiveFrom.Equal(upcoming.EffectiveFrom) && p.Version > upcoming.Version) {
			upcoming = p
		}
	}
	if active == nil {
		return domain.SellerFeesView{}, domain.UpstreamUnavailable("payments")
	}
	cats := feeCategoriesFor(orgID, active, upcoming)
	cur, err := s.version(ctx, orgID, cats, now)
	if err != nil {
		return domain.SellerFeesView{}, err
	}
	out := domain.SellerFeesView{Currency: domain.Currency, Current: cur, Changes: []domain.SellerFeeChange{}}
	out.Retail = slices.ContainsFunc(cur.Categories, func(c domain.SellerFeeCategory) bool { return c.Source == "RETAIL" })
	if out.Retail {
		out.Note = strPtr(domain.RetailFeesNote)
	}
	if upcoming != nil {
		next, err := s.version(ctx, orgID, cats, upcoming.EffectiveFrom)
		if err != nil {
			return domain.SellerFeesView{}, err
		}
		out.Upcoming = &next
		out.Changes = domain.SellerFeeChanges(cur, &next)
	}
	return out, nil
}

// feeCategoriesFor: categories the schedules price, the organisation's own override categories and "services".
func feeCategoriesFor(orgID string, versions ...*port.FeeScheduleSummary) []string {
	set := map[string]bool{"services": true}
	for _, v := range versions {
		if v == nil {
			continue
		}
		for k := range v.CategoryBps {
			set[domain.NormalizeFeeCategory(k)] = true
		}
		for _, o := range v.Overrides {
			if c := domain.NormalizeFeeCategory(o.Category); o.OrgID == orgID && c != "" && c != domain.AllCategories {
				set[c] = true
			}
		}
	}
	out := make([]string, 0, len(set))
	for k := range set {
		if k != "" {
			out = append(out, k)
		}
	}
	sort.Strings(out)
	if len(out) > maxFeeCategories {
		out = out[:maxFeeCategories]
	}
	return out
}

// version reads payments' effective fees for every category at one time (concurrently) and a category no schedule
// lists, which gives the default commission (or the organisation's all-category override).
func (s *sellerFeesImpl) version(ctx context.Context, orgID string, cats []string, at time.Time) (domain.SellerFeeVersion, error) {
	const other = "__other__"
	keys := append(slices.Clone(cats), other)
	res := make([]port.EffectiveFeesResponse, len(keys))
	errs := make([]error, len(keys))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for i, k := range keys {
		wg.Go(func() {
			sem <- struct{}{}
			defer func() { <-sem }()
			res[i], errs[i] = s.r.d.Fees.Effective(ctx, k, orgID, at)
		})
	}
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			return domain.SellerFeeVersion{}, mapErr("payments", err)
		}
	}
	base := res[len(res)-1]
	v := domain.SellerFeeVersion{Version: base.Version, EffectiveFrom: base.EffectiveFrom, DefaultCommissionBps: base.CommissionBps,
		Categories: []domain.SellerFeeCategory{}, CardPctBps: base.CardPctBps, CardFixedMinor: base.CardFixedMinor, FbaPerUnitMinor: base.FbaPerUnitMinor,
		ReverseCommissionOnRefund: base.ReverseCommissionOnRefund}
	for i, k := range cats {
		r := res[i]
		v.Categories = append(v.Categories, domain.SellerFeeCategory{Category: k, Label: domain.FeeCategoryLabel(k), CommissionBps: r.CommissionBps,
			Source: strings.ToUpper(firstNonEmpty(r.CommissionSource, "DEFAULT"))})
	}
	return v, nil
}

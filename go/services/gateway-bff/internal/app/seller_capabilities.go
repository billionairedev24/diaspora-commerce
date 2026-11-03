package app

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// capabilityNames are the plan's capabilities in the words of the refusal ("Bulk upload isn't part of your plan").
var capabilityNames = map[string]string{
	domain.CapBulkUpload: "Bulk upload", domain.CapImageLibrary: "The image library", domain.CapCoupons: "Coupons",
	domain.CapAIWriter: "The AI listing writer", domain.CapLola: "Lola",
}

const capabilityMemo = 30 * time.Second

// planMemo remembers what an organisation's plan allows for a few seconds: every bulk-upload call would otherwise read
// billing first.
type planMemo struct {
	mu sync.Mutex
	by map[string]struct {
		e  *domain.PlanEntitlementsView
		at time.Time
	}
}

// entitlements reads what the organisation's plan allows (nil when it has no plan yet).
func (r *orgResolver) entitlements(ctx context.Context, c *domain.Caller, orgID string) (*domain.PlanEntitlementsView, error) {
	r.memo.mu.Lock()
	if m, ok := r.memo.by[orgID]; ok && time.Since(m.at) < capabilityMemo {
		r.memo.mu.Unlock()
		return m.e, nil
	}
	r.memo.mu.Unlock()
	b, err := r.d.Sellers.Billing(ctx, c.BearerToken, orgID)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusNotFound {
			return nil, nil
		}
		return nil, mapErr("seller", err)
	}
	e := entitlementsOf(b.Entitlements)
	r.memo.mu.Lock()
	if r.memo.by == nil {
		r.memo.by = map[string]struct {
			e  *domain.PlanEntitlementsView
			at time.Time
		}{}
	}
	r.memo.by[orgID] = struct {
		e  *domain.PlanEntitlementsView
		at time.Time
	}{e, time.Now()}
	r.memo.mu.Unlock()
	return e, nil
}

// requireCap is require plus: the organisation's plan must include the capability. A capability the plan does not
// include is refused (the Seller Center does not show it either).
func (r *orgResolver) requireCap(ctx context.Context, c *domain.Caller, kind, module, level, capability string) (sellerCtx, error) {
	sc, err := r.require(ctx, c, kind, module, level)
	if err != nil {
		return sc, err
	}
	e, err := r.entitlements(ctx, c, sc.org.ID)
	if err != nil {
		return sc, err
	}
	if !e.Allows(capability) {
		return sc, domain.NewProblem(http.StatusForbidden, domain.CodePlanFeatureRequired, "Not part of your plan",
			capabilityNames[capability]+" isn't part of your plan.")
	}
	return sc, nil
}

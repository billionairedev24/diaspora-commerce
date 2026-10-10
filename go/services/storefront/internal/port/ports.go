// Package port declares the storefront service's outbound interfaces.
package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
)

// StorefrontRepository keeps a draft and the last published snapshot per organisation.
type StorefrontRepository interface {
	Draft(ctx context.Context, orgID string) (domain.Storefront, error)
	// SaveDraft stores the draft if s.Version matches (0 for new) and increments it.
	SaveDraft(ctx context.Context, s *domain.Storefront) error
	// Publish stores the published snapshot atomically with the draft version check.
	Publish(ctx context.Context, s *domain.Storefront) error
	Published(ctx context.Context, slug string) (domain.Storefront, error)
	WithPendingLinks(ctx context.Context, limit int) ([]domain.Storefront, error)
	Ping(ctx context.Context) error
}

// CachePurger invalidates edge-cached public pages after a publish (Cloudflare in cloud environments).
type CachePurger interface {
	Purge(ctx context.Context, urls []string) error
}

// PlanLimits returns the limits of an organisation's plan.
type PlanLimits interface {
	For(planCode string) domain.Limits
}

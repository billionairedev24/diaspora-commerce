package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// MaintenanceStore keeps the maintenance switch where every BFF replica sees it (and where it survives the downstream
// services being down, which is when it is needed).
type MaintenanceStore interface {
	// Get returns the switch; a platform that was never put in maintenance returns the zero value.
	Get(ctx context.Context) (domain.Maintenance, error)
	Put(ctx context.Context, m domain.Maintenance) error
}

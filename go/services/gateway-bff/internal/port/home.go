package port

import (
	"context"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ContentHomeClient reads the dynamic home's settings, the community registry and placements as of an instant.
type ContentHomeClient interface {
	// HomeSettings is the effective settings (defaults merged by content).
	HomeSettings(ctx context.Context) (domain.HomeSettings, error)
	// CommunityRegistry is the registry (canonical keys, aliases, locale labels, optional editorial image).
	CommunityRegistry(ctx context.Context) ([]domain.CommunityEntry, error)
	// PlacementsAt is Placements for an as-of instant (staff preview, S430); never cached.
	PlacementsAt(ctx context.Context, market, zip string, at time.Time) (domain.Placements, error)
}

// HomeVisitState is the server-side memory of one signed-in shopper's home loads: the current visit window and the item
// keys shown in the windows before it (newest first). Keys are 8-character hashes, never product ids.
type HomeVisitState struct {
	Window   string     `json:"window"`
	LastSeen time.Time  `json:"lastSeen"`
	Current  []string   `json:"current"`
	Previous [][]string `json:"previous"`
}

// HomeMemory keeps HomeVisitState per signed-in shopper (key = hashed subject). Guests keep theirs in a first-party cookie.
type HomeMemory interface {
	Load(ctx context.Context, key string) (HomeVisitState, bool, error)
	Save(ctx context.Context, key string, st HomeVisitState, ttl time.Duration) error
}

// HomeMetrics are the low-cardinality home metrics: module (a fixed registry), shown, reason (a fixed enum), variant
// (a few experiment names) and locale (five). Never a product, seller or shopper label.
type HomeMetrics interface {
	Eligibility(module string, shown bool, reason string)
	Mismatch(module string)
	ComposeSeconds(mode string, seconds float64)
	SignalsAge(seconds float64)
	ModuleEvent(module, event, variant, locale string)
}

// WishlistSignals is the hook for a wishlist (S425): the products a signed-in shopper saved. There is no wishlist yet, so
// nothing implements it; when one exists, wiring it into SiteDeps lets saved products rank slightly higher in discovery
// modules. It is read per request and never stored by the home.
type WishlistSignals interface {
	// Saved returns the ATINs the shopper saved (nil for none).
	Saved(ctx context.Context, subject string) ([]string, error)
}

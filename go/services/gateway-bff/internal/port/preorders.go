package port

import (
	"context"
	"encoding/json"
	"io"
)

// Pre-orders (docs/spikes/preorder-design-implementation.md §2): delivery-runs-service owns pre-order windows,
// coverage and the waitlist. The storefront reads the public routes (no sign-in); the staff console relays the staff
// member's token to the staff routes (StaffUpstreams.DeliveryRuns).

// PreorderArea is a place a resolution is asked for: ISO-3166 alpha-2 country, subdivision code and city name.
type PreorderArea struct {
	Country string
	Region  string
	City    string
}

// PreorderWaitlist is a waitlist sign-up (delivery-runs POST /v1/public/subscriptions, double opt-in).
type PreorderWaitlist struct {
	Email string `json:"email"`
	// Area is the coverage key "US/TX" or "US/TX/Lubbock" the shopper wants a pre-order in.
	Area    string `json:"area"`
	Country string `json:"country"`
	Region  string `json:"region"`
	City    string `json:"city,omitempty"`
	Source  string `json:"source"`
}

// PreorderClient calls delivery-runs' public pre-order routes. Answers are relayed as delivery-runs shapes them
// (the gateway-bff contract documents them); effective fees, currency, pickup window and allowed payment methods
// come from delivery-runs' payment settings and the window's overrides.
type PreorderClient interface {
	// Resolve is GET /v1/public/preorders/resolve: the window (stage, countdown, creatives, fees) covering the area.
	Resolve(ctx context.Context, area PreorderArea) (json.RawMessage, error)
	// Open is GET /v1/public/preorders/open: open and upcoming windows for the /pre-orders page.
	Open(ctx context.Context) (json.RawMessage, error)
	// Waitlist signs an email up for the area's next pre-order (always accepted).
	Waitlist(ctx context.Context, w PreorderWaitlist) (json.RawMessage, error)
}

// StaffStream is a file an upstream streams back (CSV exports); the caller closes Body.
type StaffStream struct {
	ContentType        string
	ContentDisposition string
	Body               io.ReadCloser
}

// StaffStreamer is implemented by staff upstreams that can relay a file without buffering it.
type StaffStreamer interface {
	Stream(ctx context.Context, bearer string, call StaffCall) (StaffStream, error)
}

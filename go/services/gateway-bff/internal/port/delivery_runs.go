package port

import (
	"context"
	"encoding/json"
	"net/url"
)

// RunAlertClient calls delivery-runs for the shopper side of S262/S263: delivery-run locations and their pages,
// location subscriptions with consent per channel (double opt-in by email, active at once for a signed-in shopper)
// and the advertorials served to a place. The public routes need no sign-in; the /v1/me routes relay the shopper's
// token. Answers are relayed as delivery-runs shapes them (the gateway-bff contract documents them).
type RunAlertClient interface {
	// Locations is GET /v1/public/locations.
	Locations(ctx context.Context) (json.RawMessage, error)
	// LocationPage is GET /v1/public/locations/{slug}: the location, its upcoming runs, community and share links.
	LocationPage(ctx context.Context, slug string) (json.RawMessage, error)
	// SubscribeEmail is POST /v1/public/subscriptions with a locationId: always 202, the confirmation link is mailed.
	SubscribeEmail(ctx context.Context, locationID, email, source string) (json.RawMessage, error)
	// Confirm is POST /v1/public/subscriptions/confirm with the token of the confirmation link.
	Confirm(ctx context.Context, token string) (json.RawMessage, error)
	// Unsubscribe is POST /v1/public/subscriptions/unsubscribe with the token of a message link; no channels = all.
	Unsubscribe(ctx context.Context, token string, channels []string) (json.RawMessage, error)
	// Mine is GET /v1/me/run-subscriptions.
	Mine(ctx context.Context, bearer string) (json.RawMessage, error)
	// SubscribeMine is POST /v1/me/run-subscriptions: consent for the channels, active at once.
	SubscribeMine(ctx context.Context, bearer, locationID string, channels []string, source string) (json.RawMessage, error)
	// UnsubscribeMine is DELETE /v1/me/run-subscriptions/{locationId}; no channels = all.
	UnsubscribeMine(ctx context.Context, bearer, locationID string, channels []string) (json.RawMessage, error)
	// Advertorials is GET /v1/public/advertorials (zip, lat, lng, country, region, city, visitor, limit).
	Advertorials(ctx context.Context, q url.Values) (json.RawMessage, error)
	// AdvertorialEvent is POST /v1/public/advertorials/{id}/events (VIEW or CLICK; 204).
	AdvertorialEvent(ctx context.Context, id, eventType string) error
}

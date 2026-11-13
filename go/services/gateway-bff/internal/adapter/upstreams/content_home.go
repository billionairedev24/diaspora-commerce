package upstreams

import (
	"context"
	"net/url"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type contentHomeImpl struct {
	c httpclient.Client
}

var _ port.ContentHomeClient = (*contentHomeImpl)(nil)

// NewContentHome returns the content client for the dynamic home. Settings and the registry are cached by the app layer
// (60 s, fake-clock testable), not here, so a change reaches the gateway within that TTL.
func NewContentHome(c httpclient.Client) port.ContentHomeClient { return &contentHomeImpl{c: c} }

func (k *contentHomeImpl) HomeSettings(ctx context.Context) (domain.HomeSettings, error) {
	var out domain.HomeSettings
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/home-settings"}, &out)
	return out, err
}

func (k *contentHomeImpl) CommunityRegistry(ctx context.Context) ([]domain.CommunityEntry, error) {
	var out []domain.CommunityEntry
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/community-registry"}, &out)
	return out, err
}

func (k *contentHomeImpl) PlacementsAt(ctx context.Context, market, zip string, at time.Time) (domain.Placements, error) {
	var out struct {
		Placements []servedPlacement `json:"placements"`
	}
	q := url.Values{"market": {market}, "device": {"DESKTOP"}, "at": {at.UTC().Format(time.RFC3339)}}
	if zip != "" {
		q.Set("zip", zip)
	}
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/placements/resolve", Query: q}, &out)
	if err != nil {
		return domain.Placements{}, err
	}
	return placementsFrom(out.Placements), nil
}

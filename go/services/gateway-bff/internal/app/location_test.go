package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type cityGeo struct {
	port.GeoClient
	cities map[string]port.GeoAddress
}

func (g cityGeo) Zip(_ context.Context, zip string) (port.GeoAddress, error) {
	if a, ok := g.cities[zip]; ok {
		return a, nil
	}
	return port.GeoAddress{}, port.ErrNotFound
}

// cityContent switches cities off the way Markets & locations does (a city listed OFF or PLANNED is not served).
type cityContent struct {
	port.ContentClient
	off  map[string]bool
	asks []string
}

func (c *cityContent) Eligibility(_ context.Context, country, state, zip, city string) (domain.MarketEligibility, error) {
	c.asks = append(c.asks, country+"/"+state+"/"+zip+"/"+city)
	return domain.MarketEligibility{DeliveryAllowed: !c.off[city]}, nil
}

// TestDeliverToAsksMarketsAboutTheCity: the deliver-to check sends geo's city with the ZIP so a city switched off in
// Markets & locations is reported as not serviceable, while other cities of the same state still are.
func TestDeliverToAsksMarketsAboutTheCity(t *testing.T) {
	geo := cityGeo{cities: map[string]port.GeoAddress{
		"77042": {City: "Houston", State: "TX"},
		"79401": {City: "Lubbock", State: "TX"},
	}}
	content := &cityContent{off: map[string]bool{"Lubbock": true}}
	svc := NewLocationService(geo, content, nil, LocationOptions{DefaultZIP: "77042", DefaultCity: "Houston", DefaultState: "TX"})

	houston := svc.Current(context.Background(), &domain.Caller{})
	if !houston.Serviceable || houston.Label != "Houston 77042" {
		t.Errorf("Houston = %+v", houston)
	}
	lubbock := svc.Current(context.Background(), &domain.Caller{Session: &domain.Session{ZIP: "79401"}})
	if lubbock.Serviceable {
		t.Errorf("Lubbock switched off in markets but serviceable: %+v", lubbock)
	}
	if len(content.asks) != 2 || content.asks[0] != "US/TX/77042/Houston" || content.asks[1] != "US/TX/79401/Lubbock" {
		t.Errorf("eligibility asks = %v", content.asks)
	}
}

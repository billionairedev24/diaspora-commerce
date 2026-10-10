package routecache_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/routecache"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

func TestHitsWithinTheGridAndExpires(t *testing.T) {
	now := time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)
	c := routecache.New(time.Hour, 50, 10, func() time.Time { return now })
	from := domain.Point{Lat: 29.7589, Lng: -95.3677}
	to := domain.Point{Lat: 29.6713, Lng: -95.5446}
	r := domain.CachedRoute{Route: domain.Route{Metres: 20000, Seconds: 1500}, Method: domain.MethodRoadOSRM}
	c.Put(from, to, r)
	// ~11 m away: same cell.
	if got, ok := c.Get(domain.Point{Lat: 29.7590, Lng: -95.3677}, to); !ok || got != r {
		t.Fatalf("nearby origin should hit: %+v %v", got, ok)
	}
	// ~550 m away: another cell.
	if _, ok := c.Get(domain.Point{Lat: 29.7639, Lng: -95.3677}, to); ok {
		t.Fatal("far origin should miss")
	}
	now = now.Add(61 * time.Minute)
	if _, ok := c.Get(from, to); ok {
		t.Fatal("expired entry should miss")
	}
}

func TestDisabledAndBounded(t *testing.T) {
	off := routecache.New(0, 50, 10, nil)
	p := domain.Point{Lat: 1, Lng: 1}
	off.Put(p, p, domain.CachedRoute{})
	if _, ok := off.Get(p, p); ok {
		t.Fatal("TTL 0 disables the cache")
	}
	c := routecache.New(time.Hour, 50, 2, nil)
	for i := range 5 {
		c.Put(p, domain.Point{Lat: float64(i), Lng: 1}, domain.CachedRoute{Route: domain.Route{Metres: float64(i)}})
	}
	if got, ok := c.Get(p, domain.Point{Lat: 4, Lng: 1}); !ok || got.Metres != 4 {
		t.Fatalf("latest entry kept: %+v %v", got, ok)
	}
}

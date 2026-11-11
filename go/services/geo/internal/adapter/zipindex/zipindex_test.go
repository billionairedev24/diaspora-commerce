package zipindex_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/zipindex"
)

func TestLookupGeorgetown(t *testing.T) {
	ix, err := zipindex.New()
	if err != nil {
		t.Fatal(err)
	}
	p, ok := ix.Lookup("78626")
	if !ok || p.City != "Georgetown" || p.State != "TX" {
		t.Fatalf("78626 = %+v ok=%v", p, ok)
	}
	if _, ok := ix.Lookup("00000"); ok {
		t.Fatal("00000 must be unknown")
	}
}

func TestNearbyAroundGeorgetownIsTexasOnly(t *testing.T) {
	ix, err := zipindex.New()
	if err != nil {
		t.Fatal(err)
	}
	g, _ := ix.Lookup("78626")
	got := ix.Nearby(g.Lat, g.Lon, 20, 0)
	names := map[string]float64{}
	for _, c := range got {
		if c.State != "TX" {
			t.Fatalf("non-Texas city near Georgetown: %+v", c)
		}
		names[c.City] = c.DistanceMiles
	}
	for _, want := range []string{"Georgetown", "Round Rock", "Hutto", "Pflugerville"} {
		if _, ok := names[want]; !ok {
			t.Errorf("%s missing from %v", want, names)
		}
	}
	if got[0].City != "Georgetown" || got[0].DistanceMiles != 0 {
		t.Errorf("nearest must be Georgetown at 0 mi, got %+v", got[0])
	}
	if _, ok := names["Brooklyn"]; ok {
		t.Error("Brooklyn is not near Georgetown")
	}
}

func TestNearbyLimit(t *testing.T) {
	ix, _ := zipindex.New()
	g, _ := ix.Lookup("78626")
	if got := ix.Nearby(g.Lat, g.Lon, 40, 3); len(got) != 3 {
		t.Fatalf("limit 3 gave %d", len(got))
	}
}

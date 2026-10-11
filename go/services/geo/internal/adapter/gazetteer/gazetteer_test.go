package gazetteer

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

func TestWorldList(t *testing.T) {
	g, err := New()
	if err != nil {
		t.Fatal(err)
	}
	if n := len(g.Countries()); n < 200 {
		t.Fatalf("countries = %d, want the world", n)
	}
	us, regions, ok := g.Regions("us")
	if !ok || us.Name != "United States" || us.RegionLabel != "State" || len(regions) < 50 {
		t.Fatalf("US = %+v, %d regions", us, len(regions))
	}
	tx, cities, ok := g.Cities("US", "tx")
	if !ok || tx.Name != "Texas" || len(cities) < 500 {
		t.Fatalf("TX = %+v, %d cities", tx, len(cities))
	}
	want := map[string]bool{"Lubbock": false, "El Paso": false, "Amarillo": false}
	for _, c := range cities {
		if _, ok := want[c]; ok {
			want[c] = true
		}
	}
	for c, found := range want {
		if !found {
			t.Errorf("Texas has no %s", c)
		}
	}
	if _, _, ok := g.Regions("ZZ"); ok {
		t.Error("ZZ should be unknown")
	}
	if _, _, ok := g.Cities("US", "ZZ"); ok {
		t.Error("US/ZZ should be unknown")
	}
	if _, regions, _ := g.Regions("CA"); len(regions) < 10 {
		t.Errorf("Canada has %d provinces and territories", len(regions))
	}
}

func TestSearch(t *testing.T) {
	g, err := New()
	if err != nil {
		t.Fatal(err)
	}
	hits := g.Search("lubbock", "US", 8)
	if len(hits) == 0 || hits[0].Key != "US/TX/Lubbock" || hits[0].Type != domain.PlaceCityType || hits[0].RegionName != "Texas" {
		t.Fatalf("lubbock = %+v", hits)
	}
	if hits := g.Search("tex", "US", 3); len(hits) == 0 || hits[0].Key != "US/TX" {
		t.Fatalf("tex = %+v", hits)
	}
	if hits := g.Search("mississauga", "US", 8); len(hits) != 0 {
		t.Fatalf("mississauga in US = %+v", hits)
	}
	if hits := g.Search("t", "", 8); len(hits) != 0 {
		t.Fatal("one character searches nothing")
	}
}

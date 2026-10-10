package ziptable_test

import (
	"context"
	"errors"
	"testing"
	"testing/fstest"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/ziptable"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

func load(t *testing.T) ziptable.Table {
	t.Helper()
	tbl, err := ziptable.Load(promise.Files, "data/zip3_regions.csv", "data/fc_transit.csv")
	if err != nil {
		t.Fatal(err)
	}
	return tbl
}

func TestLookup(t *testing.T) {
	tbl := load(t)
	z, err := tbl.Lookup("10001-2233")
	if err != nil || z.ZIP != "10001" || z.ZIP3 != "100" || z.State != "NY" || z.Location.String() != "America/New_York" {
		t.Fatalf("%+v %v", z, err)
	}
	if z, _ := tbl.Lookup("32501"); z.Location.String() != "America/Chicago" {
		t.Fatalf("Florida panhandle is Central time: %v", z.Location)
	}
	for _, bad := range []string{"1234", "ABCDE", "00001"} {
		if _, err := tbl.Lookup(bad); !errors.Is(err, domain.ErrInvalid) {
			t.Errorf("%s: %v", bad, err)
		}
	}
}

func TestTransitTableAndDistanceFallback(t *testing.T) {
	tbl := load(t)
	ctx := context.Background()
	cases := []struct {
		from, to string
		min, max int
		source   string
	}{
		{"77061", "77002", 1, 1, "table"},    // Houston FC → Houston
		{"77061", "10001", 4, 5, "table"},    // Houston FC → New York
		{"08837", "10001", 1, 1, "table"},    // Edison FC → New York
		{"30336", "77002", 2, 3, "table"},    // Atlanta FC → Houston
		{"08837", "96813", 5, 7, "table"},    // Edison FC → Honolulu
		{"60601", "60614", 1, 1, "distance"}, // seller in Chicago → Chicago
		{"60601", "90012", 4, 5, "distance"}, // Chicago → Los Angeles (~1,750 mi)
		{"98101", "99501", 5, 7, "distance"}, // Seattle → Anchorage (non-contiguous)
	}
	for _, c := range cases {
		tr, err := tbl.Transit(ctx, c.from, c.to)
		if err != nil || tr.MinDays != c.min || tr.MaxDays != c.max || tr.Source != c.source {
			t.Errorf("%s→%s: %+v %v, want %d-%d %s", c.from, c.to, tr, err, c.min, c.max, c.source)
		}
	}
}

func TestHaversine(t *testing.T) {
	if d := ziptable.Haversine(29.76, -95.37, 40.75, -73.98); d < 1400 || d > 1440 {
		t.Fatalf("Houston–NYC %.0f mi", d)
	}
}

func TestRejectsMalformedData(t *testing.T) {
	fsys := fstest.MapFS{
		"r.csv": {Data: []byte("zip3_from,zip3_to,state,lat,lon,timezone\n770,775,TX,29.7,-95.3,Not/AZone\n")},
		"t.csv": {Data: []byte("origin_zip3,dest_zip3_from,dest_zip3_to,min_days,max_days\n")},
	}
	if _, err := ziptable.Load(fsys, "r.csv", "t.csv"); err == nil {
		t.Fatal("unknown time zone must fail loading")
	}
}

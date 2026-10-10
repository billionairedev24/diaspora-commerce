package upstreams

import (
	"net/http"
	"testing"
	"time"
)

func TestCacheControl(t *testing.T) {
	h := func(v string) http.Header { return http.Header{"Cache-Control": {v}} }
	cases := []struct {
		cc   string
		want time.Duration
	}{
		{"public, max-age=60", 60 * time.Second},
		{"max-age=600, public", 5 * time.Minute}, // capped by the ceiling
		{"private, max-age=60", 0},
		{"no-store", 0},
		{"no-cache", 0},
		{"public", 0},
		{"", 2 * time.Minute}, // no header: our fallback
	}
	for _, c := range cases {
		if got := ttlFor(h(c.cc), 5*time.Minute, 2*time.Minute); got != c.want {
			t.Fatalf("%q → %v, want %v", c.cc, got, c.want)
		}
	}
}

func TestItemsOfAcceptsArraysAndPages(t *testing.T) {
	items, next, err := itemsOf[map[string]any]([]byte(`[{"id":"a"}]`))
	if err != nil || len(items) != 1 || next != nil {
		t.Fatal(items, next, err)
	}
	items, next, err = itemsOf[map[string]any]([]byte(`{"items":[{"id":"a"},{"id":"b"}],"nextCursor":"c2"}`))
	if err != nil || len(items) != 2 || next == nil || *next != "c2" {
		t.Fatal(items, next, err)
	}
}

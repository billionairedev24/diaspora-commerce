package sellers_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sellers"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestDirectoryLearnsNames(t *testing.T) {
	ctx := context.Background()
	d := sellers.NewMemoryDirectory(map[string]string{"org_r": "AfroTransact Retail"}, "Marketplace seller")
	if n, known, _ := d.DisplayName(ctx, "org_r"); n != "AfroTransact Retail" || !known {
		t.Fatalf("configured name: %q %v", n, known)
	}
	if n, known, _ := d.DisplayName(ctx, "org_x"); n != "Marketplace seller" || known {
		t.Fatalf("fallback: %q %v", n, known)
	}
	d.Remember(ctx, "org_x", "Kemi's Pantry")
	d.Remember(ctx, "org_y", "")
	if n, known, _ := d.DisplayName(ctx, "org_x"); n != "Kemi's Pantry" || !known {
		t.Fatalf("learned name: %q %v", n, known)
	}
	if _, known, _ := d.DisplayName(ctx, "org_y"); known {
		t.Fatal("empty names are not remembered")
	}
}

type fakeSource struct {
	orgs     []domain.SellerOrg
	lookups  atomic.Int32
	pages    atomic.Int32
	failOrg  bool
	failPage int
	// before runs before a page is returned (simulates an event processed while the page is in flight).
	before func()
}

func (f *fakeSource) Directory(_ context.Context, page, size int) (domain.SellerDirectoryPage, error) {
	f.pages.Add(1)
	if f.failPage > 0 && page+1 == f.failPage {
		return domain.SellerDirectoryPage{}, errors.New("seller down")
	}
	if f.before != nil {
		f.before()
	}
	from := min(page*size, len(f.orgs))
	to := min(from+size, len(f.orgs))
	return domain.SellerDirectoryPage{Items: f.orgs[from:to], Page: page, Size: size, Total: int64(len(f.orgs))}, nil
}

func (f *fakeSource) Org(_ context.Context, id string) (domain.SellerOrg, bool, error) {
	f.lookups.Add(1)
	if f.failOrg {
		return domain.SellerOrg{}, false, errors.New("seller down")
	}
	for _, o := range f.orgs {
		if o.ID == id {
			return o, true, nil
		}
	}
	return domain.SellerOrg{}, false, nil
}

func orgs(n int) []domain.SellerOrg {
	out := make([]domain.SellerOrg, 0, n)
	for i := range n {
		out = append(out, domain.SellerOrg{ID: "org_" + strconv.Itoa(i), DisplayName: "Store " + strconv.Itoa(i),
			Kind: "SELLER", Status: "APPROVED"})
	}
	return out
}

func TestWarmLoadsEveryPageSoARestartKeepsNames(t *testing.T) {
	ctx := context.Background()
	src := &fakeSource{orgs: orgs(5)}
	src.orgs = append(src.orgs, domain.SellerOrg{ID: "org_draft"}) // no name yet: skipped
	d := sellers.NewDirectory(map[string]string{"org_afrotransact_retail": "AfroTransact Retail"}, "Marketplace seller",
		src, sellers.Options{PageSize: 2, Log: quiet})
	n, err := d.Warm(ctx)
	if err != nil || n != 6 || src.pages.Load() != 3 {
		t.Fatalf("warm: %d names, %d pages, %v", n, src.pages.Load(), err)
	}
	if name, known, _ := d.DisplayName(ctx, "org_4"); name != "Store 4" || !known {
		t.Fatalf("warmed name: %q %v", name, known)
	}
	if src.lookups.Load() != 0 {
		t.Fatal("warmed names need no lookup")
	}
}

func TestMissesAreLookedUpOnceThenCached(t *testing.T) {
	ctx := context.Background()
	src := &fakeSource{orgs: orgs(1)}
	d := sellers.NewDirectory(nil, "Marketplace seller", src, sellers.Options{Log: quiet, MissTTL: time.Hour})
	if name, known, _ := d.DisplayName(ctx, "org_0"); name != "Store 0" || !known {
		t.Fatalf("looked-up name: %q %v", name, known)
	}
	_, _, _ = d.DisplayName(ctx, "org_0")
	for range 3 {
		if name, known, _ := d.DisplayName(ctx, "org_unknown"); known || name != "Marketplace seller" {
			t.Fatalf("unknown org: %q %v", name, known)
		}
	}
	if src.lookups.Load() != 2 {
		t.Fatalf("lookups = %d, want one per org", src.lookups.Load())
	}
	// An event teaches the name even after a miss.
	d.Remember(ctx, "org_unknown", "Late Store")
	if name, known, _ := d.DisplayName(ctx, "org_unknown"); name != "Late Store" || !known {
		t.Fatalf("after event: %q %v", name, known)
	}
}

func TestSellerOutageFallsBackWithoutError(t *testing.T) {
	src := &fakeSource{failOrg: true, failPage: 1}
	d := sellers.NewDirectory(nil, "Marketplace seller", src, sellers.Options{Log: quiet})
	name, known, err := d.DisplayName(context.Background(), "org_9")
	if err != nil || known || name != "Marketplace seller" {
		t.Fatalf("outage: %q %v %v", name, known, err)
	}
	if _, err := d.Warm(context.Background()); err == nil {
		t.Fatal("warm reports the outage")
	}
}

func TestEventDuringRefreshWinsOverTheOlderSnapshot(t *testing.T) {
	ctx := context.Background()
	src := &fakeSource{orgs: []domain.SellerOrg{{ID: "org_k", DisplayName: "Kemi Foods"}}}
	d := sellers.NewDirectory(nil, "Marketplace seller", src, sellers.Options{Log: quiet})
	src.before = func() {
		time.Sleep(2 * time.Millisecond)
		d.Remember(ctx, "org_k", "Kemi's Pantry") // seller.org.updated processed while the page was in flight
	}
	if _, err := d.Warm(ctx); err != nil {
		t.Fatal(err)
	}
	if name, _, _ := d.DisplayName(ctx, "org_k"); name != "Kemi's Pantry" {
		t.Fatalf("stale snapshot overwrote the event: %q", name)
	}
}

func TestClientReadsDirectoryAndPublicProfiles(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1/orgs/directory":
			if r.URL.Query().Get("page") != "1" || r.URL.Query().Get("size") != "2" {
				http.Error(w, "bad paging", http.StatusBadRequest)
				return
			}
			_ = json.NewEncoder(w).Encode(domain.SellerDirectoryPage{Items: orgs(1), Page: 1, Size: 2, Total: 3})
		case "/v1/orgs/org_0/public":
			_ = json.NewEncoder(w).Encode(orgs(1)[0])
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	c := sellers.NewClient(sellers.ClientConfig{BaseURL: srv.URL + "/"}, &http.Client{Timeout: time.Second})
	p, err := c.Directory(context.Background(), 1, 2)
	if err != nil || p.Total != 3 || p.Items[0].DisplayName != "Store 0" {
		t.Fatalf("directory: %+v %v", p, err)
	}
	org, found, err := c.Org(context.Background(), "org_0")
	if err != nil || !found || org.Kind != "SELLER" {
		t.Fatalf("org: %+v %v %v", org, found, err)
	}
	if _, found, err := c.Org(context.Background(), "org_missing"); err != nil || found {
		t.Fatalf("missing org: %v %v", found, err)
	}
}

package app_test

import (
	"context"
	"errors"
	"sort"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type fakeStore struct {
	fail   bool
	added  []domain.AnalyticsDelta
	days   []domain.DailyCTR
	purged []string
}

func (f *fakeStore) Add(_ context.Context, d []domain.AnalyticsDelta) error {
	if f.fail {
		return errors.New("es down")
	}
	f.added = append(f.added, d...)
	return nil
}

func (f *fakeStore) TopQueries(context.Context, domain.AnalyticsRange) ([]domain.QueryStat, error) {
	return []domain.QueryStat{{Query: "egusi", Searches: 40, Clicks: 10}}, nil
}

func (f *fakeStore) ZeroResultQueries(context.Context, domain.AnalyticsRange) ([]domain.QueryStat, error) {
	return []domain.QueryStat{{Query: "kpekere", Searches: 7, ZeroResults: 7}}, nil
}

func (f *fakeStore) DailyCTR(context.Context, domain.AnalyticsRange) ([]domain.DailyCTR, error) {
	return f.days, nil
}

func (f *fakeStore) Purge(_ context.Context, market string, _ time.Time) error {
	f.purged = append(f.purged, market)
	return nil
}

func TestRecorderAggregatesPerDayAndQueryAndRetriesFailedFlushes(t *testing.T) {
	store := &fakeStore{fail: true}
	rec := app.NewAnalyticsRecorder(store, quiet)
	day := time.Date(2026, 10, 9, 23, 0, 0, 0, time.UTC)
	rec.Searched("us", "Egusi ", false, day)
	rec.Searched("us", "egusi", false, day)
	rec.Searched("us", "kpekere", true, day)
	rec.Clicked("us", "EGUSI", day)
	rec.Searched("us", "egusi", false, day.Add(2*time.Hour))
	rec.Searched("us", "  ", true, day)

	if err := rec.Flush(context.Background()); err == nil {
		t.Fatal("expected flush failure")
	}
	store.fail = false
	rec.Clicked("us", "egusi", day)
	if err := rec.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	sort.Slice(store.added, func(i, j int) bool {
		return store.added[i].Day+store.added[i].Query < store.added[j].Day+store.added[j].Query
	})
	want := []domain.AnalyticsDelta{
		{Market: "us", Day: "2026-10-09", Query: "egusi", Searches: 2, Clicks: 2},
		{Market: "us", Day: "2026-10-09", Query: "kpekere", Searches: 1, ZeroResults: 1},
		{Market: "us", Day: "2026-10-10", Query: "egusi", Searches: 1},
	}
	if len(store.added) != len(want) {
		t.Fatalf("added = %+v", store.added)
	}
	for i := range want {
		if store.added[i] != want[i] {
			t.Fatalf("delta %d = %+v, want %+v", i, store.added[i], want[i])
		}
	}
	if err := rec.Flush(context.Background()); err != nil || len(store.added) != 3 {
		t.Fatal("an empty flush writes nothing")
	}
}

func TestReporterComputesRates(t *testing.T) {
	store := &fakeStore{days: []domain.DailyCTR{
		{Day: "2026-10-08", Searches: 200, Clicks: 50, ZeroResults: 10},
		{Day: "2026-10-09", Searches: 0},
	}}
	rep := app.NewAnalyticsReporter(store)
	ctr, err := rep.ClickThrough(context.Background(), domain.AnalyticsRange{Market: "US"})
	if err != nil {
		t.Fatal(err)
	}
	if ctr.Market != "us" || ctr.Days[0].CTR != 0.25 || ctr.Days[0].ZeroResultRate != 0.05 || ctr.Days[1].CTR != 0 ||
		ctr.Total.Searches != 200 || ctr.Total.CTR != 0.25 {
		t.Fatalf("ctr: %+v", ctr)
	}
	top, err := rep.TopQueries(context.Background(), domain.AnalyticsRange{})
	if err != nil || top.Items[0].CTR != 0.25 || top.From == "" {
		t.Fatalf("top: %+v %v", top, err)
	}
	if _, err := rep.ZeroResultQueries(context.Background(), domain.AnalyticsRange{
		From: time.Date(2026, 10, 9, 0, 0, 0, 0, time.UTC), To: time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC),
	}); !errors.Is(err, domain.ErrInvalidRange) {
		t.Fatalf("inverted range: %v", err)
	}
}

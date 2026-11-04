package main

import (
	"bytes"
	"errors"
	"flag"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
)

var testNow = time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)

func testConfig() config.Config {
	var cfg config.Config
	cfg.Market = "us"
	cfg.Reindex.Batch, cfg.Reindex.Concurrency = 100, 4
	return cfg
}

func parse(t *testing.T, args ...string) (reindexArgs, error) {
	t.Helper()
	var out bytes.Buffer
	return parseReindexArgs(args, testConfig(), testNow, &out)
}

func TestDefaultsComeFromTheConfiguration(t *testing.T) {
	a, err := parse(t)
	if err != nil {
		t.Fatal(err)
	}
	want := reindexArgs{Markets: []string{"us"}, Batch: 100, Concurrency: 4, Statuses: []string{"LIVE"}}
	if !reflect.DeepEqual(a, want) {
		t.Fatalf("args = %+v, want %+v", a, want)
	}
}

func TestFlagsOverrideTheDefaults(t *testing.T) {
	a, err := parse(t, "--market", "US, ng ,us", "--batch", "50", "--concurrency", "4", "--dry-run", "--since", "36h",
		"--after", "abc", "--status", "live,paused", "--backfill-embeddings")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(a.Markets, []string{"us", "ng"}) || a.Batch != 50 || a.Concurrency != 4 || !a.DryRun ||
		!a.Since.Equal(testNow.Add(-36*time.Hour)) || a.After != "abc" || !reflect.DeepEqual(a.Statuses, []string{"LIVE", "PAUSED"}) ||
		!a.BackfillEmbeddings {
		t.Fatalf("args = %+v", a)
	}
}

func TestInvalidFlagsAreRefused(t *testing.T) {
	for _, args := range [][]string{
		{"--batch", "0"}, {"--batch", "500"}, {"--concurrency", "0"}, {"--concurrency", "99"},
		{"--since", "yesterday"}, {"--since", "-5h"}, {"extra"}, {"--nope"},
	} {
		if _, err := parse(t, args...); err == nil {
			t.Errorf("%v accepted", args)
		}
	}
	cfg := testConfig()
	cfg.Market = ""
	if _, err := parseReindexArgs(nil, cfg, testNow, &bytes.Buffer{}); err == nil {
		t.Error("no market accepted")
	}
}

func TestHelpIsNotAFailure(t *testing.T) {
	var out bytes.Buffer
	_, err := parseReindexArgs([]string{"-h"}, testConfig(), testNow, &out)
	if !errors.Is(err, flag.ErrHelp) || !strings.Contains(out.String(), "Rebuilds the product search index") {
		t.Fatalf("err = %v, usage = %q", err, out.String())
	}
}

func TestSinceAcceptsDurationsTimesAndDates(t *testing.T) {
	cases := map[string]time.Time{
		"":                     {},
		"48h":                  testNow.Add(-48 * time.Hour),
		"2026-10-01T10:00:00Z": time.Date(2026, 10, 1, 10, 0, 0, 0, time.UTC),
		"2026-10-01":           time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC),
	}
	for in, want := range cases {
		got, err := parseSince(in, testNow)
		if err != nil || !got.Equal(want) {
			t.Errorf("parseSince(%q) = %v, %v; want %v", in, got, err, want)
		}
	}
}

func TestSummaryListsEveryMarketAndTheResumePoint(t *testing.T) {
	var out bytes.Buffer
	printReindexSummary(&out, "meilisearch", app.ReindexSummary{
		Markets: []app.MarketSummary{{Market: "us", Read: 10, Written: 8, Skipped: 1, Failed: 1}, {Market: "ng", Read: 10, Written: 10}},
		Batches: 2, FailedBatches: 1, ResumeAfter: "cursor-1",
	})
	got := out.String()
	for _, want := range []string{"Reindex summary (meilisearch)", "MARKET", "us", "ng", "batches: 2, failed batches: 1", "--after cursor-1"} {
		if !strings.Contains(got, want) {
			t.Errorf("summary lacks %q:\n%s", want, got)
		}
	}
	out.Reset()
	printReindexSummary(&out, "elasticsearch", app.ReindexSummary{DryRun: true, Markets: []app.MarketSummary{{Market: "us", Read: 5, Planned: 5}}})
	if !strings.Contains(out.String(), "dry run: nothing was written") || strings.Contains(out.String(), "resume") {
		t.Errorf("dry run summary:\n%s", out.String())
	}
}

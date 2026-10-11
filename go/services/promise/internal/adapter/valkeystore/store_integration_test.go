package valkeystore_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/valkeystore"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

func TestValkeyPromiseStore(t *testing.T) {
	testdocker.Require(t)
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "valkey/valkey:8.1-alpine",
		testcontainers.WithExposedPorts("6379/tcp"),
		testcontainers.WithWaitStrategy(wait.ForListeningPort("6379/tcp").WithStartupTimeout(time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	addr, err := ctr.PortEndpoint(ctx, "6379/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	rdb := redis.NewClient(&redis.Options{Addr: addr})
	defer func() { _ = rdb.Close() }()
	s := valkeystore.New(rdb, "promise:")
	if err := s.Ping(ctx); err != nil {
		t.Fatal(err)
	}
	d := domain.Day{Year: 2026, Month: 10, Day: 10}
	cut := time.Date(2026, 10, 9, 19, 0, 0, 0, time.UTC)
	p := domain.Promise{ID: "prm_abc", OfferID: "o1", Available: true, EarliestDate: &d, LatestDate: &d, CutoffAt: &cut,
		Label: "Tomorrow, Oct 10", Transit: &domain.Transit{MinDays: 1, MaxDays: 1, Source: "table"}}
	if err := s.SaveSnapshots(ctx, []domain.Promise{p}, 24*time.Hour); err != nil {
		t.Fatal(err)
	}
	got, err := s.Snapshot(ctx, "prm_abc")
	if err != nil || *got.EarliestDate != d || got.Label != p.Label || !got.CutoffAt.Equal(cut) || got.Transit.Source != "table" {
		t.Fatalf("%+v %v", got, err)
	}
	if ttl, _ := rdb.TTL(ctx, "promise:snap:prm_abc").Result(); ttl < 23*time.Hour {
		t.Fatalf("snapshot ttl %v", ttl)
	}
	if _, err := s.Snapshot(ctx, "prm_missing"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("missing: %v", err)
	}
	if err := s.PutComputed(ctx, "FC:fc_houston_tx|CARRIER:GROUND|77002|2026100810", p, 50*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := s.GetComputed(ctx, "FC:fc_houston_tx|CARRIER:GROUND|77002|2026100810"); !ok {
		t.Fatal("computed promise must be cached")
	}
	time.Sleep(150 * time.Millisecond)
	if _, ok, _ := s.GetComputed(ctx, "FC:fc_houston_tx|CARRIER:GROUND|77002|2026100810"); ok {
		t.Fatal("computed promise must expire with its validity")
	}
}

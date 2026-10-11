package quotecache_test

import (
	"context"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/quotecache"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func TestValkeyQuoteCache(t *testing.T) {
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
	if err := quotecache.Ping(ctx, rdb); err != nil {
		t.Fatal(err)
	}
	c := quotecache.New(rdb, "cg:")
	if _, ok, err := c.Get(ctx, "rates:x"); ok || err != nil {
		t.Fatalf("miss expected: %v %v", ok, err)
	}
	eta := time.Date(2026, 10, 13, 23, 59, 0, 0, time.UTC)
	in := []domain.Quote{{Carrier: domain.DHL, Service: "P", ServiceName: "DHL EXPRESS WORLDWIDE", AmountMinor: 8642, Currency: "USD", EstimatedDelivery: &eta}}
	if err := c.Put(ctx, "rates:x", in, 10*time.Minute); err != nil {
		t.Fatal(err)
	}
	out, ok, err := c.Get(ctx, "rates:x")
	if err != nil || !ok || len(out) != 1 || out[0].AmountMinor != 8642 || !out[0].EstimatedDelivery.Equal(eta) {
		t.Fatalf("round trip %+v %v %v", out, ok, err)
	}
	ttl, _ := rdb.TTL(ctx, "cg:rates:x").Result()
	if ttl <= 9*time.Minute || ttl > 10*time.Minute {
		t.Fatalf("ttl %v", ttl)
	}
}

package redisrepo_test

import (
	"context"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/redisrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

func TestOptimisticSavesNeverLoseUpdates(t *testing.T) {
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
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
	repo := redisrepo.New(rdb, time.Hour)
	owner := domain.Owner{Kind: domain.OwnerGuest, ID: "g1"}

	var wg sync.WaitGroup
	var mu sync.Mutex
	conflicts := 0
	for i := range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				c, err := repo.Load(ctx, owner)
				if err != nil {
					t.Error(err)
					return
				}
				_ = c.Add(domain.Offer{VariantATIN: "AT" + string(rune('A'+i)), PriceMinor: 1, Currency: "USD"}, 1, 5, time.Now())
				err = repo.Save(ctx, &c)
				if errors.Is(err, domain.ErrConflict) {
					mu.Lock()
					conflicts++
					mu.Unlock()
					continue
				}
				if err != nil {
					t.Error(err)
				}
				return
			}
		}()
	}
	wg.Wait()
	final, err := repo.Load(ctx, owner)
	if err != nil {
		t.Fatal(err)
	}
	if len(final.Lines) != 20 || final.Version != 20 {
		t.Fatalf("lost updates: lines=%d version=%d (conflicts retried=%d)", len(final.Lines), final.Version, conflicts)
	}
	ttl, err := rdb.TTL(ctx, "cart:guest:g1").Result()
	if err != nil || ttl <= 0 {
		t.Fatalf("guest cart must expire, ttl=%v err=%v", ttl, err)
	}
}

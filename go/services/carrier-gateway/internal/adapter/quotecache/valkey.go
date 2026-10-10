// Package quotecache implements port.QuoteCache on Redis/Valkey: one JSON value per shipment fingerprint with a
// TTL (10 minutes), so repeated rate requests for the same cart never hit the carriers again.
package quotecache

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

type valkeyImpl struct {
	rdb    redis.UniversalClient
	prefix string
}

var _ port.QuoteCache = (*valkeyImpl)(nil)

// New returns a Valkey-backed QuoteCache; keys are prefixed (e.g. "carrier-gateway:").
func New(rdb redis.UniversalClient, prefix string) port.QuoteCache {
	return &valkeyImpl{rdb: rdb, prefix: prefix}
}

func (c *valkeyImpl) Get(ctx context.Context, key string) ([]domain.Quote, bool, error) {
	raw, err := c.rdb.Get(ctx, c.prefix+key).Bytes()
	if errors.Is(err, redis.Nil) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, fmt.Errorf("valkey get: %w", err)
	}
	var quotes []domain.Quote
	if err := json.Unmarshal(raw, &quotes); err != nil {
		return nil, false, fmt.Errorf("decode quotes: %w", err)
	}
	return quotes, true, nil
}

func (c *valkeyImpl) Put(ctx context.Context, key string, quotes []domain.Quote, ttl time.Duration) error {
	raw, err := json.Marshal(quotes)
	if err != nil {
		return fmt.Errorf("encode quotes: %w", err)
	}
	if err := c.rdb.Set(ctx, c.prefix+key, raw, ttl).Err(); err != nil {
		return fmt.Errorf("valkey set: %w", err)
	}
	return nil
}

// Ping checks connectivity (readiness).
func Ping(ctx context.Context, rdb redis.UniversalClient) error {
	if err := rdb.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("valkey ping: %w", err)
	}
	return nil
}

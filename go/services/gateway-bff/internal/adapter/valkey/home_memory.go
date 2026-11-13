package valkey

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type homeMemoryImpl struct{ rdb redis.UniversalClient }

var _ port.HomeMemory = (*homeMemoryImpl)(nil)

// NewHomeMemory returns the Valkey-backed home memory: one small JSON value per signed-in shopper (hashed key), expiring
// with its TTL.
func NewHomeMemory(rdb redis.UniversalClient) port.HomeMemory { return &homeMemoryImpl{rdb: rdb} }

func homeKey(key string) string { return prefix + "home:visit:" + key }

func (h *homeMemoryImpl) Load(ctx context.Context, key string) (port.HomeVisitState, bool, error) {
	b, err := h.rdb.Get(ctx, homeKey(key)).Bytes()
	if errors.Is(err, redis.Nil) {
		return port.HomeVisitState{}, false, nil
	}
	if err != nil {
		return port.HomeVisitState{}, false, fmt.Errorf("load home memory: %w", err)
	}
	var st port.HomeVisitState
	if err := json.Unmarshal(b, &st); err != nil {
		return port.HomeVisitState{}, false, nil // an unreadable entry is a forgotten visit
	}
	return st, true, nil
}

func (h *homeMemoryImpl) Save(ctx context.Context, key string, st port.HomeVisitState, ttl time.Duration) error {
	b, err := json.Marshal(st)
	if err != nil {
		return fmt.Errorf("encode home memory: %w", err)
	}
	if err := h.rdb.Set(ctx, homeKey(key), b, ttl).Err(); err != nil {
		return fmt.Errorf("save home memory: %w", err)
	}
	return nil
}

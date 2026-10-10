// Package redisrepo stores guest carts in Redis/Valkey with a sliding 30-day TTL. Optimistic concurrency uses
// WATCH/MULTI on the cart key.
package redisrepo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

type repoImpl struct {
	rdb *redis.Client
	ttl time.Duration
}

var _ port.CartRepository = (*repoImpl)(nil)

// New returns a Redis CartRepository.
func New(rdb *redis.Client, ttl time.Duration) port.CartRepository {
	return &repoImpl{rdb: rdb, ttl: ttl}
}

func key(o domain.Owner) string { return "cart:" + o.Key() }

func (r *repoImpl) Load(ctx context.Context, owner domain.Owner) (domain.Cart, error) {
	raw, err := r.rdb.Get(ctx, key(owner)).Bytes()
	if errors.Is(err, redis.Nil) {
		return domain.Cart{Owner: owner, Lines: []domain.Line{}, Saved: []domain.Line{}}, nil
	}
	if err != nil {
		return domain.Cart{}, fmt.Errorf("redis get: %w", err)
	}
	var c domain.Cart
	if err := json.Unmarshal(raw, &c); err != nil {
		return domain.Cart{}, fmt.Errorf("decode cart: %w", err)
	}
	c.Owner = owner
	return c, nil
}

func (r *repoImpl) Save(ctx context.Context, cart *domain.Cart) error {
	k := key(cart.Owner)
	err := r.rdb.Watch(ctx, func(tx *redis.Tx) error {
		raw, err := tx.Get(ctx, k).Bytes()
		var stored int64
		switch {
		case errors.Is(err, redis.Nil):
		case err != nil:
			return fmt.Errorf("redis get: %w", err)
		default:
			var c domain.Cart
			if err := json.Unmarshal(raw, &c); err != nil {
				return fmt.Errorf("decode cart: %w", err)
			}
			stored = c.Version
		}
		if stored != cart.Version {
			return domain.ErrConflict
		}
		next := *cart
		next.Version++
		next.UpdatedAt = time.Now().UTC()
		buf, err := json.Marshal(next)
		if err != nil {
			return fmt.Errorf("encode cart: %w", err)
		}
		if _, err := tx.TxPipelined(ctx, func(p redis.Pipeliner) error {
			p.Set(ctx, k, buf, r.ttl)
			return nil
		}); err != nil {
			return fmt.Errorf("redis set: %w", err)
		}
		*cart = next
		return nil
	}, k)
	if errors.Is(err, redis.TxFailedErr) {
		return domain.ErrConflict
	}
	if err != nil {
		return fmt.Errorf("save cart: %w", err)
	}
	return nil
}

func (r *repoImpl) Delete(ctx context.Context, owner domain.Owner) error {
	if err := r.rdb.Del(ctx, key(owner)).Err(); err != nil {
		return fmt.Errorf("redis del: %w", err)
	}
	return nil
}

func (r *repoImpl) Ping(ctx context.Context) error {
	if err := r.rdb.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("redis ping: %w", err)
	}
	return nil
}

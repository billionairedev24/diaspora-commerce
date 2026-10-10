// Package valkeystore implements port.PromiseStore on Redis/Valkey: computed promises cached per
// (origin, method, ZIP, date-hour) until the next hour or cutoff, and promise snapshots kept for 24 hours so
// checkout can store the exact promise on the order.
package valkeystore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

type storeImpl struct {
	rdb    redis.UniversalClient
	prefix string
}

var _ port.PromiseStore = (*storeImpl)(nil)

// New returns a Valkey PromiseStore with keys under prefix (e.g. "promise:").
func New(rdb redis.UniversalClient, prefix string) port.PromiseStore {
	return &storeImpl{rdb: rdb, prefix: prefix}
}

func (s *storeImpl) get(ctx context.Context, key string) (domain.Promise, bool, error) {
	raw, err := s.rdb.Get(ctx, key).Bytes()
	if errors.Is(err, redis.Nil) {
		return domain.Promise{}, false, nil
	}
	if err != nil {
		return domain.Promise{}, false, fmt.Errorf("valkey get: %w", err)
	}
	var p domain.Promise
	if err := json.Unmarshal(raw, &p); err != nil {
		return domain.Promise{}, false, fmt.Errorf("decode promise: %w", err)
	}
	return p, true, nil
}

func (s *storeImpl) GetComputed(ctx context.Context, key string) (domain.Promise, bool, error) {
	return s.get(ctx, s.prefix+"calc:"+key)
}

func (s *storeImpl) PutComputed(ctx context.Context, key string, p domain.Promise, ttl time.Duration) error {
	raw, err := json.Marshal(p)
	if err != nil {
		return fmt.Errorf("encode promise: %w", err)
	}
	if err := s.rdb.Set(ctx, s.prefix+"calc:"+key, raw, ttl).Err(); err != nil {
		return fmt.Errorf("valkey set: %w", err)
	}
	return nil
}

func (s *storeImpl) SaveSnapshots(ctx context.Context, ps []domain.Promise, ttl time.Duration) error {
	if len(ps) == 0 {
		return nil
	}
	_, err := s.rdb.Pipelined(ctx, func(p redis.Pipeliner) error {
		for _, pr := range ps {
			raw, err := json.Marshal(pr)
			if err != nil {
				return fmt.Errorf("encode promise: %w", err)
			}
			p.Set(ctx, s.prefix+"snap:"+pr.ID, raw, ttl)
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("valkey pipeline: %w", err)
	}
	return nil
}

func (s *storeImpl) Snapshot(ctx context.Context, id string) (domain.Promise, error) {
	p, ok, err := s.get(ctx, s.prefix+"snap:"+id)
	if err != nil {
		return domain.Promise{}, err
	}
	if !ok {
		return domain.Promise{}, domain.ErrNotFound
	}
	return p, nil
}

func (s *storeImpl) Ping(ctx context.Context) error {
	if err := s.rdb.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("valkey ping: %w", err)
	}
	return nil
}

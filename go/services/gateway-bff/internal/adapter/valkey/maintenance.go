package valkey

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

const maintenanceKey = prefix + "platform:maintenance"

type maintenanceImpl struct{ rdb redis.UniversalClient }

var _ port.MaintenanceStore = (*maintenanceImpl)(nil)

// NewMaintenance returns the Valkey-backed maintenance switch (no expiry: it stays until staff change it).
func NewMaintenance(rdb redis.UniversalClient) port.MaintenanceStore {
	return &maintenanceImpl{rdb: rdb}
}

func (s *maintenanceImpl) Get(ctx context.Context) (domain.Maintenance, error) {
	b, err := s.rdb.Get(ctx, maintenanceKey).Bytes()
	if errors.Is(err, redis.Nil) {
		return domain.Maintenance{}, nil
	}
	if err != nil {
		return domain.Maintenance{}, fmt.Errorf("get maintenance: %w", err)
	}
	var m domain.Maintenance
	if err := json.Unmarshal(b, &m); err != nil {
		return domain.Maintenance{}, fmt.Errorf("decode maintenance: %w", err)
	}
	return m, nil
}

func (s *maintenanceImpl) Put(ctx context.Context, m domain.Maintenance) error {
	b, err := json.Marshal(m)
	if err != nil {
		return fmt.Errorf("encode maintenance: %w", err)
	}
	if err := s.rdb.Set(ctx, maintenanceKey, b, 0).Err(); err != nil {
		return fmt.Errorf("save maintenance: %w", err)
	}
	return nil
}

package memcache

import (
	"context"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type maintenanceImpl struct {
	mu sync.Mutex
	m  domain.Maintenance
}

var _ port.MaintenanceStore = (*maintenanceImpl)(nil)

// NewMaintenance returns an in-process maintenance switch (tests and single-replica local runs).
func NewMaintenance() port.MaintenanceStore { return &maintenanceImpl{} }

func (s *maintenanceImpl) Get(context.Context) (domain.Maintenance, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.m, nil
}

func (s *maintenanceImpl) Put(_ context.Context, m domain.Maintenance) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.m = m
	return nil
}

// Package sellers implements port.SellerDirectory.
package sellers

import (
	"context"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

type memoryDirectory struct {
	mu       sync.RWMutex
	names    map[string]string
	fallback string
}

var _ port.SellerDirectory = (*memoryDirectory)(nil)

// NewMemoryDirectory resolves names from configuration (AfroTransact Retail and known orgs) plus names learned from
// seller.org.approved / seller.org.updated. Learned names live in memory: after a restart the projector keeps the
// seller_name already indexed until the next seller.org.updated event (see port.UpsertOptions).
func NewMemoryDirectory(names map[string]string, fallback string) port.SellerDirectory {
	m := make(map[string]string, len(names))
	for k, v := range names {
		m[k] = v
	}
	return &memoryDirectory{names: m, fallback: fallback}
}

func (d *memoryDirectory) DisplayName(_ context.Context, orgID string) (string, bool, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	if n, ok := d.names[orgID]; ok {
		return n, true, nil
	}
	return d.fallback, false, nil
}

func (d *memoryDirectory) Remember(_ context.Context, orgID, name string) {
	if orgID == "" || name == "" {
		return
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	d.names[orgID] = name
}

// Package sellers implements port.SellerDirectory.
package sellers

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

type staticDirectory struct {
	names    map[string]string
	fallback string
}

var _ port.SellerDirectory = (*staticDirectory)(nil)

// NewStaticDirectory resolves names from configuration (AfroTransact Retail and known orgs). The seller service
// publishes seller.org.updated events; a projection-backed directory replaces this once that service exists.
func NewStaticDirectory(names map[string]string, fallback string) port.SellerDirectory {
	return &staticDirectory{names: names, fallback: fallback}
}

func (d *staticDirectory) DisplayName(_ context.Context, orgID string) (string, error) {
	if n, ok := d.names[orgID]; ok {
		return n, nil
	}
	return d.fallback, nil
}

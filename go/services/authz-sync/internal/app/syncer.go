// Package app turns identity and seller-org events into OpenFGA tuple changes.
package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/port"
)

type syncerImpl struct {
	store       port.TupleStore
	retailOrgID string
	log         *slog.Logger
}

var _ events.Handler = (*syncerImpl)(nil)

// NewSyncer returns the handler for at.identity.events and at.seller-org.events. Every event becomes one atomic,
// idempotent tuple change; event types it does not mirror are skipped. Malformed payloads and tuples the model
// rejects are poison (dead-lettered); store outages are returned for retry.
func NewSyncer(store port.TupleStore, retailOrgID string, log *slog.Logger) events.Handler {
	return &syncerImpl{store: store, retailOrgID: retailOrgID, log: log}
}

func (s *syncerImpl) Handle(ctx context.Context, e events.Envelope) error {
	var change domain.Change
	switch e.Type {
	case domain.RoleGranted, domain.RoleRevoked:
		var p domain.RoleChanged
		if err := json.Unmarshal(e.Payload, &p); err != nil {
			return fmt.Errorf("decode %s: %w: %w", e.Type, ErrPoison, err)
		}
		change = domain.RoleChange(p, e.Type == domain.RoleGranted, s.retailOrgID)
	case domain.SellerOrgApproved, domain.SellerOrgUpdated:
		var p domain.SellerOrgEvent
		if err := json.Unmarshal(e.Payload, &p); err != nil {
			return fmt.Errorf("decode %s: %w: %w", e.Type, ErrPoison, err)
		}
		if p.OrgID == "" {
			return fmt.Errorf("%s %s: missing orgId: %w", e.Type, e.ID, ErrPoison)
		}
		markets, err := s.store.Read(ctx, "organization:"+p.OrgID, "market")
		if err != nil {
			return fmt.Errorf("read markets of %s: %w", p.OrgID, err)
		}
		change = domain.OrgChange(p, markets)
	default:
		return nil
	}
	if change.Empty() {
		return nil
	}
	if err := s.store.Write(ctx, change); err != nil {
		if errors.Is(err, port.ErrRejected) {
			return fmt.Errorf("write tuples for %s %s: %w: %w", e.Type, e.ID, ErrPoison, err)
		}
		return fmt.Errorf("write tuples for %s %s: %w", e.Type, e.ID, err)
	}
	s.log.DebugContext(ctx, "tuples synced", "event_id", e.ID, "type", e.Type,
		"writes", len(change.Writes), "deletes", len(change.Deletes))
	return nil
}

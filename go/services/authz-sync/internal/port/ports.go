// Package port declares authz-sync's outbound interfaces.
package port

import (
	"context"
	"errors"

	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
)

// ErrRejected is returned (wrapped) when the store refuses a request as invalid (4xx): retrying cannot help.
var ErrRejected = errors.New("rejected by the tuple store")

// TupleStore reads and writes relationship tuples. Writes are atomic per call and idempotent: writing an existing
// tuple or deleting a missing one is not an error, so redelivered events are harmless.
type TupleStore interface {
	Write(ctx context.Context, change domain.Change) error
	// Read returns the tuples on object with relation (any user).
	Read(ctx context.Context, object, relation string) ([]domain.Tuple, error)
	Ping(ctx context.Context) error
}

// ModelAdmin provisions the store and its authorization model.
type ModelAdmin interface {
	// EnsureStore returns the id of the store named name, creating it when missing.
	EnsureStore(ctx context.Context, name string) (string, error)
	// EnsureModel writes model (OpenFGA JSON) when it differs from the store's latest model and returns the
	// latest model id and whether a new model was written.
	EnsureModel(ctx context.Context, model []byte) (id string, written bool, err error)
}

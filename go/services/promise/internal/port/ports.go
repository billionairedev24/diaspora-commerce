// Package port declares the promise service's outbound interfaces.
package port

import (
	"context"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

// ZipDirectory resolves a ZIP to its region (ZIP3 centroid, state, time zone).
type ZipDirectory interface {
	Lookup(zip string) (domain.ZipInfo, error)
}

// TransitTable returns carrier ground transit days between two ZIPs: from the FC zone table when the origin is a
// launch FC, otherwise from the distance between ZIP3 centroids.
type TransitTable interface {
	Transit(ctx context.Context, originZIP, destZIP string) (domain.Transit, error)
}

// NodeDirectory knows our fulfilment centres.
type NodeDirectory interface {
	FC(id string) (domain.FulfilmentCentre, bool)
	All() []domain.FulfilmentCentre
}

// PromiseStore caches computed promises and keeps promise snapshots for checkout (Valkey).
type PromiseStore interface {
	GetComputed(ctx context.Context, key string) (domain.Promise, bool, error)
	PutComputed(ctx context.Context, key string, p domain.Promise, ttl time.Duration) error
	SaveSnapshots(ctx context.Context, ps []domain.Promise, ttl time.Duration) error
	Snapshot(ctx context.Context, id string) (domain.Promise, error)
	Ping(ctx context.Context) error
}

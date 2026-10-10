// Package port declares the media service's outbound interfaces.
package port

import (
	"context"
	"io"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// Upload is a direct-to-storage upload the client performs.
type Upload struct {
	Method    string            `json:"method"`
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	Key       string            `json:"key"`
	PublicURL string            `json:"publicUrl,omitempty"`
	ExpiresAt time.Time         `json:"expiresAt"`
}

// ObjectStore issues upload URLs and reads objects back (UploadThing, S3-compatible, Azure Blob).
type ObjectStore interface {
	Name() string
	PrepareUpload(ctx context.Context, key, fileName, contentType string, size int64, ttl time.Duration) (Upload, error)
	// Open streams the first n bytes of an object (n <= 0 means all) and reports its size and content type.
	Open(ctx context.Context, key string, n int64) (io.ReadCloser, int64, string, error)
	Delete(ctx context.Context, key string) error
	// SignedGetURL returns a time-limited download URL for private objects.
	SignedGetURL(ctx context.Context, key string, ttl time.Duration) (string, error)
}

// Inspector reads an object and reports its true type, size, dimensions and border brightness.
type Inspector interface {
	Inspect(r io.Reader, size int64, declaredType string) (domain.Inspection, error)
}

// AssetRepository persists assets.
type AssetRepository interface {
	Insert(ctx context.Context, a domain.Asset) error
	Get(ctx context.Context, id string) (domain.Asset, error)
	Update(ctx context.Context, a domain.Asset) error
	ListByOrg(ctx context.Context, org string, purpose domain.Purpose, limit int) ([]domain.Asset, error)
	Ping(ctx context.Context) error
}

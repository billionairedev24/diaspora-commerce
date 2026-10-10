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
	// FormField, when set, means the file is sent as multipart/form-data in this field (UploadThing ingest) rather
	// than as the raw request body; the client lets its HTTP library set the multipart Content-Type.
	FormField string `json:"formField,omitempty"`
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
	// MaxSignedURLTTL is the longest lifetime the store can sign a download URL for (0 = no limit).
	MaxSignedURLTTL() time.Duration
	// Put writes an object from the server (a moderated image being published) and returns the key it was stored
	// under (UploadThing assigns its own file keys) and, for public objects, its public URL.
	Put(ctx context.Context, key, fileName, contentType string, data []byte, public bool) (storedKey, publicURL string, err error)
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
	// SimilarImages returns completed images of the purposes (other than excludeID) whose perceptual hash is within
	// maxDistance bits of hash, closest first.
	SimilarImages(ctx context.Context, purposes []domain.Purpose, hash uint64, maxDistance int, excludeID string,
		limit int) ([]domain.SimilarImage, error)
	Ping(ctx context.Context) error
}

// ProcessedImage is an image after metadata stripping, with what moderation needs.
type ProcessedImage struct {
	// Data is the image without EXIF / XMP / IPTC / text metadata (the original bytes when there was none).
	Data []byte
	// Stripped reports whether metadata was removed.
	Stripped bool
	// Width and Height are the dimensions of Data (an EXIF orientation of 5–8 swaps the original ones).
	Width, Height int
	// Hash is the 64-bit perceptual (difference) hash.
	Hash uint64
	// Preview is a small JPEG (longest edge ≤ 768 px) sent to the AI moderation hook.
	Preview []byte
}

// ImageProcessor strips metadata from uploaded images and computes their perceptual hash and preview.
type ImageProcessor interface {
	Process(data []byte, contentType string) (ProcessedImage, error)
}

// ImageModerator is the AI moderation hook (ai-gateway POST /v1/ai/moderate-image with media's service token).
type ImageModerator interface {
	Moderate(ctx context.Context, preview []byte, contentType string, purpose domain.Purpose) (domain.AIVerdict, error)
}

// LinkSigner signs and verifies the tokens of media-hosted long-lived download links (GET /v1/l/{token}).
type LinkSigner interface {
	Sign(assetID string, expires time.Time) (string, error)
	// Verify checks the token's signature and returns the asset id and expiry; expiry is checked by the caller.
	Verify(token string) (assetID string, expires time.Time, err error)
}

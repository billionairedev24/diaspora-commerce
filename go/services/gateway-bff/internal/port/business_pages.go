package port

import (
	"context"
	"encoding/json"
	"time"
)

// UpBusinessPage is seller's BusinessPage: the plain public page of a business.
type UpBusinessPage struct {
	OrgID        string `json:"orgId"`
	Slug         string `json:"slug"`
	Kind         string `json:"kind"`
	Name         string `json:"name"`
	Description  string `json:"description"`
	BrandColor   string `json:"brandColor"`
	LogoURL      string `json:"logoUrl"`
	ContactEmail string `json:"contactEmail"`
	Approved     bool   `json:"approved"`
}

// BusinessPagesClient reads a business's public page by its address (seller service, no token).
type BusinessPagesClient interface {
	Page(ctx context.Context, slug string) (UpBusinessPage, error)
}

// UpStorefront is seller's PublicStorefront: the published store settings of an approved seller (404 otherwise).
type UpStorefront struct {
	// Kind is SELLER or PROVIDER.
	Kind         string          `json:"kind"`
	OrgID        string          `json:"orgId"`
	Slug         string          `json:"slug"`
	Name         string          `json:"name"`
	Description  string          `json:"description"`
	LogoURL      string          `json:"logoUrl"`
	BrandColor   string          `json:"brandColor"`
	ContactEmail string          `json:"contactEmail"`
	Config       json.RawMessage `json:"config"`
	PublishedAt  time.Time       `json:"publishedAt"`
	OrgCreatedAt time.Time       `json:"orgCreatedAt"`
}

// StorefrontsClient reads a seller's published storefront by its address (seller service, no token).
type StorefrontsClient interface {
	Storefront(ctx context.Context, slug string) (UpStorefront, error)
	// PublishedStorefronts lists the published storefronts of approved sellers (the sitemap).
	PublishedStorefronts(ctx context.Context) ([]UpPublishedStorefront, error)
}

// UpStorePage is seller's StorePageSettings: the draft and published JSON documents with their versions.
type UpStorePage struct {
	Draft                 json.RawMessage `json:"draft"`
	Published             json.RawMessage `json:"published"`
	IsPublished           bool            `json:"isPublished"`
	Version               int64           `json:"version"`
	PublishedVersion      *int64          `json:"publishedVersion"`
	HasUnpublishedChanges bool            `json:"hasUnpublishedChanges"`
	UpdatedAt             *time.Time      `json:"updatedAt"`
	PublishedAt           *time.Time      `json:"publishedAt"`
	Approved              bool            `json:"approved"`
}

// UpStorePageAuditEntry is one audit line (the actor is the member's subject).
type UpStorePageAuditEntry struct {
	At      time.Time `json:"at"`
	Action  string    `json:"action"`
	Actor   string    `json:"actor"`
	Version int64     `json:"version"`
}

// StorePageClient reads and changes an organisation's storefront settings (seller /v1/orgs/{orgId}/store-page).
type StorePageClient interface {
	StorePage(ctx context.Context, bearer, orgID string) (UpStorePage, error)
	SaveStorePage(ctx context.Context, bearer, orgID string, config json.RawMessage, version int64) (UpStorePage, error)
	PublishStorePage(ctx context.Context, bearer, orgID string, version int64) (UpStorePage, error)
	UnpublishStorePage(ctx context.Context, bearer, orgID string) (UpStorePage, error)
	StorePageAudit(ctx context.Context, bearer, orgID string) ([]UpStorePageAuditEntry, error)
}

// UpPublishedStorefront is one published storefront of an approved seller.
type UpPublishedStorefront struct {
	Kind        string    `json:"kind"`
	OrgID       string    `json:"orgId"`
	Slug        string    `json:"slug"`
	PublishedAt time.Time `json:"publishedAt"`
}

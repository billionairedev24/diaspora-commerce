package port

import "context"

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

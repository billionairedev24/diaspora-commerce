package domain

// BusinessPageSettings is GET/PUT /v1/seller/business-page: how the organisation's plain public page looks. Slug is
// null until the page is first made (when the Stripe account is created); PublicURL is empty then.
type BusinessPageSettings struct {
	Name         string  `json:"name"`
	Slug         *string `json:"slug"`
	Description  *string `json:"description"`
	BrandColor   *string `json:"brandColor"`
	LogoURL      *string `json:"logoUrl"`
	ContactEmail *string `json:"contactEmail"`
	// CanEdit is false for members who may only look (the owner and admins change the page).
	CanEdit bool `json:"canEdit"`
}

// BusinessPageSettingsInput is PUT /v1/seller/business-page; blank values clear them.
type BusinessPageSettingsInput struct {
	Description string `json:"description"`
	BrandColor  string `json:"brandColor"`
	LogoURL     string `json:"logoUrl"`
}

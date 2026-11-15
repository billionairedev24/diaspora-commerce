package domain

import "time"

// StorefrontCategory is a category the seller has live products in, with how many.
type StorefrontCategory struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// StorefrontPrice is a price range filter with the number of products in it.
type StorefrontPrice struct {
	Value string `json:"value"`
	Label string `json:"label"`
	Count int    `json:"count"`
}

// StorefrontProducts is one page of a storefront's All products grid.
type StorefrontProducts struct {
	Hits       []ProductSummary     `json:"hits"`
	Total      int64                `json:"total"`
	Page       int                  `json:"page"`
	Size       int                  `json:"size"`
	PageCount  int                  `json:"pageCount"`
	Categories []StorefrontCategory `json:"categories"`
	Prices     []StorefrontPrice    `json:"prices"`
}

// StorefrontQuery is the All products grid state (search within the store, filters, sort, page).
type StorefrontQuery struct {
	Q        string
	Category string
	Price    string
	Sort     string
	// OnSale keeps products with a real was-price above the price.
	OnSale bool
	Page   int
	Size   int
}

// StorefrontPickup is shown only when the seller shows it AND pickup is on at one of their places.
type StorefrontPickup struct {
	Zip string `json:"zip"`
}

// StorefrontReviews summarises the real reviews of the seller's products (null when there are none).
type StorefrontReviews struct {
	Average float64 `json:"average"`
	Count   int     `json:"count"`
}

// StorefrontProvider is what makes a provider's page bookable: its real services, weekly schedule, service area and team
// (contract: components.schemas.StorefrontProvider). Nothing here is invented: it is bookings' public view of the provider.
type StorefrontProvider struct {
	OrgID          string               `json:"orgId"`
	TimeZone       string               `json:"timeZone"`
	Location       string               `json:"location"`
	InstantBooking bool                 `json:"instantBooking"`
	PolicyLabel    string               `json:"policyLabel"`
	PolicyText     string               `json:"policyText"`
	Services       []StorefrontService  `json:"services"`
	Hours          []StorefrontDayHours `json:"hours"`
	Area           *StorefrontArea      `json:"area"`
	Team           []StorefrontMember   `json:"team"`
}

// StorefrontService is one bookable service of a provider's page.
type StorefrontService struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Description     string   `json:"description"`
	Category        string   `json:"category"`
	DurationMinutes int      `json:"durationMinutes"`
	PriceMinor      int64    `json:"priceMinor"`
	DepositMinor    int64    `json:"depositMinor"`
	Currency        string   `json:"currency"`
	Modes           []string `json:"modes"`
	ImageURL        *string  `json:"imageUrl"`
}

// StorefrontDayHours is one weekday of the schedule; no ranges means closed.
type StorefrontDayHours struct {
	Day    string               `json:"day"`
	Ranges []StorefrontTimeSpan `json:"ranges"`
}

// StorefrontTimeSpan is a local wall-clock range, "HH:mm", end exclusive.
type StorefrontTimeSpan struct {
	Start string `json:"start"`
	End   string `json:"end"`
}

// StorefrontArea is where the provider works (no address, no base ZIP, no coordinates).
type StorefrontArea struct {
	BaseCity     string   `json:"baseCity"`
	BaseState    string   `json:"baseState"`
	RadiusMiles  int      `json:"radiusMiles"`
	ServedCities []string `json:"servedCities"`
	ExtraAreas   []string `json:"extraAreas"`
}

// StorefrontMember is a team member customers can book.
type StorefrontMember struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

// Storefront is a seller's or provider's public page: the published settings plus real data (contract:
// components.schemas.Storefront). Kind says which; a provider's page has Provider and no products.
type Storefront struct {
	Kind             string               `json:"kind"`
	Provider         *StorefrontProvider  `json:"provider"`
	Slug             string               `json:"slug"`
	Name             string               `json:"name"`
	LogoURL          *string              `json:"logoUrl"`
	Description      *string              `json:"description"`
	Config           StoreConfig          `json:"config"`
	Preview          bool                 `json:"preview"`
	Indexable        bool                 `json:"indexable"`
	Verified         bool                 `json:"verified"`
	SellingSinceYear *int                 `json:"sellingSinceYear"`
	ContactEmail     *string              `json:"contactEmail"`
	Pickup           *StorefrontPickup    `json:"pickup"`
	Categories       []StorefrontCategory `json:"categories"`
	Featured         []ProductSummary     `json:"featured"`
	Reviews          *StorefrontReviews   `json:"reviews"`
	Products         StorefrontProducts   `json:"products"`
}

// StorePageSettings is GET/PUT /v1/seller/store-page: the draft, the published copy and who may do what.
type StorePageSettings struct {
	Kind                  string       `json:"kind"`
	Slug                  *string      `json:"slug"`
	Name                  string       `json:"name"`
	LogoURL               *string      `json:"logoUrl"`
	ContactEmail          *string      `json:"contactEmail"`
	Draft                 StoreConfig  `json:"draft"`
	Published             *StoreConfig `json:"published"`
	IsPublished           bool         `json:"isPublished"`
	Version               int64        `json:"version"`
	PublishedVersion      *int64       `json:"publishedVersion"`
	HasUnpublishedChanges bool         `json:"hasUnpublishedChanges"`
	UpdatedAt             *time.Time   `json:"updatedAt"`
	PublishedAt           *time.Time   `json:"publishedAt"`
	Approved              bool         `json:"approved"`
	CanEdit               bool         `json:"canEdit"`
	CanPublish            bool         `json:"canPublish"`
}

// StorePageDraftInput is PUT /v1/seller/store-page.
type StorePageDraftInput struct {
	Version int64       `json:"version"`
	Config  StoreConfig `json:"config"`
}

// StorePageVersionInput is POST /v1/seller/store-page/publish.
type StorePageVersionInput struct {
	Version int64 `json:"version"`
}

// StorePageAuditEntry is one line of the audit trail.
type StorePageAuditEntry struct {
	At      time.Time `json:"at"`
	Action  string    `json:"action"`
	Actor   string    `json:"actor"`
	Version int64     `json:"version"`
}

// StorePageAudit is GET /v1/seller/store-page/audit.
type StorePageAudit struct {
	Items []StorePageAuditEntry `json:"items"`
}

// StorePageProductList is GET /v1/seller/store-page/featured-search.
type StorePageProductList struct {
	Items []ProductSummary `json:"items"`
}

// CodeStoreAccentLowContrast (422): the accent cannot be read on the chosen look (WCAG AA).
const CodeStoreAccentLowContrast = "STORE_ACCENT_LOW_CONTRAST"

// CodeStoreNotApproved (422): only an approved organisation can publish its page.
const CodeStoreNotApproved = "STORE_NOT_APPROVED"

// StorefrontListItem is one store of the sitemap list.
type StorefrontListItem struct {
	Kind        string    `json:"kind"`
	Slug        string    `json:"slug"`
	PublishedAt time.Time `json:"publishedAt"`
}

// StorefrontList is GET /v1/storefronts.
type StorefrontList struct {
	Items []StorefrontListItem `json:"items"`
}

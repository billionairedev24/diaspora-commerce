// Package domain holds the search read-model types. It has no infrastructure dependencies.
package domain

import (
	"errors"
	"strings"
)

// Sort orders supported by the results page.
type Sort string

// Supported sorts.
const (
	SortRelevance Sort = "relevance"
	SortPriceAsc  Sort = "price_asc"
	SortPriceDesc Sort = "price_desc"
	SortRating    Sort = "rating"
	SortNewest    Sort = "newest"
)

// Query is a shopper search with the results-page filters from the storefront design.
type Query struct {
	Market      string
	Text        string
	Department  string
	Communities []string
	Fulfilment  string // AFROTRANSACT | SELLER
	SellerKind  string // PLATFORM | SELLER
	MinRating   float64
	MinPrice    int64
	MaxPrice    int64
	MaxDays     int // delivery speed filter, e.g. 1 = tomorrow
	InStockOnly bool
	NearLat     float64
	NearLon     float64
	WithinMiles int
	Sort        Sort
	Page        int
	Size        int
	// Ads asks for sponsored products at the configured slots (results pages only; rails and Lola leave it off).
	Ads bool
	// Track records the search in analytics (search.query.performed); rails and Lola send track=false.
	Track bool
	// CustomerID is the signed-in shopper (from the token), for click attribution only.
	CustomerID string
}

// MaxPageSize caps page size.
const MaxPageSize = 60

// ErrInvalidQuery is returned when a query is malformed.
var ErrInvalidQuery = errors.New("invalid query")

// ErrUnknownMarket is returned for a market the service does not serve (market filter).
var ErrUnknownMarket = errors.New("unknown market")

// Normalize fills defaults and validates bounds.
func (q Query) Normalize() (Query, error) {
	q.Text = strings.Join(strings.Fields(q.Text), " ")
	if len(q.Text) > 200 {
		return q, errors.Join(ErrInvalidQuery, errors.New("query longer than 200 characters"))
	}
	q.Market = strings.ToLower(strings.TrimSpace(q.Market))
	if q.Market == "" {
		q.Market = "us"
	}
	if q.Size <= 0 {
		q.Size = 24
	}
	if q.Size > MaxPageSize {
		q.Size = MaxPageSize
	}
	if q.Page < 0 {
		q.Page = 0
	}
	if q.MinPrice < 0 || (q.MaxPrice > 0 && q.MaxPrice < q.MinPrice) {
		return q, errors.Join(ErrInvalidQuery, errors.New("price range is inverted"))
	}
	switch q.Sort {
	case "":
		q.Sort = SortRelevance
	case SortRelevance, SortPriceAsc, SortPriceDesc, SortRating, SortNewest:
	default:
		return q, errors.Join(ErrInvalidQuery, errors.New("unknown sort "+string(q.Sort)))
	}
	if q.WithinMiles > 0 && (q.NearLat == 0 && q.NearLon == 0) {
		return q, errors.Join(ErrInvalidQuery, errors.New("distance filter needs a location"))
	}
	return q, nil
}

// Hit is one product card.
type Hit struct {
	ATIN         string  `json:"atin"`
	Title        string  `json:"title"`
	Brand        string  `json:"brand,omitempty"`
	CategoryPath string  `json:"categoryPath"`
	SellerName   string  `json:"sellerName"`
	SellerKind   string  `json:"sellerKind"`
	Fulfilment   string  `json:"fulfilment"`
	PriceMinor   int64   `json:"priceMinor"`
	Currency     string  `json:"currency"`
	Rating       float64 `json:"rating"`
	ReviewCount  int     `json:"reviewCount"`
	InStock      bool    `json:"inStock"`
	LowStock     bool    `json:"lowStock"`
	AvailableQty int     `json:"availableQty"`
	DeliveryDays int     `json:"deliveryDays"`
	ImageURL     string  `json:"imageUrl,omitempty"`
	OfferCount   int     `json:"offerCount"`
	Score        float64 `json:"score"`
	// Sponsored is set on ads only (always labelled "Sponsored" with the reason it was shown).
	Sponsored *Sponsored `json:"sponsored,omitempty"`

	// Internal fields used by the ad auction (never serialised).
	SellerID     string   `json:"-"`
	Department   string   `json:"-"`
	VariantATINs []string `json:"-"`
}

// LowStockThreshold matches the storefront's "Only N left" rule.
const LowStockThreshold = 5

// FacetBucket is one facet value with its count.
type FacetBucket struct {
	Value string `json:"value"`
	Count int64  `json:"count"`
}

// Page is a page of results with facets for the filter sidebar.
type Page struct {
	Hits     []Hit                    `json:"hits"`
	Total    int64                    `json:"total"`
	Page     int                      `json:"page"`
	Size     int                      `json:"size"`
	Facets   map[string][]FacetBucket `json:"facets"`
	Semantic bool                     `json:"semantic"`
	TookMs   int64                    `json:"tookMs"`
	// SearchID identifies this search for click reporting (POST /v1/search/clicks).
	SearchID string `json:"searchId,omitempty"`
}

// Suggestion is a completed phrase, optionally scoped to a department ("jollof seasoning in Groceries").
type Suggestion struct {
	Text       string `json:"text"`
	Department string `json:"department,omitempty"`
}

// Suggestions is the autocomplete dropdown: phrases plus up to five products.
type Suggestions struct {
	Phrases  []Suggestion `json:"phrases"`
	Products []Hit        `json:"products"`
}

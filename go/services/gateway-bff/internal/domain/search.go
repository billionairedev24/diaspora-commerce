package domain

import (
	"math"
	"strings"
)

// Search sorts accepted by the BFF.
const (
	SortRelevance = "relevance"
	SortPriceAsc  = "price_asc"
	SortPriceDesc = "price_desc"
	SortRating    = "rating"
	SortFastest   = "fastest"
)

// DefaultSearchSize is the BFF's default page size.
const DefaultSearchSize = 12

// SearchQuery is the storefront search state (GET /v1/search).
type SearchQuery struct {
	Q          string
	Department string
	Community  string
	Fulfilment string
	SellerKind string
	// SellerID limits the page to one seller's products (a business's public page); not a URL filter.
	SellerID  string
	MinRating *float64
	Price     string
	MaxDays   *int
	Sort      string
	Page      int
	Size      int
	// Ads blends sponsored products into the page (results pages; default on, ads=false opts out).
	Ads bool
}

// PriceBucket is a filter-rail price range in minor units (inclusive bounds).
type PriceBucket struct {
	ID    string
	Label string
	Min   int64 // 0 = open
	Max   int64 // 0 = open
}

// PriceBuckets mirror the storefront's filter rail.
var PriceBuckets = []PriceBucket{
	{ID: "u10", Label: "Under $10", Max: 999},
	{ID: "10-25", Label: "$10 to $25", Min: 1000, Max: 2500},
	{ID: "25-50", Label: "$25 to $50", Min: 2501, Max: 5000},
	{ID: "o50", Label: "$50 & above", Min: 5001},
}

// PriceBucketByID finds a bucket.
func PriceBucketByID(id string) (PriceBucket, bool) {
	for _, b := range PriceBuckets {
		if b.ID == id {
			return b, true
		}
	}
	return PriceBucket{}, false
}

// upstream facet bucket keys → BFF values and labels.
var (
	priceFacet = map[string]string{"under-10": "u10", "10-25": "10-25", "25-50": "25-50", "50-plus": "o50"}
	labels     = map[string]map[string]string{
		"fulfilment": {FulfilledByAfroTransact: "Fulfilled by AfroTransact", ShipsFromSeller: "Ships from seller"},
		"sellerKind": {SellerKindPlatform: "AfroTransact", SellerKindSeller: "Independent sellers"},
		"rating":     {"4": "4 & up", "3": "3 & up", "4.5": "4.5 & up"},
		"delivery":   {"1": "Get it by tomorrow", "2": "Within 2 days"},
	}
)

// FacetBucketFor maps an upstream facet bucket (search service) to the BFF value and label; ok=false drops it.
func FacetBucketFor(dimension, upstreamValue string) (value, label string, ok bool) {
	switch dimension {
	case "department", "community":
		return upstreamValue, upstreamValue, upstreamValue != ""
	case "fulfilment", "sellerKind":
		l, ok := labels[dimension][upstreamValue]
		return upstreamValue, l, ok
	case "rating":
		v := strings.TrimSuffix(upstreamValue, "+")
		l, ok := labels["rating"][v]
		return v, l, ok
	case "price":
		v, ok := priceFacet[upstreamValue]
		if !ok {
			return "", "", false
		}
		b, _ := PriceBucketByID(v)
		return v, b.Label, true
	case "delivery":
		switch upstreamValue {
		case "tomorrow":
			return "1", labels["delivery"]["1"], true
		case "2-days":
			return "2", labels["delivery"]["2"], true
		}
		return "", "", false
	default:
		return "", "", false
	}
}

// PageCount is ceil(total/size).
func PageCount(total int64, size int) int {
	if size <= 0 {
		return 0
	}
	return int(math.Ceil(float64(total) / float64(size)))
}

// DepartmentOf takes the department from a category path ("Groceries › Grains › Rice" → "Groceries").
func DepartmentOf(path string) string {
	dept, _, _ := strings.Cut(path, " › ")
	if dept == path {
		dept, _, _ = strings.Cut(path, "/")
	}
	return strings.TrimSpace(dept)
}

// Package searchindex is the shared Elasticsearch read model for products: the document shape, index mapping,
// analyzers (diaspora synonyms, folding, search-as-you-type) and naming. The indexer writes it; search reads it.
package searchindex

import (
	"embed"
	"fmt"
	"strings"
	"time"
)

//go:embed mapping.json
var files embed.FS

// Version of the mapping. Bump it to reindex into products-<market>-v<N> behind the alias with zero downtime.
const Version = 1

// Alias is the read alias for a market, for example products-us.
func Alias(market string) string { return "products-" + strings.ToLower(market) }

// IndexName is the physical index for a market and mapping version, for example products-us-v1.
func IndexName(market string) string { return fmt.Sprintf("%s-v%d", Alias(market), Version) }

// Mapping returns the index settings and mappings JSON.
func Mapping() ([]byte, error) {
	b, err := files.ReadFile("mapping.json")
	if err != nil {
		return nil, fmt.Errorf("read mapping: %w", err)
	}
	return b, nil
}

// Fulfilment values.
const (
	FulfilledByAfroTransact = "AFROTRANSACT"
	ShipsFromSeller         = "SELLER"
)

// Document statuses. Only LIVE documents are returned by search.
const (
	StatusLive    = "LIVE"
	StatusPending = "PENDING"
)

// Seller kinds.
const (
	SellerPlatform = "PLATFORM"
	SellerMarket   = "SELLER"
)

// ProductDocument is one product page with its buy-box offer (lowest-landed-price eligible offer).
type ProductDocument struct {
	ATIN         string         `json:"atin"`
	Title        string         `json:"title"`
	Brand        string         `json:"brand,omitempty"`
	Description  string         `json:"description,omitempty"`
	Keywords     []string       `json:"keywords,omitempty"`
	Department   string         `json:"department"`
	CategoryPath string         `json:"category_path"`
	Communities  []string       `json:"communities,omitempty"`
	SellerID     string         `json:"seller_id"`
	SellerName   string         `json:"seller_name"`
	SellerKind   string         `json:"seller_kind"`
	Fulfilment   string         `json:"fulfilment"`
	PriceMinor   int64          `json:"price_minor"`
	Currency     string         `json:"currency"`
	Rating       float64        `json:"rating"`
	ReviewCount  int            `json:"review_count"`
	AvailableQty int            `json:"available_qty"`
	InStock      bool           `json:"in_stock"`
	DeliveryDays int            `json:"delivery_days"`
	ShipsFrom    *GeoPoint      `json:"ships_from,omitempty"`
	ImageURL     string         `json:"image_url,omitempty"`
	OfferCount   int            `json:"offer_count"`
	VariantATINs []string       `json:"variant_atins,omitempty"`
	VariantQty   map[string]int `json:"variant_qty,omitempty"`
	Status       string         `json:"status,omitempty"`
	Embedding    []float32      `json:"embedding,omitempty"`
	Version      int64          `json:"version"`
	UpdatedAt    time.Time      `json:"updated_at"`
}

// GeoPoint is an Elasticsearch geo_point.
type GeoPoint struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

// LateMappings are the fields added to the mapping after the first release (strict mapping rejects unknown fields), put
// onto an existing index so it accepts them without a reindex. Documents indexed earlier simply lack them.
const LateMappings = `{"properties":{"keywords":{"type":"text","analyzer":"product_index","search_analyzer":"product_search"},"rating_version":{"type":"long"}}}`

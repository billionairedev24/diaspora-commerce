package port

import (
	"context"
	"encoding/json"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- catalog: one-step form, drafts, bulk upload

// CatalogDraft is catalog's ProductDraft (a saved form).
type CatalogDraft struct {
	ID           string          `json:"id"`
	ProductID    string          `json:"productId"`
	Title        string          `json:"title"`
	CategoryPath string          `json:"categoryPath"`
	Payload      json.RawMessage `json:"payload"`
	UpdatedAt    time.Time       `json:"updatedAt"`
	Version      int64           `json:"version"`
	Todo         int             `json:"todo"`
}

// DraftsQuery is the drafts table's query: search, kind (all | new), one sort key, page and size.
type DraftsQuery struct {
	Q, Kind, Sort string
	Page, Size    int
}

// CatalogImport is catalog's ProductImport.
type CatalogImport struct {
	ID              string     `json:"id"`
	Reference       string     `json:"reference"`
	FileName        string     `json:"fileName"`
	Format          string     `json:"format"`
	Mode            string     `json:"mode"`
	ProductType     string     `json:"productType"`
	Status          string     `json:"status"`
	ValidOnly       bool       `json:"validOnly"`
	TotalRows       int        `json:"totalRows"`
	ValidRows       int        `json:"validRows"`
	WarningRows     int        `json:"warningRows"`
	ErrorRows       int        `json:"errorRows"`
	ProductCount    int        `json:"productCount"`
	ProcessedRows   int        `json:"processedRows"`
	CreatedProducts int        `json:"createdProducts"`
	UpdatedProducts int        `json:"updatedProducts"`
	FailedRows      int        `json:"failedRows"`
	Header          []string   `json:"header"`
	FileWarnings    []string   `json:"fileWarnings"`
	UploadedByName  string     `json:"uploadedByName"`
	CreatedAt       time.Time  `json:"createdAt"`
	StartedAt       *time.Time `json:"startedAt"`
	FinishedAt      *time.Time `json:"finishedAt"`
	FileSHA256      string     `json:"fileSha256"`
	SameFileAs      string     `json:"sameFileAs"`
	OffersCreated   int        `json:"offersCreated"`
	HeldRows        int        `json:"heldRows"`
}

// CatalogImportPage is catalog's ProductImportPage.
type CatalogImportPage struct {
	Items []CatalogImport `json:"items"`
	Total int64           `json:"total"`
	Page  int             `json:"page"`
	Size  int             `json:"size"`
}

// CatalogImportRow is catalog's ProductImportRow.
type CatalogImportRow struct {
	RowNumber   int    `json:"rowNumber"`
	GroupKey    string `json:"groupKey"`
	SKU         string `json:"sku"`
	Status      string `json:"status"`
	Outcome     string `json:"outcome"`
	OutcomeNote string `json:"outcomeNote"`
	ProductID   string `json:"productId"`
	ATIN        string `json:"atin"`
	OfferID     string `json:"offerId"`
	Messages    []struct {
		Column      *string `json:"column"`
		ColumnIndex int     `json:"columnIndex"`
		Severity    string  `json:"severity"`
		Message     string  `json:"message"`
	} `json:"messages"`
	Cells  []string `json:"cells"`
	Images []struct {
		Slot    string  `json:"slot"`
		Source  string  `json:"source"`
		Ref     string  `json:"ref"`
		State   string  `json:"state"`
		URL     *string `json:"url"`
		Message *string `json:"message"`
		// Check is a finding catalog already put in the form's code, level and params (optional; the BFF derives one
		// from State when absent).
		Check *struct {
			Code   string         `json:"code"`
			Level  string         `json:"level"`
			Params map[string]any `json:"params"`
		} `json:"check"`
	} `json:"images"`
}

// CatalogImportRowPage is catalog's ProductImportRowPage.
type CatalogImportRowPage struct {
	Items []CatalogImportRow `json:"items"`
	Total int64              `json:"total"`
	Page  int                `json:"page"`
	Size  int                `json:"size"`
}

// UploadFile is a file the browser uploaded, relayed to catalog.
type UploadFile struct {
	Name        string
	ContentType string
	Data        []byte
}

// SellerListingClient calls catalog's product edit, draft and bulk upload endpoints with the member's token.
type SellerListingClient interface {
	Product(ctx context.Context, bearer, productID string) (CatalogOwnProduct, error)
	// ProductByATIN reads one of the organisation's own products by its ATIN (GET /v1/products/atin/{atin}).
	ProductByATIN(ctx context.Context, bearer, atin string) (CatalogOwnProduct, error)
	// Update sends the edited product (catalog PUT /v1/products/{id}); shipFrom is the resolved ship-from snapshot.
	Update(ctx context.Context, bearer, productID string, d domain.ProductDraft, shipFrom *CatalogShipFrom) (CatalogOwnProduct, error)
	// CreateFull is POST /v1/products with the listing details and the resolved ship-from.
	CreateFull(ctx context.Context, bearer string, d domain.ProductDraft, shipFrom *CatalogShipFrom) (CatalogOwnProduct, error)
	// Drafts is one page of GET /v1/product-drafts and the total from X-Total-Count.
	Drafts(ctx context.Context, bearer string, q DraftsQuery) ([]CatalogDraft, int64, error)
	Draft(ctx context.Context, bearer, id string) (CatalogDraft, error)
	CreateDraft(ctx context.Context, bearer string, in domain.SaveProductDraftRequest) (CatalogDraft, error)
	SaveDraft(ctx context.Context, bearer, id string, in domain.SaveProductDraftRequest) (CatalogDraft, error)
	DeleteDraft(ctx context.Context, bearer, id string) error
	ImportTemplate(ctx context.Context, bearer, mode, productType, categoryID, format string) (domain.FileDownload, error)
	Upload(ctx context.Context, bearer string, f UploadFile, mode, productType, uploadedByName string) (CatalogImport, error)
	Imports(ctx context.Context, bearer string, page, size int) (CatalogImportPage, error)
	Import(ctx context.Context, bearer, id string) (CatalogImport, error)
	ImportRows(ctx context.Context, bearer, id, status, outcome string, page, size int) (CatalogImportRowPage, error)
	ImportReport(ctx context.Context, bearer, id, format string) (domain.FileDownload, error)
	StartImport(ctx context.Context, bearer, id string, validOnly bool) (CatalogImport, error)
	// ImageUsage is catalog POST /v1/image-library/usage: the SKUs using each URL, and which SKUs exist.
	ImageUsage(ctx context.Context, bearer string, urls, skus []string) (CatalogImageUsage, error)
	// AttachLibraryImages is catalog POST /v1/image-library/attach.
	AttachLibraryImages(ctx context.Context, bearer string, skus []string) (domain.AttachLibraryImagesResult, error)
	// Brands is catalog GET /v1/brands (the brands of a category).
	Brands(ctx context.Context, bearer string, q domain.BrandQuery) (domain.BrandList, error)
	// CreateBrand is catalog POST /v1/brands (adds a pending brand, or returns the existing match).
	CreateBrand(ctx context.Context, bearer, name string, q domain.BrandQuery) (domain.CreatedBrand, error)
}

// CatalogImageUsage is catalog's ImageUsage.
type CatalogImageUsage struct {
	Usage []struct {
		URL  string   `json:"url"`
		SKUs []string `json:"skus"`
	} `json:"usage"`
	KnownSKUs []string `json:"knownSkus"`
}

// ListingWriterClient is ai-gateway's listing writer (POST /v1/ai/listing-writer) with the member's token.
type ListingWriterClient interface {
	Write(ctx context.Context, bearer string, in domain.ListingWriterRequest) (domain.ListingWriterDraft, error)
}

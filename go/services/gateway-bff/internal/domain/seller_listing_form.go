package domain

import (
	"encoding/json"
	"time"
)

// ---------------------------------------------------------------- one-step product form (S307)

// ProductVariant is a stored variant as the edit form loads it.
type ProductVariant struct {
	ATIN           string              `json:"atin"`
	SellerSKU      string              `json:"sellerSku"`
	GTIN           *string             `json:"gtin"`
	GTINExempt     bool                `json:"gtinExempt"`
	Options        map[string]string   `json:"options"`
	PriceMinor     int64               `json:"priceMinor"`
	CompareAtMinor *int64              `json:"compareAtMinor"`
	Quantity       int                 `json:"quantity"`
	Images         []ProductImageInput `json:"images"`
}

// ListingDetails is a stored listing's description, condition, keywords, fulfilment, package, handling and ship-from.
type ListingDetails struct {
	Description  *string            `json:"description"`
	Condition    string             `json:"condition"`
	Keywords     []string           `json:"keywords"`
	Fulfilment   string             `json:"fulfilment"`
	Packaging    *PackageDimensions `json:"packaging"`
	HandlingDays int                `json:"handlingDays"`
	ShipFromID   *string            `json:"shipFromId"`
}

// SellerProductDetail is GET /v1/seller/listings/{productId}: everything the one-step form edits.
type SellerProductDetail struct {
	ProductID         string              `json:"productId"`
	ATIN              string              `json:"atin"`
	Status            string              `json:"status"`
	StatusLabel       string              `json:"statusLabel"`
	ModerationNote    *string             `json:"moderationNote"`
	CategoryID        *string             `json:"categoryId"`
	CategoryRequestID *string             `json:"categoryRequestId"`
	CategoryPath      string              `json:"categoryPath"`
	ProductType       string              `json:"productType"`
	Title             string              `json:"title"`
	Attributes        map[string]string   `json:"attributes"`
	Images            []ProductImageInput `json:"images"`
	VariationTheme    string              `json:"variationTheme"`
	Variants          []ProductVariant    `json:"variants"`
	PreorderEligible  bool                `json:"preorderEligible"`
	Listing           ListingDetails      `json:"listing"`
	Currency          string              `json:"currency"`
	Version           int64               `json:"version"`
	UpdatedAt         time.Time           `json:"updatedAt"`
}

// ProductDraftSummary is one saved draft of the form.
type ProductDraftSummary struct {
	ID           string    `json:"id"`
	ProductID    *string   `json:"productId"`
	Title        string    `json:"title"`
	CategoryPath *string   `json:"categoryPath"`
	UpdatedAt    time.Time `json:"updatedAt"`
	Version      int64     `json:"version"`
}

// ProductDraftList is GET /v1/seller/product-drafts.
type ProductDraftList struct {
	Items []ProductDraftSummary `json:"items"`
}

// SavedProductDraft is a draft with the form's input.
type SavedProductDraft struct {
	ProductDraftSummary
	Payload json.RawMessage `json:"payload"`
}

// SaveProductDraftRequest creates (productId) or autosaves (version) a draft.
type SaveProductDraftRequest struct {
	ProductID    string          `json:"productId,omitempty"`
	Title        string          `json:"title,omitempty"`
	CategoryPath string          `json:"categoryPath,omitempty"`
	Payload      json.RawMessage `json:"payload"`
	Version      *int64          `json:"version,omitempty"`
}

// MaxDraftPayloadBytes bounds a draft's saved input.
const MaxDraftPayloadBytes = 256 << 10

// Validate checks a draft save request.
func (r SaveProductDraftRequest) Validate(update bool) map[string]string {
	errs := map[string]string{}
	if len(r.Payload) == 0 || r.Payload[0] != '{' {
		errs["payload"] = "Required: the form's input as an object"
	} else if len(r.Payload) > MaxDraftPayloadBytes {
		errs["payload"] = "The draft is too large"
	}
	if len([]rune(r.Title)) > 200 {
		errs["title"] = "At most 200 characters"
	}
	if update && r.Version == nil {
		errs["version"] = "Required"
	}
	return errs
}

// AIListingWriter tells the form whether to offer the AI listing writer.
type AIListingWriter struct {
	Available bool `json:"available"`
	// Reason is PLAN (the plan doesn't include it) or ROLE (the member may not use AI tools); nil when available.
	Reason *string `json:"reason"`
}

// ImportLimits are the bulk upload limits shown on the upload page.
type ImportLimits struct {
	MaxFileBytes          int64 `json:"maxFileBytes"`
	MaxRows               int   `json:"maxRows"`
	MaxImagesPerProduct   int   `json:"maxImagesPerProduct"`
	MaxVariantsPerProduct int   `json:"maxVariantsPerProduct"`
}

// BulkUploadLimits mirror catalog's ImportLimits.
var BulkUploadLimits = ImportLimits{MaxFileBytes: 10 << 20, MaxRows: 5000, MaxImagesPerProduct: 10, MaxVariantsPerProduct: 200}

// ListingContext is GET /v1/seller/catalog/listing-context.
type ListingContext struct {
	AIListingWriter AIListingWriter `json:"aiListingWriter"`
	Moderated       bool            `json:"moderated"`
	ImportLimits    ImportLimits    `json:"importLimits"`
}

// AIListingWriterPlans are the plans whose features include the AI listing writer.
var AIListingWriterPlans = []string{"seller_growth", "seller_pro"}

// Reasons the AI listing writer is not offered.
const (
	AIUnavailablePlan = "PLAN"
	AIUnavailableRole = "ROLE"
)

// CodePlanFeatureRequired is the problem code of a feature the organisation's plan doesn't include.
const CodePlanFeatureRequired = "PLAN_FEATURE_REQUIRED"

// ListingWriterRequest asks the AI listing writer for a draft.
type ListingWriterRequest struct {
	ProductType string            `json:"productType"`
	Title       string            `json:"title"`
	Attributes  map[string]string `json:"attributes,omitempty"`
	Tone        string            `json:"tone,omitempty"`
}

// ListingWriterDraft is the writer's suggestion (never saved or published by itself).
type ListingWriterDraft struct {
	Title       string   `json:"title"`
	Description string   `json:"description"`
	Bullets     []string `json:"bullets"`
}

// ---------------------------------------------------------------- bulk upload (S308)

// Bulk upload statuses, row statuses and outcomes.
var (
	ImportModes        = []string{"CREATE", "UPDATE"}
	ImportRowStatuses  = []string{"VALID", "WARNING", "ERROR"}
	ImportRowOutcomes  = []string{"PENDING", "CREATED", "UPDATED", "FAILED", "SKIPPED"}
	SpreadsheetFormats = []string{"xlsx", "csv"}
)

// ProductImport is one bulk upload: validation counts and import progress.
type ProductImport struct {
	ID              string     `json:"id"`
	Reference       string     `json:"reference"`
	FileName        string     `json:"fileName"`
	Format          string     `json:"format"`
	Mode            string     `json:"mode"`
	ProductType     *string    `json:"productType"`
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
	UploadedByName  *string    `json:"uploadedByName"`
	CreatedAt       time.Time  `json:"createdAt"`
	StartedAt       *time.Time `json:"startedAt"`
	FinishedAt      *time.Time `json:"finishedAt"`
	ReportURL       string     `json:"reportUrl"`
}

// ProductImportPage is the upload history.
type ProductImportPage struct {
	Items []ProductImport `json:"items"`
	Total int64           `json:"total"`
	Page  int             `json:"page"`
	Size  int             `json:"size"`
}

// ImportRowMessage is a problem of one row, on one column.
type ImportRowMessage struct {
	Column      *string `json:"column"`
	ColumnIndex int     `json:"columnIndex"`
	Severity    string  `json:"severity"`
	Message     string  `json:"message"`
}

// ProductImportRow is one row of the preview.
type ProductImportRow struct {
	RowNumber   int                `json:"rowNumber"`
	GroupKey    string             `json:"groupKey"`
	SKU         string             `json:"sku"`
	Status      string             `json:"status"`
	Outcome     string             `json:"outcome"`
	OutcomeNote *string            `json:"outcomeNote"`
	ProductID   *string            `json:"productId"`
	Messages    []ImportRowMessage `json:"messages"`
	Cells       []string           `json:"cells"`
}

// ProductImportRowPage is one page of the preview.
type ProductImportRowPage struct {
	Items []ProductImportRow `json:"items"`
	Total int64              `json:"total"`
	Page  int                `json:"page"`
	Size  int                `json:"size"`
}

// FileDownload is a generated file relayed to the browser (template, error report).
type FileDownload struct {
	FileName    string
	ContentType string
	Content     []byte
}

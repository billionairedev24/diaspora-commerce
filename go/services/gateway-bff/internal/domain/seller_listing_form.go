package domain

import (
	"encoding/json"
	"slices"
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
	// SellerSKUGenerated is true when catalog generated the SKU because it was left empty.
	SellerSKUGenerated bool             `json:"sellerSkuGenerated"`
	TaxFacts           map[string]any   `json:"taxFacts"`
	TaxStatus          string           `json:"taxStatus"`
	MissingTaxFacts    []MissingTaxFact `json:"missingTaxFacts"`
	// Packaging is this variant's box as shipped (nil for items that ship nothing and for listings saved before
	// packages were per variant: the form then starts from the listing's package).
	Packaging *PackageDimensions `json:"packaging"`
}

// MissingTaxFact is a question still to answer before a variant can go live, in plain words.
type MissingTaxFact struct {
	FactID  string  `json:"factId"`
	Label   *string `json:"label,omitempty"`
	Message string  `json:"message"`
}

// ListingDetails is a stored listing's description, condition, keywords, fulfilment, package, handling and ship-from.
type ListingDetails struct {
	Description     *string                `json:"description"`
	Condition       string                 `json:"condition"`
	Keywords        []string               `json:"keywords"`
	Fulfilment      string                 `json:"fulfilment"`
	Packaging       *PackageDimensions     `json:"packaging"`
	HandlingDays    int                    `json:"handlingDays"`
	ShipFromID      *string                `json:"shipFromId"`
	SizeChartID     *string                `json:"sizeChartId"`
	FitNote         *string                `json:"fitNote"`
	SellerBehaviour map[string]any         `json:"sellerBehaviour"`
	DangerousGoods  *bool                  `json:"dangerousGoods"`
	Documents       []ListingDocumentInput `json:"documents"`
	Exemptions      map[string]string      `json:"exemptions"`
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
	// Todo is how many of the five basics (category, title, a photo, a price, a description) the draft lacks.
	Todo int `json:"todo"`
}

// ProductDraftList is GET /v1/seller/product-drafts: one page and how many drafts match in all.
type ProductDraftList struct {
	Items []ProductDraftSummary `json:"items"`
	Total int64                 `json:"total"`
	Page  int                   `json:"page"`
	Size  int                   `json:"size"`
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
	// MaxFeedFileBytes and MaxFeedRows are the limits of the price and quantity feed (S394) and the stock file (S395).
	MaxFeedFileBytes int64 `json:"maxFeedFileBytes"`
	MaxFeedRows      int   `json:"maxFeedRows"`
}

// BulkUploadLimits mirror catalog's ImportLimits.
var BulkUploadLimits = ImportLimits{MaxFileBytes: 10 << 20, MaxRows: 5000, MaxImagesPerProduct: 10, MaxVariantsPerProduct: 200,
	MaxFeedFileBytes: 25 << 20, MaxFeedRows: 50000}

// Bulk upload modes (catalog mode names).
const (
	ImportModeCreate   = "CREATE"
	ImportModeUpdate   = "UPDATE"
	ImportModeOffer    = "OFFER"
	ImportModePriceQty = "PRICE_QTY"
)

// MaxFileBytesFor is the largest file of a mode: the feed (price and quantity) takes 25 MB, the others 10 MB (S394).
func MaxFileBytesFor(mode string) int64 {
	if mode == ImportModePriceQty {
		return BulkUploadLimits.MaxFeedFileBytes
	}
	return BulkUploadLimits.MaxFileBytes
}

// ListingContext is GET /v1/seller/catalog/listing-context.
type ListingContext struct {
	AIListingWriter AIListingWriter `json:"aiListingWriter"`
	Moderated       bool            `json:"moderated"`
	ImportLimits    ImportLimits    `json:"importLimits"`
}

// AIListingWriterPlans are the plans assumed to include the AI listing writer when the seller service does not say
// (it normally does: staff set aiListingWriter per plan).
var AIListingWriterPlans = []string{"seller_growth", "seller_pro"}

// PlanHasAIListingWriter is whether a plan includes the AI listing writer: the plan's entitlement when the seller
// service sent one, else the built-in list.
func PlanHasAIListingWriter(planCode string, entitled, known bool) bool {
	if known {
		return entitled
	}
	return slices.Contains(AIListingWriterPlans, planCode)
}

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
	ImportModes        = []string{ImportModeCreate, ImportModeUpdate, ImportModeOffer, ImportModePriceQty}
	ImportRowStatuses  = []string{"VALID", "WARNING", "ERROR"}
	ImportRowOutcomes  = []string{"PENDING", "CREATED", "UPDATED", "OFFER_CREATED", "HELD", "FAILED", "SKIPPED"}
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
	// FileSHA256 and SameFileAs: the organisation uploaded this file before (SameFileAs is that upload's id; S393).
	FileSHA256 *string `json:"fileSha256"`
	SameFileAs *string `json:"sameFileAs"`
	// OffersCreated are rows that put an offer on an existing page; HeldRows are valid rows that are not for sale yet.
	OffersCreated int `json:"offersCreated"`
	HeldRows      int `json:"heldRows"`
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
	RowNumber   int     `json:"rowNumber"`
	GroupKey    string  `json:"groupKey"`
	SKU         string  `json:"sku"`
	Status      string  `json:"status"`
	Outcome     string  `json:"outcome"`
	OutcomeNote *string `json:"outcomeNote"`
	ProductID   *string `json:"productId"`
	// ATIN is the page the row matched or ended on, OfferID the offer it created or changed (offer rows, S393).
	ATIN     *string            `json:"atin"`
	OfferID  *string            `json:"offerId"`
	Messages []ImportRowMessage `json:"messages"`
	Cells    []string           `json:"cells"`
	Images   []ImportRowImage   `json:"images"`
}

// ImportRowImage is one image of a preview row (S310): a library image FOUND (thumbnail), a LINK copied at import,
// HELD by moderation, MISSING from the library or a BAD_LINK.
type ImportRowImage struct {
	Slot    string  `json:"slot"`
	Source  string  `json:"source"`
	Ref     string  `json:"ref"`
	State   string  `json:"state"`
	URL     *string `json:"url"`
	Message *string `json:"message"`
	// Check is the finding in the same code, level and params as the product form's image cards (S391), so the preview
	// shows the same plain message; nil for an image that is fine.
	Check *ImageCheck `json:"check"`
}

// ImageLibraryItem is one image of the organisation's image library with the SKUs that use it.
type ImageLibraryItem struct {
	AssetID   string    `json:"assetId"`
	FileName  string    `json:"fileName"`
	SKU       *string   `json:"sku"`
	Slot      *string   `json:"slot"`
	Status    string    `json:"status"`
	URL       *string   `json:"url"`
	Width     int       `json:"width"`
	Height    int       `json:"height"`
	Bytes     int64     `json:"bytes"`
	Flags     []string  `json:"flags"`
	CreatedAt time.Time `json:"createdAt"`
	UsedBy    []string  `json:"usedBy"`
	SKUKnown  bool      `json:"skuKnown"`
	// Verdict and Checks are the image rules' answer, shown with the same messages as the product form (S388, S391).
	Verdict *string      `json:"verdict"`
	Checks  []ImageCheck `json:"checks"`
}

// ImageLibraryPage is one page of the image library.
type ImageLibraryPage struct {
	Items []ImageLibraryItem `json:"items"`
	Total int64              `json:"total"`
	Page  int                `json:"page"`
	Size  int                `json:"size"`
}

// AttachLibraryImagesResult is what "attach matching images" changed.
type AttachLibraryImagesResult struct {
	ProductsUpdated int                    `json:"productsUpdated"`
	ImagesAttached  int                    `json:"imagesAttached"`
	Skipped         []AttachLibrarySkipped `json:"skipped"`
}

// AttachLibrarySkipped is a listing "attach matching images" left unchanged, with the reason.
type AttachLibrarySkipped struct {
	SKU    string `json:"sku"`
	Reason string `json:"reason"`
}

// BrandQuery asks for the brands of a category (a node id or code) or of every category using a product type.
type BrandQuery struct {
	ProductType string
	Category    string
	Q           string
}

// BrandList is the product form's brand choices for one category: the brands of its department and below, never the
// brands of unrelated departments. DepartmentName heads the group ("Brands in Fashion").
type BrandList struct {
	DepartmentID   *string `json:"departmentId"`
	DepartmentName *string `json:"departmentName"`
	Brands         []Brand `json:"brands"`
}

// Brand is one brand choice: OWN (the organisation typed it in or uses it) or CATALOG; PENDING brands await review.
type Brand struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Source      string `json:"source"`
	Status      string `json:"status"`
	OwnProducts int    `json:"ownProducts"`
	Products    int    `json:"products"`
}

// CreatedBrand is the answer to typing in a brand: the existing match (Created false) or a new pending brand.
type CreatedBrand struct {
	Brand   Brand `json:"brand"`
	Created bool  `json:"created"`
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

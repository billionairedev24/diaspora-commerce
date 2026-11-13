package domain

import "time"

// Catalog matching, offers on an existing page, change requests and image tasks as Seller Center sees them
// (docs/engineering/bulk-upload-and-images.md, sections 3 and 6). catalog owns the page; the seller sells an offer on it.

// Match results (catalog's MatchResult.result).
const (
	MatchFound      = "MATCH"
	MatchNone       = "NO_MATCH"
	MatchInvalid    = "INVALID"
	MatchRestricted = "RESTRICTED"
)

// Blocker is why the organisation cannot do something, in plain words.
type Blocker struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// MatchImage is the page's main picture.
type MatchImage struct {
	URL    string `json:"url"`
	Width  *int   `json:"width"`
	Height *int   `json:"height"`
}

// MatchVariant is one child (size, colour) of a matched page; each child is its own ATIN and takes its own offer.
type MatchVariant struct {
	ATIN    string            `json:"atin"`
	Options map[string]string `json:"options"`
}

// CatalogMatch is the answer to "does a page already exist for this barcode, or brand and part number?".
type CatalogMatch struct {
	Result         string            `json:"result"`
	Digits         *string           `json:"digits"`
	ATIN           *string           `json:"atin"`
	ParentATIN     *string           `json:"parentAtin"`
	ProductStatus  *string           `json:"productStatus"`
	Title          *string           `json:"title"`
	Brand          *string           `json:"brand"`
	Image          *MatchImage       `json:"image"`
	Category       *string           `json:"category"`
	VariantOptions map[string]string `json:"variantOptions"`
	Variants       []MatchVariant    `json:"variants"`
	// SellerCount is how many sellers list the matched variant (offers; null when it cannot be read).
	SellerCount    *int      `json:"sellerCount"`
	CanOffer       bool      `json:"canOffer"`
	Blockers       []Blocker `json:"blockers"`
	CanCreate      bool      `json:"canCreate"`
	CreateBlockers []Blocker `json:"createBlockers"`
	Suggestion     *string   `json:"suggestion"`
	Message        *string   `json:"message"`
}

// MatchInput is a barcode (UPC, EAN, GTIN, ISBN): the optional exact shortcut of "Add a product".
type MatchInput struct {
	GTIN string `json:"gtin"`
}

// MatchCategory is a category as the seller sees it: an editable chip, never an id on screen.
type MatchCategory struct {
	ID   string `json:"id"`
	Path string `json:"path"`
}

// CandidateInput is what the seller is selling, as typed in the one search box: the name (the brand and the size may be inside it).
// The category is the optional chip.
type CandidateInput struct {
	Query      string `json:"query"`
	CategoryID string `json:"categoryId,omitempty"`
}

// MatchCandidate is one existing product page the seller may be selling. It carries nothing about any seller except how many sell it.
type MatchCandidate struct {
	ParentATIN string         `json:"parentAtin"`
	ATIN       *string        `json:"atin"`
	Title      string         `json:"title"`
	Brand      *string        `json:"brand"`
	Image      *MatchImage    `json:"image"`
	Category   *string        `json:"category"`
	SizeLabel  *string        `json:"sizeLabel"`
	Variants   []MatchVariant `json:"variants"`
	// Kind is SAME_NAME (exact) or SIMILAR (a suggestion); both need the seller's click.
	Kind   string `json:"kind"`
	Likely bool   `json:"likely"`
	// SellerCount is how many sellers list the page (offers; null when it cannot be read).
	SellerCount *int      `json:"sellerCount"`
	CanOffer    bool      `json:"canOffer"`
	Blockers    []Blocker `json:"blockers"`
}

// CandidateList is the answer to the search box: up to six pages, the category suggestion and whether the search could answer.
type CandidateList struct {
	Candidates        []MatchCandidate `json:"candidates"`
	SuggestedCategory *MatchCategory   `json:"suggestedCategory"`
	Category          *MatchCategory   `json:"category"`
	TooShort          bool             `json:"tooShort"`
	SearchUnavailable bool             `json:"searchUnavailable"`
}

// Choices of the candidate list.
const (
	ChoiceSellExisting = "SELL_EXISTING"
	ChoiceNoneOfThese  = "NONE_OF_THESE"
)

// DecisionInput is the seller's answer to the list: "This is my product" (with the one-tap size confirmation) or "Not here, add it as new".
type DecisionInput struct {
	Choice        string `json:"choice"`
	Query         string `json:"query"`
	CategoryID    string `json:"categoryId,omitempty"`
	ATIN          string `json:"atin,omitempty"`
	SizeConfirmed bool   `json:"sizeConfirmed"`
}

// MatchDecision is catalog's recorded answer.
type MatchDecision struct {
	DecisionID              *string   `json:"decisionId"`
	Choice                  string    `json:"choice"`
	ATIN                    *string   `json:"atin"`
	CanOffer                bool      `json:"canOffer"`
	Blockers                []Blocker `json:"blockers"`
	PossibleDuplicateQueued bool      `json:"possibleDuplicateQueued"`
}

// Offer statuses a new offer starts in.
const (
	OfferActive  = "ACTIVE"
	OfferTaxHeld = "TAX_HELD"
)

// OfferOnlyInput is what a seller sets on an offer to a page that already exists: terms only, never page content.
type OfferOnlyInput struct {
	ATIN               string          `json:"atin"`
	SellerSKU          string          `json:"sellerSku"`
	Condition          string          `json:"condition"`
	ConditionNote      string          `json:"conditionNote"`
	PriceMinor         int64           `json:"priceMinor"`
	ListPriceMinor     *int64          `json:"listPriceMinor"`
	ShippingMinor      *int64          `json:"shippingMinor"`
	Fulfilment         string          `json:"fulfilment"`
	Quantity           *int            `json:"quantity"`
	HandlingDays       *int            `json:"handlingDays"`
	ShipFromLocationID string          `json:"shipFromLocationId"`
	Packaging          *OfferPackaging `json:"packaging"`
}

// OfferPackaging is the box the seller ships the item in (Fulfilled by AfroTransact only).
type OfferPackaging struct {
	LengthIn float64 `json:"lengthIn"`
	WidthIn  float64 `json:"widthIn"`
	HeightIn float64 `json:"heightIn"`
	WeightLb float64 `json:"weightLb"`
}

// OfferOnlyResult is a new offer: ACTIVE, or TAX_HELD while the seller's own tax decision for the item is checked.
type OfferOnlyResult struct {
	OfferID    string  `json:"offerId"`
	ATIN       string  `json:"atin"`
	SellerSKU  string  `json:"sellerSku"`
	Status     string  `json:"status"`
	HeldReason *string `json:"heldReason"`
	// Message is plain English for API readers; Seller Center translates by status.
	Message string `json:"message"`
}

// ChangeRequestTypes are the types a seller may file (catalog's ChangeRequestType).
var ChangeRequestTypes = []string{"IMAGE_ADD", "IMAGE_REPLACE", "TITLE", "ATTRIBUTE", "DESCRIPTION", "CATEGORY", "BRAND", "NEW_VARIANT", "WRONG_MATCH", "DUPLICATE_PAGE"}

// ChangeRequestStatuses are the states a request is in.
var ChangeRequestStatuses = []string{"PENDING", "APPLIED", "REJECTED", "WITHDRAWN"}

// ChangeRequestInput is "Suggest a change", "This is the wrong product" or "This is a duplicate".
type ChangeRequestInput struct {
	Type    string         `json:"type"`
	Field   string         `json:"field,omitempty"`
	Payload map[string]any `json:"payload,omitempty"`
	Note    string         `json:"note,omitempty"`
}

// ChangeRequest is one request and its decision (Waiting, Applied, Not applied because ...).
type ChangeRequest struct {
	ID          string         `json:"id"`
	ATIN        string         `json:"atin"`
	Title       *string        `json:"title"`
	Type        string         `json:"type"`
	Field       *string        `json:"field"`
	Payload     map[string]any `json:"payload"`
	Note        *string        `json:"note"`
	Status      string         `json:"status"`
	RequestedAt time.Time      `json:"requestedAt"`
	SLADueAt    *time.Time     `json:"slaDueAt"`
	DecidedAt   *time.Time     `json:"decidedAt"`
	Reason      *string        `json:"reason"`
	Resolution  *string        `json:"resolution"`
}

// ChangeRequestPage is one page of the organisation's requests.
type ChangeRequestPage struct {
	Items []ChangeRequest `json:"items"`
	Total int64           `json:"total"`
	Page  int             `json:"page"`
	Size  int             `json:"size"`
}

// ImageCheck is one finding of the image rules; the code is translated by Seller Center, the message is English.
type ImageCheck struct {
	Code    string         `json:"code"`
	Level   string         `json:"level"`
	Params  map[string]any `json:"params"`
	Message string         `json:"message"`
}

// ImageTaskReason is one finding behind an "Improve your image" task.
type ImageTaskReason struct {
	Code    string `json:"code"`
	Level   string `json:"level"`
	Message string `json:"message"`
}

// ImageTask is an "Improve your image" task of a live listing: IMPROVE (passes with a warning) or FAIL (breaks a rule,
// with the end of the grace period).
type ImageTask struct {
	ProductID    string            `json:"productId"`
	ATIN         *string           `json:"atin"`
	Title        string            `json:"title"`
	State        string            `json:"state"`
	Reasons      []ImageTaskReason `json:"reasons"`
	FailingSince *time.Time        `json:"failingSince"`
	HideAfter    *time.Time        `json:"hideAfter"`
	DaysLeft     *int              `json:"daysLeft"`
	Hidden       bool              `json:"hidden"`
}

// ImageTaskList is the organisation's image tasks, failing ones first.
type ImageTaskList struct {
	Items []ImageTask `json:"items"`
}

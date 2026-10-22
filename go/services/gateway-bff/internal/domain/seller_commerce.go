package domain

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"
)

// ---------------------------------------------------------------- dashboard

// ChartSeries is one plotted series.
type ChartSeries struct {
	Key    string `json:"key"`
	Label  string `json:"label"`
	Tone   string `json:"tone"`
	Format string `json:"format,omitempty"`
}

// ChartRow is one category with a value per series.
type ChartRow struct {
	Category string             `json:"category"`
	Values   map[string]float64 `json:"values"`
}

// ChartSpec is one dashboard chart; Available=false when its upstream could not answer.
type ChartSpec struct {
	ID            string        `json:"id"`
	Title         string        `json:"title"`
	Kind          string        `json:"kind"`
	CategoryLabel string        `json:"categoryLabel"`
	ValueFormat   string        `json:"valueFormat"`
	Series        []ChartSeries `json:"series"`
	Rows          []ChartRow    `json:"rows"`
	Available     bool          `json:"available"`
}

// Kpi is a headline number.
type Kpi struct {
	Label  string `json:"label"`
	Value  string `json:"value"`
	Detail string `json:"detail"`
	Tone   string `json:"tone"`
}

// TodoItem links the home page to a module that needs attention.
type TodoItem struct {
	Label  string `json:"label"`
	Module string `json:"module"`
	Tone   string `json:"tone"`
}

// Dashboard is GET /v1/seller/dashboard.
type Dashboard struct {
	Portal    string      `json:"portal"`
	RangeDays int         `json:"rangeDays"`
	Kpis      []Kpi       `json:"kpis"`
	Charts    []ChartSpec `json:"charts"`
	Todo      []TodoItem  `json:"todo"`
}

// UnavailableChart is a chart placeholder whose data source is down.
func UnavailableChart(id, title, kind, categoryLabel, format string, series []ChartSeries) ChartSpec {
	return ChartSpec{ID: id, Title: title, Kind: kind, CategoryLabel: categoryLabel, ValueFormat: format, Series: series, Rows: []ChartRow{}, Available: false}
}

// ---------------------------------------------------------------- orders

// Shipment statuses (orders ShipmentStatus).
var shipmentLabels = map[string]string{
	"PENDING_PAYMENT": "Awaiting payment", "PLACED": "New", "CONFIRMED": "Confirmed", "PACKED": "Packed", "READY": "Ready to ship",
	"PICKED_UP": "Picked up", "IN_TRANSIT": "In transit", "OUT_FOR_DELIVERY": "Out for delivery", "DELIVERY_FAILED": "Delivery failed",
	"DELIVERED": "Delivered", "RETURNED": "Returned", "CANCELLED": "Cancelled",
}

// ShipmentStatusLabel names a shipment status for sellers.
func ShipmentStatusLabel(s string) string {
	if l, ok := shipmentLabels[s]; ok {
		return l
	}
	return s
}

// IsShipmentStatus reports whether s is a known shipment status.
func IsShipmentStatus(s string) bool { _, ok := shipmentLabels[s]; return ok }

// SellerStatuses are the only statuses a seller may set (orders enforces the same rule). DELIVERED is the handover of
// a pickup order at the seller's own shop, with the customer's code; orders offers it only for those.
var SellerStatuses = []string{"CONFIRMED", "PACKED", "READY", "PICKED_UP", "DELIVERED"}

// labelStatuses are the shipment statuses a label can still be bought in.
var labelStatuses = []string{"CONFIRMED", "PACKED", "READY"}

// IsHandoverCode reports whether v is a 4-digit handover code.
func IsHandoverCode(v string) bool {
	if len(v) != 4 {
		return false
	}
	for _, r := range v {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// IsSellerStatus reports whether a seller may set status s.
func IsSellerStatus(s string) bool { return slices.Contains(SellerStatuses, s) }

// SellerNextActions keeps the seller-settable statuses of orders' nextActions (lowercase upstream), in flow order.
func SellerNextActions(next []string) []string {
	out := []string{}
	for _, s := range SellerStatuses {
		for _, n := range next {
			if strings.EqualFold(n, s) {
				out = append(out, s)
				break
			}
		}
	}
	return out
}

// CanBuyLabel reports whether a label can be bought: a seller-fulfilled carrier shipment that is confirmed, packed or ready and has no tracking yet.
func CanBuyLabel(fulfilment, deliveryMethod, status, tracking string) bool {
	if fulfilment != ShipsFromSeller || tracking != "" {
		return false
	}
	if dm := strings.ToUpper(deliveryMethod); dm == "PICKUP" || dm == "AFROTRANSACT_DELIVERY" {
		return false
	}
	return slices.Contains(labelStatuses, status)
}

// SellerOrderRow is one row of the orders table.
type SellerOrderRow struct {
	OrderNumber    string    `json:"orderNumber"`
	ShipmentID     string    `json:"shipmentId"`
	PlacedAt       time.Time `json:"placedAt"`
	BuyerName      string    `json:"buyerName"`
	ShipTo         string    `json:"shipTo"`
	ItemsSummary   string    `json:"itemsSummary"`
	ItemCount      int       `json:"itemCount"`
	TotalMinor     int64     `json:"totalMinor"`
	Currency       string    `json:"currency"`
	Fulfilment     string    `json:"fulfilment"`
	Status         string    `json:"status"`
	StatusLabel    string    `json:"statusLabel"`
	Carrier        *string   `json:"carrier"`
	TrackingNumber *string   `json:"trackingNumber"`
	NextActions    []string  `json:"nextActions"`
	CanBuyLabel    bool      `json:"canBuyLabel"`
}

// SellerOrderPage is GET /v1/seller/orders.
type SellerOrderPage struct {
	Items      []SellerOrderRow `json:"items"`
	NextCursor *string          `json:"nextCursor"`
	Total      int64            `json:"total"`
	// Sort is the applied sort (`<column>,asc|desc`).
	Sort []string `json:"sort"`
	// Partial is true when the search or sort covered the first TableScanMaxRows rows only.
	Partial bool `json:"partial"`
}

// SellerAddress is a postal address (ship-to, ship-from).
type SellerAddress struct {
	Name       string  `json:"name"`
	Phone      *string `json:"phone"`
	Line1      string  `json:"line1"`
	Line2      *string `json:"line2"`
	City       string  `json:"city"`
	State      string  `json:"state"`
	PostalCode string  `json:"postalCode"`
	Country    string  `json:"country"`
}

// SellerShipmentLine is one line of a shipment.
type SellerShipmentLine struct {
	Title          string  `json:"title"`
	Quantity       int     `json:"quantity"`
	UnitPriceMinor int64   `json:"unitPriceMinor"`
	ItemsMinor     int64   `json:"itemsMinor"`
	VariantATIN    string  `json:"variantAtin"`
	ImageURL       *string `json:"imageUrl"`
}

// TimelineEntry is one status change.
type TimelineEntry struct {
	Status string    `json:"status"`
	At     time.Time `json:"at"`
	Actor  string    `json:"actor"`
}

// SellerShipment is a shipment in the order sheet.
type SellerShipment struct {
	ShipmentID     string               `json:"shipmentId"`
	Label          string               `json:"label"`
	Status         string               `json:"status"`
	StatusLabel    string               `json:"statusLabel"`
	Fulfilment     string               `json:"fulfilment"`
	Perishable     bool                 `json:"perishable"`
	Lines          []SellerShipmentLine `json:"lines"`
	ItemsMinor     int64                `json:"itemsMinor"`
	ShippingMinor  int64                `json:"shippingMinor"`
	TotalMinor     int64                `json:"totalMinor"`
	Carrier        *string              `json:"carrier"`
	TrackingNumber *string              `json:"trackingNumber"`
	NextActions    []string             `json:"nextActions"`
	CanBuyLabel    bool                 `json:"canBuyLabel"`
	// HasLabel: a label was bought for this shipment and can be downloaded again.
	HasLabel bool `json:"hasLabel"`
	// SuggestedParcel is the package size and weight the products were listed with (nil when unknown).
	SuggestedParcel *SuggestedParcel     `json:"suggestedParcel"`
	Timeline        []TimelineEntry      `json:"timeline"`
	LocalDelivery   *SellerLocalDelivery `json:"localDelivery"`
	// Proof is how our driver handed it over (photo, who received it); nil for carrier, pickup and manual deliveries.
	Proof *DeliveryProof `json:"proof"`
}

// SuggestedParcel is the package of a shipment worked out from its products' listed package (inches, pounds).
type SuggestedParcel struct {
	WeightLb float64 `json:"weightLb"`
	LengthIn float64 `json:"lengthIn"`
	WidthIn  float64 `json:"widthIn"`
	HeightIn float64 `json:"heightIn"`
}

// SellerOrderDetail is GET /v1/seller/orders/{orderNumber}.
type SellerOrderDetail struct {
	OrderNumber     string           `json:"orderNumber"`
	Status          string           `json:"status"`
	PlacedAt        time.Time        `json:"placedAt"`
	Currency        string           `json:"currency"`
	BuyerName       string           `json:"buyerName"`
	ShippingAddress *SellerAddress   `json:"shippingAddress"`
	Shipments       []SellerShipment `json:"shipments"`
	TotalMinor      int64            `json:"totalMinor"`
	Earnings        *OrderEarnings   `json:"earnings"`
}

// Shipment finds a shipment by id.
func (o SellerOrderDetail) Shipment(id string) (SellerShipment, bool) {
	for _, s := range o.Shipments {
		if s.ShipmentID == id {
			return s, true
		}
	}
	return SellerShipment{}, false
}

// Parcel is the package to ship.
type Parcel struct {
	WeightLb float64 `json:"weightLb"`
	LengthIn float64 `json:"lengthIn,omitempty"`
	WidthIn  float64 `json:"widthIn,omitempty"`
	HeightIn float64 `json:"heightIn,omitempty"`
}

// RateQuote is one carrier price.
type RateQuote struct {
	Carrier           string     `json:"carrier"`
	Service           string     `json:"service"`
	ServiceName       string     `json:"serviceName"`
	AmountMinor       int64      `json:"amountMinor"`
	Currency          string     `json:"currency"`
	TransitDays       *int       `json:"transitDays"`
	EstimatedDelivery *time.Time `json:"estimatedDelivery"`
}

// RateQuotes is the rate-shopping answer.
type RateQuotes struct {
	Quotes  []RateQuote `json:"quotes"`
	Partial bool        `json:"partial"`
}

// LabelPurchase is the bought label with the updated order.
type LabelPurchase struct {
	Carrier        string            `json:"carrier"`
	Service        string            `json:"service"`
	TrackingNumber string            `json:"trackingNumber"`
	CostMinor      int64             `json:"costMinor"`
	Currency       string            `json:"currency"`
	DocumentURL    *string           `json:"documentUrl"`
	Order          SellerOrderDetail `json:"order"`
}

// ---------------------------------------------------------------- listings & inventory

var listingLabels = map[string]string{
	"IN_REVIEW": "In review", "PENDING_CATEGORY_APPROVAL": "Waiting for category approval", "LIVE": "Live", "REJECTED": "Rejected", "PAUSED": "Paused",
	"TAKEN_DOWN": "Taken down",
}

// ListingStatusLabel names a listing status.
func ListingStatusLabel(s string) string {
	if l, ok := listingLabels[s]; ok {
		return l
	}
	return s
}

// IsListingStatus reports whether s is a catalog product status.
func IsListingStatus(s string) bool { _, ok := listingLabels[s]; return ok }

// ListingRow is one row of the listings table.
type ListingRow struct {
	ProductID      string    `json:"productId"`
	ATIN           *string   `json:"atin"`
	Title          string    `json:"title"`
	SKU            string    `json:"sku"`
	VariantCount   int       `json:"variantCount"`
	PriceMinor     int64     `json:"priceMinor"`
	Currency       string    `json:"currency"`
	Stock          int       `json:"stock"`
	Fulfilment     string    `json:"fulfilment"`
	Status         string    `json:"status"`
	StatusLabel    string    `json:"statusLabel"`
	ModerationNote *string   `json:"moderationNote"`
	ImageURL       *string   `json:"imageUrl"`
	UpdatedAt      time.Time `json:"updatedAt"`
}

// ListingPage is GET /v1/seller/listings.
type ListingPage struct {
	Items []ListingRow `json:"items"`
	Total int64        `json:"total"`
	Page  int          `json:"page"`
	Size  int          `json:"size"`
	// Sort is the applied sort (`<column>,asc|desc`).
	Sort []string `json:"sort"`
	// Partial is true when the search or sort covered the first TableScanMaxRows rows only.
	Partial bool `json:"partial"`
}

// CategoryNode is a node of the nested category tree.
type CategoryNode struct {
	ID          string         `json:"id"`
	Name        string         `json:"name"`
	Level       string         `json:"level"`
	ProductType *string        `json:"productType"`
	Path        string         `json:"path"`
	Children    []CategoryNode `json:"children"`
}

// AttributeDef is a product-type attribute: its kind (TEXT, MULTILINE, ENUM, NUMBER), units for numbers, the form
// section it belongs to (DETAILS, FOOD, REGULATED) and a help line.
type AttributeDef struct {
	Key       string   `json:"key"`
	Label     string   `json:"label"`
	Options   []string `json:"options"`
	Required  bool     `json:"required"`
	Multiline bool     `json:"multiline"`
	Kind      string   `json:"kind"`
	Units     []string `json:"units"`
	Section   string   `json:"section"`
	Help      *string  `json:"help"`
}

// ProductTypeTraits decide the one-step form's conditional sections.
type ProductTypeTraits struct {
	Food       bool `json:"food"`
	Perishable bool `json:"perishable"`
	Regulated  bool `json:"regulated"`
	Preorder   bool `json:"preorder"`
}

// VariationDimension is one variation axis.
type VariationDimension struct {
	Name    string   `json:"name"`
	Options []string `json:"options"`
}

// VariationTheme is a set of axes (Size × Color).
type VariationTheme struct {
	Name       string               `json:"name"`
	Dimensions []VariationDimension `json:"dimensions"`
}

// ProductTypeSchema drives the attribute fields, variant axes and conditional sections of the product form.
type ProductTypeSchema struct {
	Code           string            `json:"code"`
	Label          string            `json:"label"`
	IdentifierRule string            `json:"identifierRule"`
	Attributes     []AttributeDef    `json:"attributes"`
	Themes         []VariationTheme  `json:"themes"`
	Traits         ProductTypeTraits `json:"traits"`
}

// GtinCheck is the identifier check.
type GtinCheck struct {
	Digits       string  `json:"digits"`
	Valid        bool    `json:"valid"`
	Suggestion   *string `json:"suggestion"`
	ExistingATIN *string `json:"existingAtin"`
	Message      string  `json:"message"`
}

// CategoryRequestInput asks for a new category.
type CategoryRequestInput struct {
	ParentPath     string `json:"parentPath"`
	Name           string `json:"name"`
	ExampleProduct string `json:"exampleProduct"`
	DetailsNeeded  string `json:"detailsNeeded"`
}

// CategoryRequest is a requested category.
type CategoryRequest struct {
	ID           string    `json:"id"`
	Reference    string    `json:"reference"`
	Path         string    `json:"path"`
	Status       string    `json:"status"`
	DecisionNote *string   `json:"decisionNote"`
	CreatedAt    time.Time `json:"createdAt"`
}

// ProductImageInput is an uploaded image of the draft.
type ProductImageInput struct {
	URL         string `json:"url"`
	Width       int    `json:"width,omitempty"`
	Height      int    `json:"height,omitempty"`
	Bytes       int64  `json:"bytes,omitempty"`
	ContentType string `json:"contentType"`
}

// VariantInput is one variant row (the variant matrix, or the single row of a product without variations).
type VariantInput struct {
	Options        map[string]string   `json:"options"`
	SellerSKU      string              `json:"sellerSku"`
	GTIN           string              `json:"gtin,omitempty"`
	GTINExempt     bool                `json:"gtinExempt,omitempty"`
	PriceMinor     int64               `json:"priceMinor"`
	CompareAtMinor *int64              `json:"compareAtMinor,omitempty"`
	Quantity       int                 `json:"quantity"`
	Images         []ProductImageInput `json:"images,omitempty"`
}

// Item conditions and fulfilment modes of a listing.
var (
	ItemConditions     = []string{"NEW", "RENEWED", "USED_LIKE_NEW", "USED_VERY_GOOD", "USED_GOOD", "USED_ACCEPTABLE"}
	ListingFulfilments = []string{"SELLER", "AFROTRANSACT"}
)

// PackageDimensions is the packed size (inches) and weight (pounds).
type PackageDimensions struct {
	LengthIn float64 `json:"lengthIn"`
	WidthIn  float64 `json:"widthIn"`
	HeightIn float64 `json:"heightIn"`
	WeightLb float64 `json:"weightLb"`
}

// ListingDetailsInput is the form's description, condition, keywords, fulfilment, package, handling and ship-from.
type ListingDetailsInput struct {
	Description  string             `json:"description,omitempty"`
	Condition    string             `json:"condition,omitempty"`
	Keywords     []string           `json:"keywords,omitempty"`
	Fulfilment   string             `json:"fulfilment,omitempty"`
	Packaging    *PackageDimensions `json:"packaging,omitempty"`
	HandlingDays int                `json:"handlingDays,omitempty"`
	ShipFromID   *string            `json:"shipFromId,omitempty"`
}

// ProductDraft is the one-step product form's submission (POST /v1/seller/products, PUT /v1/seller/products/{id}).
type ProductDraft struct {
	CategoryID        string               `json:"categoryId,omitempty"`
	CategoryRequestID string               `json:"categoryRequestId,omitempty"`
	Title             string               `json:"title"`
	Attributes        map[string]string    `json:"attributes"`
	Images            []ProductImageInput  `json:"images"`
	VariationTheme    string               `json:"variationTheme"`
	Variants          []VariantInput       `json:"variants"`
	PreorderEligible  *bool                `json:"preorderEligible,omitempty"`
	Listing           *ListingDetailsInput `json:"listing,omitempty"`
	DraftID           string               `json:"draftId,omitempty"`
	// Version is the product version an edit is based on (PUT only).
	Version *int64 `json:"version,omitempty"`
}

// Validate applies the form's rules (the same ones catalog enforces) to a new product.
func (d ProductDraft) Validate() map[string]string {
	errs := d.validateContent()
	if (d.CategoryID == "") == (d.CategoryRequestID == "") {
		errs["categoryId"] = "Choose a category, or request a new one."
	}
	return errs
}

// ValidateEdit applies the form's rules to an edit (the category may be omitted: it is kept).
func (d ProductDraft) ValidateEdit() map[string]string {
	errs := d.validateContent()
	if d.CategoryRequestID != "" {
		errs["categoryRequestId"] = "A product's category request can't change."
	}
	return errs
}

func (d ProductDraft) validateContent() map[string]string {
	errs := map[string]string{}
	if n := len([]rune(strings.TrimSpace(d.Title))); n < 10 || n > 200 {
		errs["title"] = "At least 10 characters"
	}
	if len(d.Images) == 0 {
		errs["images"] = "Add at least one image."
	} else if len(d.Images) > 10 {
		errs["images"] = "Up to 10 images."
	}
	if strings.TrimSpace(d.VariationTheme) == "" && len(d.Variants) > 1 {
		errs["variationTheme"] = "Choose how this product varies to list several variants."
	}
	if len(d.Variants) == 0 {
		errs["variants"] = "Add the price and quantity."
	} else if len(d.Variants) > 200 {
		errs["variants"] = "Up to 200 variants."
	}
	skus := map[string]bool{}
	for _, v := range d.Variants {
		sku := strings.TrimSpace(v.SellerSKU)
		switch {
		case sku == "":
			errs["variants"] = "Every variation needs a SKU."
		case skus[sku]:
			errs["variants"] = "SKUs must be unique."
		case v.PriceMinor <= 0:
			errs["variants"] = "Every variation needs a price."
		case v.Quantity < 0:
			errs["variants"] = "Quantities can't be negative."
		case v.Quantity > 100000:
			errs["variants"] = "Quantities go up to 100,000."
		case v.CompareAtMinor != nil && *v.CompareAtMinor <= v.PriceMinor:
			errs["variants"] = "A compare-at price must be higher than the price."
		case !v.GTINExempt && v.GTIN == "":
			errs["variants"] = "Enter a barcode for every variation, or say it has none."
		}
		skus[sku] = true
	}
	if l := d.Listing; l != nil {
		if len([]rune(l.Description)) > 5000 {
			errs["listing.description"] = "At most 5,000 characters"
		}
		if l.Condition != "" && !slices.Contains(ItemConditions, l.Condition) {
			errs["listing.condition"] = "Unknown condition"
		}
		if l.Fulfilment != "" && !slices.Contains(ListingFulfilments, l.Fulfilment) {
			errs["listing.fulfilment"] = "Unknown fulfilment"
		}
		if len(l.Keywords) > 20 {
			errs["listing.keywords"] = "Up to 20 keywords"
		}
		if l.HandlingDays < 0 || l.HandlingDays > 30 {
			errs["listing.handlingDays"] = "Between 1 and 30 days"
		}
		if p := l.Packaging; p != nil && (p.LengthIn <= 0 || p.WidthIn <= 0 || p.HeightIn <= 0 || p.WeightLb <= 0) {
			errs["listing.packaging"] = "Give the package length, width, height and weight"
		}
		if l.Fulfilment == "AFROTRANSACT" && l.Packaging == nil {
			errs["listing.packaging"] = "Package size and weight are required for Fulfilled by AfroTransact"
		}
	}
	return errs
}

// UploadTicket is a direct-upload instruction.
type UploadTicket struct {
	AssetID   string            `json:"assetId"`
	Method    string            `json:"method"`
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	PublicURL *string           `json:"publicUrl"`
	ExpiresAt *time.Time        `json:"expiresAt"`
}

// Asset is a finalised upload.
type Asset struct {
	ID          string `json:"id"`
	URL         string `json:"url"`
	Width       *int   `json:"width"`
	Height      *int   `json:"height"`
	Bytes       *int64 `json:"bytes"`
	ContentType string `json:"contentType"`
	Status      string `json:"status"`
}

// InventoryLowStockThreshold flags positions with this many or fewer units available (inventory's rule).
const InventoryLowStockThreshold = 10

// StockPosition is stock of one SKU at one node.
type StockPosition struct {
	SKU       string `json:"sku"`
	NodeID    string `json:"nodeId"`
	OnHand    int    `json:"onHand"`
	Reserved  int    `json:"reserved"`
	Available int    `json:"available"`
	Inbound   int    `json:"inbound"`
	LowStock  bool   `json:"lowStock"`
}

// InventoryTotals sums the positions.
type InventoryTotals struct {
	Available    int `json:"available"`
	Reserved     int `json:"reserved"`
	Inbound      int `json:"inbound"`
	LowStockSKUs int `json:"lowStockSkus"`
}

// InventoryView is GET /v1/seller/inventory.
type InventoryView struct {
	Positions []StockPosition `json:"positions"`
	Totals    InventoryTotals `json:"totals"`
}

// StockAdjustment is the adjusted quantity.
type StockAdjustment struct {
	SKU      string `json:"sku"`
	Quantity int    `json:"quantity"`
}

// StockReasons are the accepted adjustment reasons.
var StockReasons = []string{"STOCK_TAKE", "DAMAGED", "FOUND", "RETURNED", "OTHER"}

// ---------------------------------------------------------------- money

// PayoutHold is net on settlement hold for one order.
type PayoutHold struct {
	OrderID     string  `json:"orderId"`
	AmountMinor int64   `json:"amountMinor"`
	ReleaseAt   *string `json:"releaseAt"`
}

// PayoutBalance is the balance card.
type PayoutBalance struct {
	Currency                  string  `json:"currency"`
	AvailableMinor            int64   `json:"availableMinor"`
	OnHoldMinor               int64   `json:"onHoldMinor"`
	RequestedOrInTransitMinor int64   `json:"requestedOrInTransitMinor"`
	PaidMinor                 int64   `json:"paidMinor"`
	ProviderFeesMinor         int64   `json:"providerFeesMinor"`
	PlanFeesMinor             int64   `json:"planFeesMinor"`
	MinimumPayoutMinor        int64   `json:"minimumPayoutMinor"`
	CanRequestToday           bool    `json:"canRequestToday"`
	PayoutsPaused             bool    `json:"payoutsPaused"`
	PayoutsPausedReason       *string `json:"payoutsPausedReason"`
	PayoutsPausedMessage      *string `json:"payoutsPausedMessage"`
	// PayoutAccountMissing: payments has no Stripe Connect account for the organisation, so earnings stay on hold.
	PayoutAccountMissing bool         `json:"payoutAccountMissing"`
	Holds                []PayoutHold `json:"holds"`
}

var pausedMessages = map[string]string{
	"AUTO_PAST_DUE":    "Stripe needs overdue information. Payouts resume automatically once Account health is resolved.",
	"MANUAL":           "AfroTransact paused payouts while we review your account. We’ll email you when they resume.",
	"DEAUTHORIZED":     "Your Stripe account was disconnected. Reconnect it in Account health to receive payouts.",
	"CHARGES_DISABLED": "Stripe disabled charges on your account. Resolve Account health to resume payouts.",
}

// PayoutsPausedMessage explains a payout hold (copy shown on the Payouts banner).
func PayoutsPausedMessage(reason string) string {
	if m, ok := pausedMessages[reason]; ok {
		return m
	}
	return "Payouts are paused. Your balance keeps accruing and is paid out once the hold is lifted."
}

// IsPausedReason reports whether reason is a payments pause reason.
func IsPausedReason(reason string) bool { _, ok := pausedMessages[reason]; return ok }

// Payout is one payout request.
type Payout struct {
	ID             string  `json:"id"`
	AmountMinor    int64   `json:"amountMinor"`
	Currency       string  `json:"currency"`
	Status         string  `json:"status"`
	RequestedAt    string  `json:"requestedAt"`
	HoldReason     *string `json:"holdReason"`
	FailureMessage *string `json:"failureMessage"`
}

// PayoutsView is GET /v1/seller/payouts.
type PayoutsView struct {
	Balance          PayoutBalance `json:"balance"`
	Payouts          []Payout      `json:"payouts"`
	HistoryAvailable bool          `json:"historyAvailable"`
	// PlanFeeDeductions are plan invoices paid from sales (S298), newest first.
	PlanFeeDeductions []PlanFeeDeduction `json:"planFeeDeductions"`
}

// PlanFeeTransfer is part of a plan fee deducted from (or, negative, paid back with) one order's settlement.
type PlanFeeTransfer struct {
	OrderID     string    `json:"orderId"`
	AmountMinor int64     `json:"amountMinor"`
	At          time.Time `json:"at"`
	Label       string    `json:"label"`
}

// PlanFeeDeduction is a plan invoice paid from sales: "Plan fee (invoice …)" (S298).
type PlanFeeDeduction struct {
	InvoiceID        string            `json:"invoiceId"`
	InvoiceNumber    *string           `json:"invoiceNumber"`
	Label            string            `json:"label"`
	Status           string            `json:"status"`
	RequestedMinor   int64             `json:"requestedMinor"`
	WithheldMinor    int64             `json:"withheldMinor"`
	ReleasedMinor    int64             `json:"releasedMinor"`
	OutstandingMinor int64             `json:"outstandingMinor"`
	CreatedAt        time.Time         `json:"createdAt"`
	Transfers        []PlanFeeTransfer `json:"transfers"`
}

// Plan fee collection modes (S298).
const (
	PlanFeeEarningsFirst = "EARNINGS_FIRST"
	PlanFeeCard          = "CARD"
)

// PlanFeeCollectionView is how the organisation pays its plan (S298).
type PlanFeeCollectionView struct {
	Available            bool       `json:"available"`
	Mode                 string     `json:"mode"`
	Effective            string     `json:"effective"`
	Source               string     `json:"source"`
	PlatformDefault      string     `json:"platformDefault"`
	AppliesFromInvoiceAt *time.Time `json:"appliesFromInvoiceAt"`
	AppliesFromLabel     string     `json:"appliesFromLabel"`
	InProgressInvoiceID  *string    `json:"inProgressInvoiceId"`
	ChangedAt            *time.Time `json:"changedAt"`
	CanChange            bool       `json:"canChange"`
}

// Cadences of a subscription.
var Cadences = []string{"MONTHLY", "QUARTERLY", "ANNUAL"}

// PlanPrice is a plan's price for one cadence.
type PlanPrice struct {
	Cadence       string `json:"cadence"`
	Months        int    `json:"months"`
	TotalMinor    int64  `json:"totalMinor"`
	PerMonthMinor int64  `json:"perMonthMinor"`
	SavingMinor   int64  `json:"savingMinor"`
	PerMonthLabel string `json:"perMonthLabel"`
	BilledLabel   string `json:"billedLabel"`
	SavingLabel   string `json:"savingLabel"`
}

// Plan is a plan card.
type Plan struct {
	Code         string      `json:"code"`
	Name         string      `json:"name"`
	Tagline      string      `json:"tagline"`
	MonthlyMinor int64       `json:"monthlyMinor"`
	TrialDays    int         `json:"trialDays"`
	Features     []string    `json:"features"`
	Prices       []PlanPrice `json:"prices"`
	Current      bool        `json:"current"`
	// Entitlements is what the plan allows: the live-listing cap, the AI listing writer and Lola's tier.
	Entitlements *PlanEntitlementsView `json:"entitlements"`
	// ChargeTerms is what the plan charges, in words: "First 3 months free, then $39/mo", or commission only.
	ChargeTerms string `json:"chargeTerms"`
}

// BillingPaymentMethod is a card or bank account the plan is charged to.
type BillingPaymentMethod struct {
	ID              string  `json:"id"`
	Kind            string  `json:"kind"`
	Brand           *string `json:"brand"`
	Last4           string  `json:"last4"`
	ExpMonth        *int    `json:"expMonth"`
	ExpYear         *int    `json:"expYear"`
	BankName        *string `json:"bankName"`
	AccountType     *string `json:"accountType"`
	Verified        bool    `json:"verified"`
	ReceivesPayouts bool    `json:"receivesPayouts"`
	IsDefault       bool    `json:"isDefault"`
	Label           string  `json:"label"`
}

// Invoice is a subscription invoice.
type Invoice struct {
	ID              string     `json:"id"`
	Number          string     `json:"number"`
	PeriodStart     *time.Time `json:"periodStart"`
	PeriodEnd       *time.Time `json:"periodEnd"`
	SubtotalMinor   int64      `json:"subtotalMinor"`
	DiscountMinor   int64      `json:"discountMinor"`
	AmountDueMinor  int64      `json:"amountDueMinor"`
	AmountPaidMinor int64      `json:"amountPaidMinor"`
	Currency        string     `json:"currency"`
	Status          string     `json:"status"`
	CreatedAt       time.Time  `json:"createdAt"`
	// PaidAt is when it was settled; NextAttemptAt and LastError describe an unpaid one (a declined card is retried).
	PaidAt        *time.Time    `json:"paidAt"`
	NextAttemptAt *time.Time    `json:"nextAttemptAt"`
	LastError     *string       `json:"lastError"`
	Lines         []InvoiceLine `json:"lines"`
	// What settled it, in the order tried: platform credit and coupons first, then the seller's sales (only when they
	// chose that), then the card or bank account; a waiver is staff forgiving the rest.
	PaidFromCreditMinor int64 `json:"paidFromCreditMinor"`
	PaidFromSalesMinor  int64 `json:"paidFromSalesMinor"`
	PaidByCardMinor     int64 `json:"paidByCardMinor"`
	WaivedMinor         int64 `json:"waivedMinor"`
	// CanRetry is true for an invoice that failed every attempt: the seller can fix the payment method and retry.
	CanRetry bool `json:"canRetry"`
}

// InvoiceLine is one line of an invoice: the plan, usage, a coupon or what a charge condition waived.
type InvoiceLine struct {
	Kind        string `json:"kind"`
	Description string `json:"description"`
	AmountMinor int64  `json:"amountMinor"`
}

// PendingPlanChange is a downgrade or cadence change applied at renewal.
type PendingPlanChange struct {
	PlanCode    string    `json:"planCode"`
	PlanName    string    `json:"planName"`
	Cadence     string    `json:"cadence"`
	EffectiveAt time.Time `json:"effectiveAt"`
}

// Subscription is the current plan.
type Subscription struct {
	PlanCode          string             `json:"planCode"`
	PlanName          string             `json:"planName"`
	Cadence           string             `json:"cadence"`
	Status            string             `json:"status"`
	PriceLabel        string             `json:"priceLabel"`
	BilledLabel       string             `json:"billedLabel"`
	TrialEndsAt       *time.Time         `json:"trialEndsAt"`
	CurrentPeriodEnd  *time.Time         `json:"currentPeriodEnd"`
	NextChargeAt      *time.Time         `json:"nextChargeAt"`
	NextChargeMinor   *int64             `json:"nextChargeMinor"`
	NextChargeLabel   string             `json:"nextChargeLabel"`
	CancelAtPeriodEnd bool               `json:"cancelAtPeriodEnd"`
	PendingChange     *PendingPlanChange `json:"pendingChange"`
	Dunning           *string            `json:"dunning"`
	// PlanVersion is the plan version (prices) the organisation pays; it changes only by a plan change or a staff
	// migration (S292).
	PlanVersion int `json:"planVersion"`
	// CreditBalanceMinor is the platform credit the next invoices use first (rewards, staff credits).
	CreditBalanceMinor int64 `json:"creditBalanceMinor"`
	// TrialSource is PLAN, CAMPAIGN, OVERRIDE or NONE once the subscription started.
	TrialSource *string `json:"trialSource"`
	// ChargeGate is a condition staff put on the plan charges, with the seller's progress; nil when charges apply.
	ChargeGate *ChargeGateView `json:"chargeGate"`
	// Entitlements is what the plan allows.
	Entitlements *PlanEntitlementsView `json:"entitlements"`
}

// ChargeGateView explains a condition on the plan charges to the seller: "Charging starts after $1,000 in sales — you're
// at $420".
type ChargeGateView struct {
	Mode      string     `json:"mode"`
	Metric    *string    `json:"metric"`
	Threshold *int64     `json:"threshold"`
	Progress  int64      `json:"progress"`
	Until     *time.Time `json:"until"`
	MetAt     *time.Time `json:"metAt"`
	Label     string     `json:"label"`
}

// PlanEntitlementsView is what a plan lets an organisation do. A capability that is off is not offered to the plan's
// members: the BFF refuses it (403 PLAN_FEATURE_REQUIRED) and Seller Center does not show it.
type PlanEntitlementsView struct {
	Listings          *int64 `json:"listings"`
	ListingsUnlimited bool   `json:"listingsUnlimited"`
	AIListingWriter   bool   `json:"aiListingWriter"`
	LolaTier          string `json:"lolaTier"`
	BulkUpload        bool   `json:"bulkUpload"`
	ImageLibrary      bool   `json:"imageLibrary"`
	Coupons           bool   `json:"coupons"`
}

// Plan capabilities: the keys of PlanEntitlementsView and of the session's capabilities list.
const (
	CapBulkUpload   = "bulkUpload"
	CapImageLibrary = "imageLibrary"
	CapCoupons      = "coupons"
	CapAIWriter     = "aiListingWriter"
	CapLola         = "lola"
)

// Allows reports whether the plan includes a capability.
func (e *PlanEntitlementsView) Allows(capability string) bool {
	if e == nil {
		return false
	}
	switch capability {
	case CapBulkUpload:
		return e.BulkUpload
	case CapImageLibrary:
		return e.ImageLibrary
	case CapCoupons:
		return e.Coupons
	case CapAIWriter:
		return e.AIListingWriter
	case CapLola:
		return !strings.EqualFold(e.LolaTier, "NONE")
	}
	return false
}

// Capabilities lists the keys the plan includes (what Seller Center may show).
func (e *PlanEntitlementsView) Capabilities() []string {
	out := []string{}
	for _, c := range []string{CapBulkUpload, CapImageLibrary, CapCoupons, CapAIWriter, CapLola} {
		if e.Allows(c) {
			out = append(out, c)
		}
	}
	return out
}

// BillingView is GET /v1/seller/billing.
type BillingView struct {
	Subscription   Subscription           `json:"subscription"`
	Plans          []Plan                 `json:"plans"`
	PaymentMethods []BillingPaymentMethod `json:"paymentMethods"`
	Invoices       []Invoice              `json:"invoices"`
	CanManage      bool                   `json:"canManage"`
	// PlanFeeCollection is how the plan is paid (S298); nil when it can't be read.
	PlanFeeCollection *PlanFeeCollectionView `json:"planFeeCollection"`
}

// RewardProgress is a reward the organisation can still earn: "$640 of $1,000 in sales → 1 month free".
type RewardProgress struct {
	RuleID         string     `json:"ruleId"`
	Name           string     `json:"name"`
	Description    *string    `json:"description"`
	Metric         string     `json:"metric"`
	Value          int64      `json:"value"`
	Threshold      int64      `json:"threshold"`
	Percent        int        `json:"percent"`
	Qualifies      bool       `json:"qualifies"`
	Label          string     `json:"label"`
	RewardLabel    string     `json:"rewardLabel"`
	ConditionLabel string     `json:"conditionLabel"`
	EndsAt         *time.Time `json:"endsAt"`
	// Comparison is AT_LEAST (progress towards the threshold) or LESS_THAN (low-sales relief, S299: no progress bar,
	// decided once at CheckAt).
	Comparison     string     `json:"comparison"`
	WindowAnchor   string     `json:"windowAnchor"`
	WindowDays     *int       `json:"windowDays"`
	Pending        bool       `json:"pending"`
	CheckAt        *time.Time `json:"checkAt"`
	WindowClosesAt *time.Time `json:"windowClosesAt"`
}

// EarnedReward is a reward or credit the organisation received.
type EarnedReward struct {
	ID          string     `json:"id"`
	Source      string     `json:"source"`
	Type        string     `json:"type"`
	Summary     string     `json:"summary"`
	Reason      *string    `json:"reason"`
	Status      string     `json:"status"`
	CreditMinor *int64     `json:"creditMinor"`
	FreeDays    *int       `json:"freeDays"`
	CreatedAt   time.Time  `json:"createdAt"`
	AppliedAt   *time.Time `json:"appliedAt"`
	// Comparison is the reward rule's comparison: LESS_THAN is low-sales relief (S299).
	Comparison *string `json:"comparison"`
	// NextChargeAt is the new trial end for free days: when Stripe charges next.
	NextChargeAt *time.Time `json:"nextChargeAt"`
}

// SellerRewardsView is GET /v1/seller/billing/rewards.
type SellerRewardsView struct {
	Available []RewardProgress `json:"available"`
	Earned    []EarnedReward   `json:"earned"`
}

// PlanChangePreview is what a plan or cadence change would do (nothing changes).
type PlanChangePreview struct {
	Change            string     `json:"change"`
	PlanCode          string     `json:"planCode"`
	PlanName          string     `json:"planName"`
	Cadence           string     `json:"cadence"`
	EffectiveAt       *time.Time `json:"effectiveAt"`
	AmountDueNowMinor int64      `json:"amountDueNowMinor"`
	NextChargeMinor   int64      `json:"nextChargeMinor"`
	NextChargeAt      *time.Time `json:"nextChargeAt"`
	Trialing          bool       `json:"trialing"`
}

// BillingSetupIntent starts adding a card or bank account (Payment Element); LocalTest: no Stripe key locally.
type BillingSetupIntent struct {
	SetupIntentID  string `json:"setupIntentId"`
	ClientSecret   string `json:"clientSecret"`
	PublishableKey string `json:"publishableKey"`
	LocalTest      bool   `json:"localTest"`
}

// AddBillingPaymentMethod registers a SetupIntent's confirmed payment method.
type AddBillingPaymentMethod struct {
	StripePaymentMethodID string `json:"stripePaymentMethodId"`
	Kind                  string `json:"kind"`
	Brand                 string `json:"brand"`
	Last4                 string `json:"last4"`
	ExpMonth              int    `json:"expMonth"`
	ExpYear               int    `json:"expYear"`
	BankName              string `json:"bankName"`
	AccountType           string `json:"accountType"`
	MakeDefault           bool   `json:"makeDefault"`
}

// ChangePlanResult is the plan change outcome.
type ChangePlanResult struct {
	Applied string      `json:"applied"`
	Billing BillingView `json:"billing"`
}

// ---------------------------------------------------------------- team

// TeamMember is one member of the organisation.
// Status is ACTIVE, DISABLED or INVITED; INVITED rows are pending invitations (InvitationID set, UserID
// "invite:<id>").
type TeamMember struct {
	UserID       string     `json:"userId"`
	Name         string     `json:"name"`
	Email        *string    `json:"email"`
	RoleKey      string     `json:"roleKey"`
	RoleLabel    string     `json:"roleLabel"`
	Status       string     `json:"status"`
	IsYou        bool       `json:"isYou"`
	InvitationID *string    `json:"invitationId,omitempty"`
	JoinedAt     *time.Time `json:"joinedAt,omitempty"`
	ExpiresAt    *time.Time `json:"expiresAt,omitempty"`
}

// InvitationPreview is what an invitation link is for (accept page, before signing in).
type InvitationPreview struct {
	OrgName   string    `json:"orgName"`
	OrgKind   string    `json:"orgKind"`
	Email     string    `json:"email"`
	RoleKey   string    `json:"roleKey"`
	RoleLabel string    `json:"roleLabel"`
	Status    string    `json:"status"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// AcceptedInvitation is the outcome of accepting an invitation.
type AcceptedInvitation struct {
	OrgID         string `json:"orgId"`
	OrgName       string `json:"orgName"`
	OrgKind       string `json:"orgKind"`
	RoleKey       string `json:"roleKey"`
	RoleLabel     string `json:"roleLabel"`
	AlreadyMember bool   `json:"alreadyMember"`
}

// TeamView is GET /v1/seller/team.
type TeamView struct {
	Members     []TeamMember `json:"members"`
	Roles       []RoleCard   `json:"roles"`
	YourRoleKey string       `json:"yourRoleKey"`
	CanManage   bool         `json:"canManage"`
}

// IsRoleOf reports whether key is a role of the organisation kind.
func IsRoleOf(kind, key string) bool {
	for _, r := range RolesFor(kind) {
		if r.Key == key {
			return true
		}
	}
	return false
}

var (
	stateRe = regexp.MustCompile(`^[A-Za-z]{2}$`)
	zipRe   = regexp.MustCompile(`^\d{5}(-\d{4})?$`)
)

// ShipFromAddress is a saved ship-from address of the seller organisation (label form, rates).
type ShipFromAddress struct {
	ID         string `json:"id"`
	Label      string `json:"label"`
	Name       string `json:"name"`
	Company    string `json:"company"`
	Phone      string `json:"phone"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
	IsDefault  bool   `json:"isDefault"`
	Version    int64  `json:"version"`
	// Position is where geo-service located the address (nil when geocoding failed or geo was down); S255.
	Position           *GeoPosition       `json:"position"`
	AfrotransactPickup AfrotransactPickup `json:"afrotransactPickup"`
	// CustomerPickup is whether customers can collect their orders here (checkout pickup).
	CustomerPickup CustomerPickup `json:"customerPickup"`
}

// GeoPosition is a geocoded position: ADDRESS (street level), ZIP (centroid) or POINT.
type GeoPosition struct {
	Lat        float64    `json:"lat"`
	Lng        float64    `json:"lng"`
	Precision  string     `json:"precision"`
	GeocodedAt *time.Time `json:"geocodedAt"`
}

// AfrotransactPickup is "AfroTransact picks up from my store" for one address (S255).
type AfrotransactPickup struct {
	Enabled   bool           `json:"enabled"`
	Hours     []PickupWindow `json:"hours"`
	ZoneName  *string        `json:"zoneName"`
	EnabledAt *time.Time     `json:"enabledAt"`
}

// CustomerPickup is "customers can pick up here": the carrier-gateway pickup location (kind SELLER, same id as the
// address) checkout offers for the seller's packages.
type CustomerPickup struct {
	Enabled    bool           `json:"enabled"`
	Hours      []PickupWindow `json:"hours"`
	ReadyHours int            `json:"readyHours"`
}

// CustomerPickupRequest is PUT /v1/seller/ship-from-addresses/{id}/customer-pickup.
type CustomerPickupRequest struct {
	Enabled    bool           `json:"enabled"`
	Hours      []PickupWindow `json:"hours"`
	ReadyHours int            `json:"readyHours"`
}

// Validate applies the same hours rules as AfroTransact pickup (1–14 windows to enable, no overlap) and a ready time
// of 0–168 hours.
func (r CustomerPickupRequest) Validate() map[string]string {
	errs := AfrotransactPickupRequest{Enabled: r.Enabled, Hours: r.Hours}.Validate()
	if r.ReadyHours < 0 || r.ReadyHours > 168 {
		errs["readyHours"] = "Between 0 and 168 hours"
	}
	return errs
}

// AfrotransactPickupRequest is PUT /v1/seller/ship-from-addresses/{id}/afrotransact-pickup.
type AfrotransactPickupRequest struct {
	Enabled bool           `json:"enabled"`
	Hours   []PickupWindow `json:"hours"`
	Version *int64         `json:"version"`
}

// PickupDays are the weekdays of pickup windows, Monday first.
var PickupDays = []string{"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"}

var hhmm = regexp.MustCompile(`^([01][0-9]|2[0-3]):[0-5][0-9]$`)

// Validate applies the seller service's pickup-hours rules: 1–14 windows to enable, HH:MM, opens before closes, no
// overlap on the same day. Errors are keyed hours / hours.<i>.<field>.
func (r AfrotransactPickupRequest) Validate() map[string]string {
	errs := map[string]string{}
	if r.Enabled && len(r.Hours) == 0 {
		errs["hours"] = "Add at least one pickup window"
	}
	if len(r.Hours) > 14 {
		errs["hours"] = "At most 14 pickup windows"
	}
	type span struct {
		i          int
		open, shut string
	}
	byDay := map[string][]span{}
	for i, h := range r.Hours {
		k := fmt.Sprintf("hours.%d", i)
		day := strings.ToUpper(strings.TrimSpace(h.Day))
		if !slices.Contains(PickupDays, day) {
			errs[k+".day"] = "Choose a day"
		}
		switch {
		case !hhmm.MatchString(h.Opens):
			errs[k+".opens"] = "Use HH:MM (24-hour)"
		case !hhmm.MatchString(h.Closes):
			errs[k+".closes"] = "Use HH:MM (24-hour)"
		case h.Opens >= h.Closes:
			errs[k+".closes"] = "Closes after it opens"
		default:
			for _, o := range byDay[day] {
				if h.Opens < o.shut && o.open < h.Closes {
					errs[k+".opens"] = "Overlaps another window on " + strings.ToLower(day)
				}
			}
			byDay[day] = append(byDay[day], span{i, h.Opens, h.Closes})
		}
	}
	return errs
}

// SellerOffer is one offer of the organisation with its fulfilment choice (S255).
type SellerOffer struct {
	OfferID            string  `json:"offerId"`
	ATIN               string  `json:"atin"`
	ProductID          string  `json:"productId"`
	SellerSKU          string  `json:"sellerSku"`
	Condition          string  `json:"condition"`
	PriceMinor         int64   `json:"priceMinor"`
	Currency           string  `json:"currency"`
	Fulfilment         string  `json:"fulfilment"`
	Quantity           int     `json:"quantity"`
	AvailableQty       int     `json:"availableQty"`
	ShipFromLocationID *string `json:"shipFromLocationId"`
	ShipsFrom          *string `json:"shipsFrom"`
	Status             string  `json:"status"`
	Version            int64   `json:"version"`
}

// SellerOfferList is GET /v1/seller/offers.
type SellerOfferList struct {
	Items []SellerOffer `json:"items"`
}

// OfferFulfilmentRequest is PUT /v1/seller/offers/{id}/fulfilment.
type OfferFulfilmentRequest struct {
	Fulfilment         string  `json:"fulfilment"`
	ShipFromLocationID *string `json:"shipFromLocationId"`
}

// SellerLocalDelivery is AfroTransact Delivery from the seller's store on a shipment (S255).
type SellerLocalDelivery struct {
	PickupLocationID    *string  `json:"pickupLocationId"`
	RoadMiles           *float64 `json:"roadMiles"`
	DurationMinutes     *float64 `json:"durationMinutes"`
	CustomerChargeMinor int64    `json:"customerChargeMinor"`
	SellerFeeMinor      int64    `json:"sellerFeeMinor"`
}

// OrderEarnings is the organisation's row of the order's payment split.
type OrderEarnings struct {
	ItemsMinor        int64 `json:"itemsMinor"`
	ShippingMinor     int64 `json:"shippingMinor"`
	CommissionMinor   int64 `json:"commissionMinor"`
	FbaFeesMinor      int64 `json:"fbaFeesMinor"`
	DeliveryFeesMinor int64 `json:"deliveryFeesMinor"`
	StripeFeeMinor    int64 `json:"stripeFeeMinor"`
	NetMinor          int64 `json:"netMinor"`
}

// ShipFromInput creates or changes a ship-from address.
type ShipFromInput struct {
	Label       string `json:"label"`
	Name        string `json:"name"`
	Company     string `json:"company"`
	Phone       string `json:"phone"`
	Line1       string `json:"line1"`
	Line2       string `json:"line2"`
	City        string `json:"city"`
	State       string `json:"state"`
	PostalCode  string `json:"postalCode"`
	Country     string `json:"country"`
	MakeDefault bool   `json:"makeDefault"`
	Version     *int64 `json:"version"`
}

// Validate applies the seller service's format rules so the form gets field errors before a round trip.
func (in ShipFromInput) Validate() map[string]string {
	errs := map[string]string{}
	req := func(field, v string, max int) {
		if n := len([]rune(strings.TrimSpace(v))); n == 0 || n > max {
			errs[field] = "Required"
		}
	}
	req("name", in.Name, 120)
	req("line1", in.Line1, 120)
	req("city", in.City, 80)
	country := strings.ToUpper(strings.TrimSpace(in.Country))
	if country == "" || country == "US" {
		if !stateRe.MatchString(strings.TrimSpace(in.State)) {
			errs["state"] = "Two-letter state"
		}
		if !zipRe.MatchString(strings.TrimSpace(in.PostalCode)) {
			errs["postalCode"] = "5-digit ZIP"
		}
	} else {
		req("state", in.State, 40)
		req("postalCode", in.PostalCode, 12)
	}
	return errs
}

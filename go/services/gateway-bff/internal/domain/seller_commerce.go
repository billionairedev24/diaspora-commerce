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

// SellerStatuses are the only statuses a seller may set (orders enforces the same rule).
var SellerStatuses = []string{"CONFIRMED", "PACKED", "READY"}

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
	return IsSellerStatus(status)
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
	Timeline       []TimelineEntry      `json:"timeline"`
	LocalDelivery  *SellerLocalDelivery `json:"localDelivery"`
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

// AttributeDef is a product-type attribute.
type AttributeDef struct {
	Key       string   `json:"key"`
	Label     string   `json:"label"`
	Options   []string `json:"options"`
	Required  bool     `json:"required"`
	Multiline bool     `json:"multiline"`
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

// ProductTypeSchema drives steps 3 and 4 of the builder.
type ProductTypeSchema struct {
	Code           string           `json:"code"`
	Label          string           `json:"label"`
	IdentifierRule string           `json:"identifierRule"`
	Attributes     []AttributeDef   `json:"attributes"`
	Themes         []VariationTheme `json:"themes"`
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

// VariantInput is one variant row of step 4.
type VariantInput struct {
	Options    map[string]string `json:"options"`
	SellerSKU  string            `json:"sellerSku"`
	GTIN       string            `json:"gtin,omitempty"`
	GTINExempt bool              `json:"gtinExempt,omitempty"`
	PriceMinor int64             `json:"priceMinor"`
	Quantity   int               `json:"quantity"`
}

// ProductDraft is the builder's submission.
type ProductDraft struct {
	CategoryID        string              `json:"categoryId,omitempty"`
	CategoryRequestID string              `json:"categoryRequestId,omitempty"`
	Title             string              `json:"title"`
	Attributes        map[string]string   `json:"attributes"`
	Images            []ProductImageInput `json:"images"`
	VariationTheme    string              `json:"variationTheme"`
	Variants          []VariantInput      `json:"variants"`
}

// Validate applies the builder's rules (the same ones catalog enforces).
func (d ProductDraft) Validate() map[string]string {
	errs := map[string]string{}
	if (d.CategoryID == "") == (d.CategoryRequestID == "") {
		errs["categoryId"] = "Choose a category, or request a new one."
	}
	if n := len([]rune(strings.TrimSpace(d.Title))); n < 10 || n > 200 {
		errs["title"] = "At least 10 characters"
	}
	if len(d.Images) == 0 {
		errs["images"] = "Add at least one image."
	} else if len(d.Images) > 10 {
		errs["images"] = "Up to 10 images."
	}
	if strings.TrimSpace(d.VariationTheme) == "" {
		errs["variationTheme"] = "Choose how this product varies."
	}
	if len(d.Variants) == 0 {
		errs["variants"] = "Add at least one variation."
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
		case !v.GTINExempt && v.GTIN == "":
			errs["variants"] = "Enter a barcode for every variation, or request a GTIN exemption."
		}
		skus[sku] = true
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
	Currency                  string       `json:"currency"`
	AvailableMinor            int64        `json:"availableMinor"`
	OnHoldMinor               int64        `json:"onHoldMinor"`
	RequestedOrInTransitMinor int64        `json:"requestedOrInTransitMinor"`
	PaidMinor                 int64        `json:"paidMinor"`
	ProviderFeesMinor         int64        `json:"providerFeesMinor"`
	MinimumPayoutMinor        int64        `json:"minimumPayoutMinor"`
	CanRequestToday           bool         `json:"canRequestToday"`
	PayoutsPaused             bool         `json:"payoutsPaused"`
	PayoutsPausedReason       *string      `json:"payoutsPausedReason"`
	PayoutsPausedMessage      *string      `json:"payoutsPausedMessage"`
	Holds                     []PayoutHold `json:"holds"`
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
	AmountDueMinor  int64      `json:"amountDueMinor"`
	AmountPaidMinor int64      `json:"amountPaidMinor"`
	Currency        string     `json:"currency"`
	Status          string     `json:"status"`
	PDFURL          *string    `json:"pdfUrl"`
	HostedURL       *string    `json:"hostedUrl"`
	CreatedAt       time.Time  `json:"createdAt"`
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
}

// BillingView is GET /v1/seller/billing.
type BillingView struct {
	Subscription   Subscription           `json:"subscription"`
	Plans          []Plan                 `json:"plans"`
	PaymentMethods []BillingPaymentMethod `json:"paymentMethods"`
	Invoices       []Invoice              `json:"invoices"`
	CanManage      bool                   `json:"canManage"`
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

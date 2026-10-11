package domain

import (
	"encoding/json"
	"time"
)

// Fulfilment and seller kinds.
const (
	FulfilledByAfroTransact = "AFROTRANSACT"
	ShipsFromSeller         = "SELLER"
	SellerKindPlatform      = "PLATFORM"
	SellerKindSeller        = "SELLER"
	Currency                = "USD"
)

// DeliveryPromise is the delivery estimate to the session's deliver-to ZIP.
type DeliveryPromise struct {
	Days         int     `json:"days"`
	Label        string  `json:"label"`
	Prefix       string  `json:"prefix"`
	Free         bool    `json:"free"`
	FeeMinor     int64   `json:"feeMinor"`
	ExpressLabel *string `json:"expressLabel"`
	Cutoff       *string `json:"cutoff"`
}

// Seller is who sells an offer.
type Seller struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"`
	City string `json:"city"`
}

// Offer is one purchasable offer of a product.
type Offer struct {
	OfferID        string          `json:"offerId"`
	Seller         Seller          `json:"seller"`
	PriceMinor     int64           `json:"priceMinor"`
	ListPriceMinor *int64          `json:"listPriceMinor"`
	Currency       string          `json:"currency"`
	Fulfilment     string          `json:"fulfilment"`
	AvailableQty   int             `json:"availableQty"`
	StockStatus    string          `json:"stockStatus"`
	Delivery       DeliveryPromise `json:"delivery"`
}

// Sponsor labels a sponsored placement.
type Sponsor struct {
	Advertiser string `json:"advertiser"`
	// Search ads (S240): the campaign, the 1-based slot it won, the click token to report back, and why it was shown.
	CampaignID string         `json:"campaignId,omitempty"`
	Slot       int            `json:"slot,omitempty"`
	AdToken    string         `json:"adToken,omitempty"`
	Reason     *SponsorReason `json:"reason,omitempty"`
}

// ProductSummary is a product card (search hit, rail item, typeahead product).
type ProductSummary struct {
	ATIN               string          `json:"atin"`
	Title              string          `json:"title"`
	Brand              string          `json:"brand"`
	Community          string          `json:"community"`
	Department         string          `json:"department"`
	Unit               string          `json:"unit"`
	Rating             float64         `json:"rating"`
	ReviewCount        int             `json:"reviewCount"`
	ImageURL           *string         `json:"imageUrl"`
	OfferID            string          `json:"offerId"`
	PriceMinor         int64           `json:"priceMinor"`
	ListPriceMinor     *int64          `json:"listPriceMinor"`
	Currency           string          `json:"currency"`
	SellerName         string          `json:"sellerName"`
	SellerKind         string          `json:"sellerKind"`
	SellerCity         string          `json:"sellerCity"`
	Fulfilment         string          `json:"fulfilment"`
	AvailableQty       int             `json:"availableQty"`
	InStock            bool            `json:"inStock"`
	LowStock           bool            `json:"lowStock"`
	StockStatus        string          `json:"stockStatus"`
	Delivery           DeliveryPromise `json:"delivery"`
	OfferCount         int             `json:"offerCount"`
	MinOfferPriceMinor int64           `json:"minOfferPriceMinor"`
	Sponsor            *Sponsor        `json:"sponsor"`
}

// VariantOption is one selectable variant on the product page.
type VariantOption struct {
	ATIN       string `json:"atin"`
	Label      string `json:"label"`
	Selected   bool   `json:"selected"`
	Available  bool   `json:"available"`
	PriceMinor int64  `json:"priceMinor"`
}

// ProductImage is a gallery image.
type ProductImage struct {
	URL *string `json:"url"`
	Alt string  `json:"alt"`
}

// ProductInfo is the product block of the product page.
type ProductInfo struct {
	ATIN        string          `json:"atin"`
	Title       string          `json:"title"`
	Brand       string          `json:"brand"`
	Community   string          `json:"community"`
	Department  string          `json:"department"`
	Unit        string          `json:"unit"`
	Origin      string          `json:"origin"`
	Bullets     []string        `json:"bullets"`
	Rating      float64         `json:"rating"`
	ReviewCount int             `json:"reviewCount"`
	Images      []ProductImage  `json:"images"`
	Variants    []VariantOption `json:"variants"`
}

// ProductDetail is the product page.
type ProductDetail struct {
	Product        ProductInfo `json:"product"`
	BuyBox         *Offer      `json:"buyBox"`
	OtherOffers    []Offer     `json:"otherOffers"`
	ShipsFrom      string      `json:"shipsFrom"`
	SoldBy         string      `json:"soldBy"`
	ReturnsPolicy  string      `json:"returnsPolicy"`
	UnitPriceLabel string      `json:"unitPriceLabel"`
}

// FacetBucket is one filter value with its count.
type FacetBucket struct {
	Value string `json:"value"`
	Label string `json:"label"`
	Count int64  `json:"count"`
}

// Facets for the search filter rail.
type Facets struct {
	Department []FacetBucket `json:"department"`
	Community  []FacetBucket `json:"community"`
	Fulfilment []FacetBucket `json:"fulfilment"`
	SellerKind []FacetBucket `json:"sellerKind"`
	Rating     []FacetBucket `json:"rating"`
	Price      []FacetBucket `json:"price"`
	Delivery   []FacetBucket `json:"delivery"`
}

// SearchPage is one page of search results.
type SearchPage struct {
	Hits           []ProductSummary `json:"hits"`
	Total          int64            `json:"total"`
	Page           int              `json:"page"`
	Size           int              `json:"size"`
	PageCount      int              `json:"pageCount"`
	Facets         Facets           `json:"facets"`
	DeliverToLabel string           `json:"deliverToLabel"`
	TookMs         int64            `json:"tookMs"`
	// SearchID identifies this search for click reporting (POST /v1/search/clicks).
	SearchID string `json:"searchId,omitempty"`
	// Sponsored is true when the page carries ads; such pages are never cached.
	Sponsored bool `json:"-"`
}

// Phrase is a completed typeahead phrase.
type Phrase struct {
	Text       string  `json:"text"`
	Department *string `json:"department"`
}

// Suggestions is the typeahead dropdown.
type Suggestions struct {
	Query    string           `json:"query"`
	Phrases  []Phrase         `json:"phrases"`
	Products []ProductSummary `json:"products"`
}

// CartLine is one cart row.
type CartLine struct {
	LineID         string  `json:"lineId"`
	ATIN           string  `json:"atin"`
	OfferID        string  `json:"offerId"`
	Title          string  `json:"title"`
	Unit           string  `json:"unit"`
	Department     string  `json:"department"`
	ImageURL       *string `json:"imageUrl"`
	PriceMinor     int64   `json:"priceMinor"`
	LineTotalMinor int64   `json:"lineTotalMinor"`
	Quantity       int     `json:"quantity"`
	MaxQuantity    int     `json:"maxQuantity"`
	AvailableQty   int     `json:"availableQty"`
	StockStatus    string  `json:"stockStatus"`
	Selected       bool    `json:"selected"`
	// Perishable (catalog product type or attribute): hand delivery with a code, no safe place.
	Perishable bool `json:"perishable"`
}

// DeliveryOption is one way to ship a package.
type DeliveryOption struct {
	Key      string `json:"key"`
	Label    string `json:"label"`
	ETA      string `json:"eta"`
	FeeMinor int64  `json:"feeMinor"`
	Free     bool   `json:"free"`
	// Checkout only: the promise service's dates for this option (empty in the cart).
	EarliestDate string `json:"earliestDate,omitempty"`
	LatestDate   string `json:"latestDate,omitempty"`
	CutoffLabel  string `json:"cutoffLabel,omitempty"`
}

// ShippingNudge is "Add $X for free shipping".
type ShippingNudge struct {
	RemainingMinor int64 `json:"remainingMinor"`
	ThresholdMinor int64 `json:"thresholdMinor"`
}

// CartPackage groups lines shipped together (one seller and fulfilment method).
type CartPackage struct {
	PackageID         string           `json:"packageId"`
	Seller            Seller           `json:"seller"`
	Fulfilment        string           `json:"fulfilment"`
	Heading           string           `json:"heading"`
	ShipsFrom         string           `json:"shipsFrom"`
	Delivery          DeliveryPromise  `json:"delivery"`
	DeliveryOptions   []DeliveryOption `json:"deliveryOptions"`
	Lines             []CartLine       `json:"lines"`
	ItemCount         int              `json:"itemCount"`
	SubtotalMinor     int64            `json:"subtotalMinor"`
	FreeShippingNudge *ShippingNudge   `json:"freeShippingNudge"`
	// Preorder is the pre-order block of a PREORDER package (windowId, name, deliversOn, stage, closesAt, fulfilment,
	// options[], effective doorFee / pickupFee, currency, pickupWindow, allowedMethods), relayed from the cart
	// service; null for packages that ship now.
	Preorder json.RawMessage `json:"preorder"`
}

// SavedItem is a "saved for later" row.
type SavedItem struct {
	SavedID    string  `json:"savedId"`
	ATIN       string  `json:"atin"`
	OfferID    string  `json:"offerId"`
	Title      string  `json:"title"`
	ImageURL   *string `json:"imageUrl"`
	PriceMinor int64   `json:"priceMinor"`
	Available  bool    `json:"available"`
}

// Cart is the cart page / mini cart.
type Cart struct {
	CartID        string        `json:"cartId"`
	Currency      string        `json:"currency"`
	Packages      []CartPackage `json:"packages"`
	Saved         []SavedItem   `json:"saved"`
	ItemCount     int           `json:"itemCount"`
	SelectedCount int           `json:"selectedCount"`
	SubtotalMinor int64         `json:"subtotalMinor"`
	AllSelected   bool          `json:"allSelected"`
	SellerCount   int           `json:"sellerCount"`
}

// DeliveryLocation is the session's deliver-to location.
type DeliveryLocation struct {
	ZIP         string `json:"zip"`
	City        string `json:"city"`
	State       string `json:"state"`
	Label       string `json:"label"`
	Serviceable bool   `json:"serviceable"`
}

// PostalAddress is a structured address from autocomplete.
type PostalAddress struct {
	Line1   string   `json:"line1"`
	Line2   string   `json:"line2"`
	City    string   `json:"city"`
	State   string   `json:"state"`
	ZIP     string   `json:"zip"`
	Country string   `json:"country"`
	Lat     *float64 `json:"lat"`
	Lon     *float64 `json:"lon"`
}

// AddressSuggestion is one autocomplete row.
type AddressSuggestion struct {
	ID        string        `json:"id"`
	Label     string        `json:"label"`
	Secondary string        `json:"secondary"`
	Address   PostalAddress `json:"address"`
	Source    string        `json:"source"`
}

// AddressSuggestions is the autocomplete response.
type AddressSuggestions struct {
	Suggestions []AddressSuggestion `json:"suggestions"`
	Attribution string              `json:"attribution"`
}

// MeUser is the signed-in user.
type MeUser struct {
	ID        string   `json:"id"`
	FirstName string   `json:"firstName"`
	LastName  string   `json:"lastName"`
	Email     string   `json:"email"`
	Initials  string   `json:"initials"`
	Roles     []string `json:"roles"`
	// Phone is masked (•••• 0142); PhoneVerified is nil when identity's profile was unavailable.
	Phone         *string `json:"phone,omitempty"`
	PhoneVerified *bool   `json:"phoneVerified,omitempty"`
}

// PhoneCodeSent says where the phone verification code went.
type PhoneCodeSent struct {
	SentTo    string    `json:"sentTo"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// PhoneVerification is the account phone's verification state.
type PhoneVerification struct {
	PhoneVerified bool       `json:"phoneVerified"`
	VerifiedAt    *time.Time `json:"verifiedAt,omitempty"`
}

// MeCounts are the account menu badges.
type MeCounts struct {
	Orders         int `json:"orders"`
	Returns        int `json:"returns"`
	Addresses      int `json:"addresses"`
	PaymentMethods int `json:"paymentMethods"`
}

// Me is who is signed in.
type Me struct {
	Authenticated bool      `json:"authenticated"`
	User          *MeUser   `json:"user"`
	Counts        *MeCounts `json:"counts"`
}

// Address is a saved delivery address.
type Address struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Phone     string `json:"phone"`
	Line1     string `json:"line1"`
	Line2     string `json:"line2"`
	City      string `json:"city"`
	State     string `json:"state"`
	ZIP       string `json:"zip"`
	IsDefault bool   `json:"isDefault"`
	Verified  bool   `json:"verified"`
}

// AddressInput creates or edits an address.
type AddressInput struct {
	Name             string `json:"name"`
	Phone            string `json:"phone"`
	Line1            string `json:"line1"`
	Line2            string `json:"line2"`
	City             string `json:"city"`
	State            string `json:"state"`
	ZIP              string `json:"zip"`
	IsDefault        bool   `json:"isDefault"`
	AcceptUnverified bool   `json:"acceptUnverified"`
}

// PaymentMethod is a saved Stripe payment method (display data only).
type PaymentMethod struct {
	ID        string  `json:"id"`
	Type      string  `json:"type"`
	Brand     *string `json:"brand"`
	Last4     *string `json:"last4"`
	Label     string  `json:"label"`
	Expires   *string `json:"expires"`
	IsDefault bool    `json:"isDefault"`
}

// SetupIntent is the Stripe Payment Element bootstrap.
type SetupIntent struct {
	ClientSecret   string `json:"clientSecret"`
	PublishableKey string `json:"publishableKey"`
}

// Page is a cursor page of items.
type Page[T any] struct {
	Items      []T     `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

// OrderShipment is one shipment of an order.
type OrderShipment struct {
	ShipmentID string `json:"shipmentId"`
	Label      string `json:"label"`
	Status     string `json:"status"`
	Items      string `json:"items"`
	// Optional facts from orders (Your orders cards): cancel button, tracking, handover code.
	Cancellable    bool    `json:"cancellable,omitempty"`
	Carrier        *string `json:"carrier,omitempty"`
	TrackingNumber *string `json:"trackingNumber,omitempty"`
	PromisedBy     *string `json:"promisedBy,omitempty"`
	DeliveredAt    *string `json:"deliveredAt,omitempty"`
	DeliveryCode   *string `json:"deliveryCode,omitempty"`
	// Returns (S148/S166).
	Returnable         bool    `json:"returnable,omitempty"`
	ReturnWindowEndsAt *string `json:"returnWindowEndsAt,omitempty"`
	ProblemReportOnly  bool    `json:"problemReportOnly,omitempty"`
}

// OrderSummary is one order in Your orders.
type OrderSummary struct {
	OrderNumber string          `json:"orderNumber"`
	PlacedAt    string          `json:"placedAt"`
	TotalMinor  int64           `json:"totalMinor"`
	Currency    string          `json:"currency"`
	ShipTo      string          `json:"shipTo"`
	Status      string          `json:"status"`
	Shipments   []OrderShipment `json:"shipments"`
}

// ReturnSummary is one return (RMA).
type ReturnSummary struct {
	RMAID       string `json:"rmaId"`
	OrderNumber string `json:"orderNumber"`
	Title       string `json:"title"`
	Status      string `json:"status"`
	RefundMinor int64  `json:"refundMinor"`
	// Optional facts from tax-returns (drop-off label / QR, driver handover code, cancel button).
	Number       string  `json:"number,omitempty"`
	Method       string  `json:"method,omitempty"`
	Reason       string  `json:"reason,omitempty"`
	RefundState  string  `json:"refundState,omitempty"`
	Currency     string  `json:"currency,omitempty"`
	Cancellable  bool    `json:"cancellable,omitempty"`
	CreatedAt    *string `json:"createdAt,omitempty"`
	LabelURL     *string `json:"labelUrl,omitempty"`
	QRPayload    *string `json:"qrPayload,omitempty"`
	HandoverCode *string `json:"handoverCode,omitempty"`
	DropOffBy    *string `json:"dropOffBy,omitempty"`
	Carrier      *string `json:"carrier,omitempty"`
	Tracking     *string `json:"trackingNumber,omitempty"`
}

// StartReturn starts a return: one delivered shipment, a method and a reason per line. The legacy shape
// (orderNumber + atin + reason, used by the customer app) returns one unit of that item with the first allowed method.
type StartReturn struct {
	OrderNumber string            `json:"orderNumber"`
	ShipmentID  string            `json:"shipmentId"`
	Method      string            `json:"method"`
	Lines       []StartReturnLine `json:"lines"`
	ATIN        string            `json:"atin"`
	Reason      string            `json:"reason"`
}

// StartReturnLine is one returned order line.
type StartReturnLine struct {
	LineID   string `json:"lineId"`
	Quantity int    `json:"quantity"`
	Reason   string `json:"reason"`
	Comment  string `json:"comment,omitempty"`
}

// PickupPoint is a pickup location offered for a package at checkout.
type PickupPoint struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Address string `json:"address"`
	Hours   string `json:"hours"`
	Ready   string `json:"ready"`
	SetBy   string `json:"setBy"`
}

// CheckoutPackage is a cart package with pickup points.
type CheckoutPackage struct {
	CartPackage
	// LocalDelivery is the quote of AfroTransact Delivery from the seller's store (S255); AFROTRANSACT_DELIVERY packages only.
	LocalDelivery *CheckoutLocalDelivery `json:"localDelivery,omitempty"`
	PickupPoints  []PickupPoint          `json:"pickupPoints"`
}

// Reasons AfroTransact Delivery from the seller's store can't be quoted for an address.
const (
	LocalOutOfRange        = "OUT_OF_RANGE"
	LocalUnavailable       = "UNAVAILABLE"
	LocalPickupUnavailable = "PICKUP_UNAVAILABLE"
	LocalAddressNotLocated = "ADDRESS_NOT_LOCATED"
	LocalQuoteUnavailable  = "QUOTE_UNAVAILABLE"
)

// CheckoutLocalDelivery is the checkout quote of an AfroTransact Delivery package (S255).
type CheckoutLocalDelivery struct {
	Available         bool     `json:"available"`
	ChargeMinor       *int64   `json:"chargeMinor"`
	RoadMiles         *float64 `json:"roadMiles"`
	DurationMinutes   *float64 `json:"durationMinutes"`
	FromCity          string   `json:"fromCity"`
	UnavailableReason *string  `json:"unavailableReason"`
	Message           *string  `json:"message"`
	MaxRadiusMiles    *float64 `json:"maxRadiusMiles"`
	OfferIDs          []string `json:"offerIds"`
}

// OrderLocalDelivery is AfroTransact Delivery from the seller's store on an order shipment (seller fee hidden).
type OrderLocalDelivery struct {
	RoadMiles       *float64 `json:"roadMiles"`
	DurationMinutes *float64 `json:"durationMinutes"`
	ChargeMinor     int64    `json:"chargeMinor"`
}

// CheckoutSummary is the checkout page.
type CheckoutSummary struct {
	Packages       []CheckoutPackage `json:"packages"`
	Addresses      []Address         `json:"addresses"`
	PaymentMethods []PaymentMethod   `json:"paymentMethods"`
	SubtotalMinor  int64             `json:"subtotalMinor"`
	ShippingMinor  int64             `json:"shippingMinor"`
	TaxMinor       int64             `json:"taxMinor"`
	TaxRateLabel   string            `json:"taxRateLabel"`
	TotalMinor     int64             `json:"totalMinor"`
	Currency       string            `json:"currency"`
	SellerCount    int               `json:"sellerCount"`
	// PublishableKey is the Stripe key for Stripe.js (Payment Element, Express Checkout Element).
	PublishableKey string `json:"publishableKey,omitempty"`
}

// PlaceOrderRequest is the checkout submission (forwarded to orders as-is plus the selected lines).
type PlaceOrderRequest struct {
	AddressID          string            `json:"addressId"`
	PaymentMethodID    string            `json:"paymentMethodId,omitempty"`
	Wallet             string            `json:"wallet,omitempty"`
	Gift               bool              `json:"gift,omitempty"`
	DeliverySelections []json.RawMessage `json:"deliverySelections"`
	// NewPaymentMethod: paid with a new method from Stripe's Payment Element (deferred intent, confirmed client-side).
	NewPaymentMethod bool `json:"newPaymentMethod,omitempty"`
	// SavePaymentMethod (with NewPaymentMethod) keeps the method on the shopper's Stripe Customer.
	SavePaymentMethod bool `json:"savePaymentMethod,omitempty"`
	// CouponCode is validated and redeemed by orders with offers-service (released if the order fails).
	CouponCode string `json:"couponCode,omitempty"`
	// Attribution is the last-touch source {src, cr, po}; the handler falls back to the at_attr cookie.
	Attribution *Attribution `json:"attribution,omitempty"`
}

// OrderConfirmationShipment is one shipment of a placed order.
type OrderConfirmationShipment struct {
	ShipmentID   string  `json:"shipmentId"`
	Label        string  `json:"label"`
	ETA          string  `json:"eta"`
	DeliveryCode *string `json:"deliveryCode"`
}

// OrderConfirmation is the result of placing an order.
type OrderConfirmation struct {
	OrderNumber         string                      `json:"orderNumber"`
	Status              string                      `json:"status"`
	PaymentClientSecret *string                     `json:"paymentClientSecret"`
	TotalMinor          int64                       `json:"totalMinor"`
	Currency            string                      `json:"currency"`
	Shipments           []OrderConfirmationShipment `json:"shipments"`
	// Preorder is the "You're in" block of an order with a pre-order package (number, windowName, deliversOn,
	// closesAt, timeZone, otherItemCount), relayed from orders; omitted otherwise.
	Preorder json.RawMessage `json:"preorder,omitempty"`
}

// StockAlertItem is one of the shopper's back-in-stock alerts (GET /v1/account/stock-alerts).
type StockAlertItem struct {
	ATIN         string     `json:"atin"`
	ProductTitle string     `json:"productTitle"`
	Status       string     `json:"status"` // active | notified
	CreatedAt    time.Time  `json:"createdAt"`
	NotifiedAt   *time.Time `json:"notifiedAt"`
}

// PasskeyCredential is an allowed credential.
type PasskeyCredential struct {
	ID   string `json:"id"`
	Type string `json:"type"`
}

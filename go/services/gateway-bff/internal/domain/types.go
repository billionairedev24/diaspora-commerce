package domain

import (
	"encoding/json"
	"math"
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
	Days     int     `json:"days"`
	Label    string  `json:"label"`
	Prefix   string  `json:"prefix"`
	Free     bool    `json:"free"`
	FeeMinor int64   `json:"feeMinor"`
	Cutoff   *string `json:"cutoff"`
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
	CampaignID string `json:"campaignId,omitempty"`
	Slot       int    `json:"slot,omitempty"`
	AdToken    string `json:"adToken,omitempty"`
	// SearchID is the search the ad came from, set on home sponsored products (several searches fill one rail); a click
	// is reported with it.
	SearchID string         `json:"searchId,omitempty"`
	Reason   *SponsorReason `json:"reason,omitempty"`
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
	// ShipsFrom ("Houston, TX") and SellerID come from the buy box; the home rules read them (never serialised).
	ShipsFrom string `json:"-"`
	SellerID  string `json:"-"`
}

// VariantOption is one selectable variant on the product page.
type VariantOption struct {
	ATIN       string `json:"atin"`
	Label      string `json:"label"`
	Selected   bool   `json:"selected"`
	Available  bool   `json:"available"`
	PriceMinor int64  `json:"priceMinor"`
	// Options are the variant's values per dimension (Size, Color), as the seller wrote them.
	Options map[string]string `json:"options"`
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
	// SizeChart is the seller's size chart of a sized product (fashion); null for the others.
	SizeChart *ProductSizeChart `json:"sizeChart"`
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
	// Services are the services that match the words (first page of a text search only): a few, best match first.
	Services []SearchServiceHit `json:"services"`
	// SearchID identifies this search for click reporting (POST /v1/search/clicks).
	SearchID string `json:"searchId,omitempty"`
	// Sponsored is true when the page carries ads; such pages are never cached.
	Sponsored bool `json:"-"`
}

// SearchServiceHit is a service that matches a product search's words (the full list is the services page).
type SearchServiceHit struct {
	ServiceID       string `json:"serviceId"`
	OrgID           string `json:"orgId"`
	Name            string `json:"name"`
	ProviderName    string `json:"providerName"`
	Category        string `json:"category"`
	DurationMinutes int    `json:"durationMinutes"`
	PriceFromMinor  int64  `json:"priceFromMinor"`
	Currency        string `json:"currency"`
	Location        string `json:"location"`
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
	LineID string `json:"lineId"`
	// ATIN is the PRODUCT ATIN (links to the product page). It is not the id tax-returns knows the item by.
	ATIN    string `json:"atin"`
	OfferID string `json:"offerId"`
	// VariantATIN is the variant (SKU) ATIN: the id catalog, inventory and tax-returns' classification records are keyed
	// by, so the one id a tax quote line may carry. Not serialised (LineID is the same value on the wire).
	VariantATIN    string  `json:"-"`
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
	// ProductType is the catalog product type code (tax-returns maps it to GENERAL | GROCERY | PREPARED_FOOD).
	ProductType string `json:"productType,omitempty"`
	// SellerBehaviour is how the line's seller sells the item (sold_heated, utensils_provided ...) as catalog reports it
	// for the seller's own listing. It is part of the tax decision, so the checkout estimate sends it to tax-returns
	// exactly as orders does; never serialised.
	SellerBehaviour map[string]any `json:"-"`
	// FactsMissing is true when catalog's facts for the line could not be read: the tax estimate then says so instead
	// of quoting without them (orders would quote with them); never serialised.
	FactsMissing bool `json:"-"`
}

// DeliveryOption is one way to ship a package.
type DeliveryOption struct {
	Key      string `json:"key"`
	Label    string `json:"label"`
	ETA      string `json:"eta"`
	FeeMinor int64  `json:"feeMinor"`
	Free     bool   `json:"free"`
	// FeeUnknown is true when the staff delivery fee could not be read: feeMinor is then not a price (never shown as $0)
	// and the order cannot be placed until it is known.
	FeeUnknown bool `json:"feeUnknown,omitempty"`
	// Checkout only: the promise service's dates for this option (empty in the cart).
	EarliestDate string `json:"earliestDate,omitempty"`
	LatestDate   string `json:"latestDate,omitempty"`
	CutoffLabel  string `json:"cutoffLabel,omitempty"`
	// FeeRule is the staff delivery fee rule that priced the option (carrier shipping; ADR 0005 §3); absent when the
	// flat estimate applies. Orders keep the rule they charge by with the order.
	FeeRule *FeeRuleRef `json:"feeRule,omitempty"`
}

// FeeRuleRef names the delivery fee rule behind a fee: the method, the rule and its version, the amount and where it
// came from (RULE, CARRIER_FALLBACK, ...). The storefront shows it next to the fee.
type FeeRuleRef struct {
	Method      string `json:"method"`
	RuleID      string `json:"ruleId,omitempty"`
	RuleVersion int64  `json:"ruleVersion"`
	AmountMinor int64  `json:"amountMinor"`
	Source      string `json:"source,omitempty"`
	Band        string `json:"band,omitempty"`
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
	// SellerOrgRaw is the seller org id exactly as the cart service sent it (empty for AfroTransact's own items): the
	// tax quote sends this, as orders does, never the display fallback in Seller.ID; never serialised.
	SellerOrgRaw string `json:"-"`
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
	ZIP   string `json:"zip"`
	City  string `json:"city"`
	State string `json:"state"`
	// Label is the header value: the city (never the ZIP; the ZIP stays internal).
	Label       string `json:"label"`
	Serviceable bool   `json:"serviceable"`
	// Source is "chosen" (the shopper picked it), "detected" (edge headers / override) or "default" (the market's).
	Source string `json:"source"`
	// Precision is how exactly the place is known: "ZIP" (a ZIP the shopper chose or the edge reported) or "CITY" (only
	// the city and state were known and the ZIP is the city's representative one from geo's table). Empty means ZIP.
	Precision string `json:"precision,omitempty"`
}

// Location precisions (DeliveryLocation.Precision, tax-returns shipTo.precision).
const (
	PrecisionZip  = "ZIP"
	PrecisionCity = "CITY"
)

// Location sources.
const (
	LocationChosen   = "chosen"
	LocationDetected = "detected"
	LocationDefault  = "default"
)

// EdgeHints are the visitor-location headers an edge (Cloudflare) adds to a request.
type EdgeHints struct {
	Country, City, Region, ZIP string
	Lat, Lon                   *float64
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
	// Resolved is false when Address holds the street line only (Google Places): resolve the picked suggestion
	// with GET /v1/geo/address-suggestions/{id} and the session token.
	Resolved bool `json:"resolved"`
}

// AddressSuggestions is the autocomplete response.
type AddressSuggestions struct {
	Suggestions []AddressSuggestion `json:"suggestions"`
	Attribution string              `json:"attribution"`
	// SessionToken groups one address entry's keystrokes and its resolve into one Google Places billing session.
	SessionToken string `json:"sessionToken"`
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
	// BackOffices are the person's Seller Center / Provider Hub / staff console links (never nil).
	BackOffices []BackOffice `json:"backOffices"`
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

// MeCounts are the account menu badges.
type MeCounts struct {
	Orders         int `json:"orders"`
	Addresses      int `json:"addresses"`
	PaymentMethods int `json:"paymentMethods"`
}

// Me is who is signed in.
type Me struct {
	Authenticated bool      `json:"authenticated"`
	User          *MeUser   `json:"user"`
	Counts        *MeCounts `json:"counts"`
	// Session is the sign-out policy in force for this session (GET /v1/session/policy); absent for guests.
	Session *SessionPolicyView `json:"session,omitempty"`
	// SignedOutReason is SESSION_IDLE_TIMEOUT or SESSION_ABSOLUTE_TIMEOUT for ten minutes after the session ended by
	// policy; absent otherwise.
	SignedOutReason string `json:"signedOutReason,omitempty"`
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
	// SellerName and Lines say what is in the shipment (name, photo, quantity) so Your orders can show it.
	SellerName string              `json:"sellerName,omitempty"`
	Lines      []OrderShipmentLine `json:"lines,omitempty"`
	// Optional facts from orders (Your orders cards): cancel button, tracking, handover code.
	Cancellable bool `json:"cancellable,omitempty"`
	// CancellableUntil is when Cancel stops being offered (null when it is not offered now).
	CancellableUntil *string `json:"cancellableUntil,omitempty"`
	Carrier          *string `json:"carrier,omitempty"`
	TrackingNumber   *string `json:"trackingNumber,omitempty"`
	PromisedBy       *string `json:"promisedBy,omitempty"`
	DeliveredAt      *string `json:"deliveredAt,omitempty"`
	DeliveryCode     *string `json:"deliveryCode,omitempty"`
	// Returns (S148/S166).
	Returnable         bool    `json:"returnable,omitempty"`
	ReturnWindowEndsAt *string `json:"returnWindowEndsAt,omitempty"`
	ProblemReportOnly  bool    `json:"problemReportOnly,omitempty"`
}

// OrderShipmentLine is one product in a shipment of Your orders.
type OrderShipmentLine struct {
	Title       string  `json:"title"`
	Quantity    int     `json:"quantity"`
	ImageURL    *string `json:"imageUrl,omitempty"`
	ProductATIN string  `json:"productAtin,omitempty"`
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

// PickupPoint is a pickup location offered for a package at checkout.
type PickupPoint struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Address string `json:"address"`
	Hours   string `json:"hours"`
	Ready   string `json:"ready"`
	SetBy   string `json:"setBy"`
	// Kind is carrier-gateway's PLATFORM_COUNTER, LOCKER or SELLER.
	Kind string `json:"kind,omitempty"`
	// DistanceMiles is the straight-line distance from the delivery address (nil when either isn't located).
	DistanceMiles *float64 `json:"distanceMiles"`
}

// HaversineMiles is the great-circle distance between two WGS84 points in miles.
func HaversineMiles(lat1, lng1, lat2, lng2 float64) float64 {
	const earthMiles = 3958.8
	rad := func(d float64) float64 { return d * math.Pi / 180 }
	dLat, dLng := rad(lat2-lat1), rad(lng2-lng1)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(rad(lat1))*math.Cos(rad(lat2))*math.Sin(dLng/2)*math.Sin(dLng/2)
	return 2 * earthMiles * math.Asin(math.Min(1, math.Sqrt(a)))
}

// CheckoutPackage is a cart package with pickup points.
type CheckoutPackage struct {
	CartPackage
	// MembershipWaivedMinor is the part of this package's delivery fee the shopper's membership waives (S323).
	MembershipWaivedMinor int64 `json:"membershipWaivedMinor"`
	// LocalDelivery is the quote of AfroTransact Delivery from the seller's store (S255); AFROTRANSACT_DELIVERY packages only.
	LocalDelivery *CheckoutLocalDelivery `json:"localDelivery,omitempty"`
	PickupPoints  []PickupPoint          `json:"pickupPoints"`
	// PickupUnavailableReason is why the package has no pickup option (PickupWhy*); empty when it has one. Machine-readable:
	// the app words it, shoppers never see ids or the code itself.
	PickupUnavailableReason string `json:"pickupUnavailableReason,omitempty"`
}

// Reasons a package has no pickup option at checkout (CheckoutPackage.PickupUnavailableReason).
const (
	// PickupWhyNeedsAddress: no delivery address with a 5 digit ZIP is chosen yet.
	PickupWhyNeedsAddress = "NEEDS_ADDRESS"
	// PickupWhySellerNotEnabled: the seller has not turned on customer pickup for any place (no active pickup place).
	PickupWhySellerNotEnabled = "SELLER_NOT_ENABLED"
	// PickupWhyTooFar: the seller has pickup places, all farther than the pickup radius from the address.
	PickupWhyTooFar = "TOO_FAR"
	// PickupWhyOtherArea: the seller has pickup places that are not located and sit in another ZIP area.
	PickupWhyOtherArea = "OTHER_AREA"
	// PickupWhyNoPlatformPoint: an AfroTransact fulfilled package and no AfroTransact counter or locker near the address.
	PickupWhyNoPlatformPoint = "NO_PLATFORM_POINT"
	// PickupWhyLookupFailed: carrier-gateway could not be read, so pickup could not be checked.
	PickupWhyLookupFailed = "LOOKUP_FAILED"
)

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

// CheckoutCouponSellerShare is how much of the coupon discount applies to one seller's offers (settlement preview).
type CheckoutCouponSellerShare struct {
	SellerOrgID   string `json:"sellerOrgId"`
	SellerName    string `json:"sellerName,omitempty"`
	DiscountMinor int64  `json:"discountMinor"`
	// Funding is who pays this slice: platform (AfroTransact marketing) or seller (reduces their payout).
	Funding string `json:"funding"`
}

// CheckoutCouponOfferDiscount is the coupon amount allocated to one offer line (tax and settlement preview).
type CheckoutCouponOfferDiscount struct {
	OfferID       string `json:"offerId"`
	SellerOrgID   string `json:"sellerOrgId,omitempty"`
	DiscountMinor int64  `json:"discountMinor"`
}

// CheckoutCoupon is a coupon priced against the current cart (validate only; redemption happens at place order).
type CheckoutCoupon struct {
	Code                  string                        `json:"code"`
	Applicable            bool                          `json:"applicable"`
	Rejection             *string                       `json:"rejection,omitempty"`
	Message               string                        `json:"message,omitempty"`
	DiscountMinor         int64                         `json:"discountMinor"`
	ShippingDiscountMinor int64                         `json:"shippingDiscountMinor"`
	Funding               string                        `json:"funding,omitempty"`
	FundedByOrg           string                        `json:"fundedByOrg,omitempty"`
	OfferDiscounts        []CheckoutCouponOfferDiscount `json:"offerDiscounts,omitempty"`
	SellerShares          []CheckoutCouponSellerShare   `json:"sellerShares,omitempty"`
}

// Tax line statuses of CheckoutTaxLine.
// Tax estimate states of CheckoutSummary.TaxEstimateStatus. The storefront words the tax row from this, never from the
// label text: ESTIMATED shows the amount (the same tax-returns quote orders asks for at order time), NEEDS_ADDRESS asks
// for a ZIP code, UNAVAILABLE is the exceptional state (tax-returns could not answer): Place order stays blocked and the
// row offers Retry. The tax is never shown as $0.00 or 0% unless every line is really exempt, and the shopper is never
// told it is calculated later.
const (
	TaxEstimated    = "ESTIMATED"
	TaxUnavailable  = "UNAVAILABLE"
	TaxNeedsAddress = "NEEDS_ADDRESS"
)

// Reasons behind CheckoutSummary.TaxUnavailableReason (a status other than ESTIMATED).
const (
	// TaxReasonNeedsZip: no destination yet (no address and no ZIP entered).
	TaxReasonNeedsZip = "NEEDS_ZIP"
	// TaxReasonAddressIncomplete: the chosen address has no state or ZIP code; the shopper completes the address.
	TaxReasonAddressIncomplete = "ADDRESS_INCOMPLETE"
	// TaxReasonBlockedItems: a line has no definite tax decision (BlockedLines lists it; the shopper removes it).
	TaxReasonBlockedItems = "BLOCKED_ITEMS"
	// TaxReasonServiceUnavailable: tax-returns did not answer (down, slow, refused). Retry.
	TaxReasonServiceUnavailable = "SERVICE_UNAVAILABLE"
	// TaxReasonFactsUnavailable: catalog's facts for an item could not be read, so the quote would not match orders'. Retry.
	TaxReasonFactsUnavailable = "FACTS_UNAVAILABLE"
)

const (
	TaxLineTaxable = "TAXABLE"
	TaxLineExempt  = "EXEMPT"
	TaxLineBlocked = "BLOCKED"
)

// CheckoutTaxLine is the tax estimate of one cart line. Shipping is the line's share of its package's delivery charge.
type CheckoutTaxLine struct {
	LineID        string `json:"lineId"`
	Status        string `json:"status"`
	TaxMinor      int64  `json:"taxMinor"`
	ShippingMinor int64  `json:"shippingMinor"`
	// ExemptReason is why an EXEMPT line carries no tax, from tax-returns' stored decision (customer-safe words). Only a
	// RESOLVED, EXEMPT line has one; a taxable or blocked line never does.
	ExemptReason string `json:"exemptReason,omitempty"`
}

// ShippingTaxAllocation is the method that attributed a package's delivery charge to its lines for the estimate.
// Approved stays false until the tax adviser signs off the mixed-basket method (open legal decision 5).
type ShippingTaxAllocation struct {
	Method   string `json:"method"`
	Approved bool   `json:"approved"`
	Note     string `json:"note"`
}

// CheckoutBlockedLine is a cart line that can't be sold yet because its tax decision is not definite. The message is
// for the shopper (the item's name and what happens next, no ids and no tax terms); Action is the one thing that
// clears it from the shopper's side.
type CheckoutBlockedLine struct {
	LineID    string `json:"lineId"`
	OfferID   string `json:"offerId"`
	ATIN      string `json:"atin"`
	Title     string `json:"title"`
	PackageID string `json:"packageId"`
	Message   string `json:"message"`
	Action    string `json:"action"`
}

// CheckoutSummary is the checkout page.
type CheckoutSummary struct {
	Packages       []CheckoutPackage `json:"packages"`
	Addresses      []Address         `json:"addresses"`
	PaymentMethods []PaymentMethod   `json:"paymentMethods"`
	SubtotalMinor  int64             `json:"subtotalMinor"`
	ShippingMinor  int64             `json:"shippingMinor"`
	// DiscountMinor is the item discount from an applied coupon (subtotal reduction before tax).
	DiscountMinor int64 `json:"discountMinor"`
	// ShippingDiscountMinor is free-shipping (or partial) from the coupon.
	ShippingDiscountMinor int64  `json:"shippingDiscountMinor"`
	TaxMinor              int64  `json:"taxMinor"`
	TaxRateLabel          string `json:"taxRateLabel"`
	// TaxEstimateStatus is ESTIMATED, UNAVAILABLE or NEEDS_ADDRESS (see the constants).
	TaxEstimateStatus string `json:"taxEstimateStatus"`
	// TaxJurisdictionLabel names where the estimate was worked out for the shopper ("Houston, TX"); empty without an
	// estimate. The storefront shows "Estimated tax" with it.
	TaxJurisdictionLabel string `json:"taxJurisdictionLabel"`
	// TaxUnavailableReason says why there is no estimate (see the TaxReason constants); empty when ESTIMATED.
	TaxUnavailableReason string `json:"taxUnavailableReason,omitempty"`
	// TaxZip is the ZIP code the estimate was worked out for (the address's, or the one the shopper entered).
	TaxZip string `json:"taxZip,omitempty"`
	// TaxQuoteID locks the shown tax and total for TaxQuoteExpiresAt (a signed token bound to the cart lines, address,
	// delivery choices and coupon): Place order carries it, and orders refuses with CHECKOUT_TOTAL_CHANGED (the new
	// numbers in the problem) rather than charge more than the total shown. Empty without an estimate.
	TaxQuoteID        string `json:"taxQuoteId,omitempty"`
	TaxQuoteExpiresAt string `json:"taxQuoteExpiresAt,omitempty"`
	// TaxLines is the estimate per cart line (taxable, exempt or blocked) for mixed baskets; empty without an estimate.
	TaxLines []CheckoutTaxLine `json:"taxLines"`
	// ShippingTaxAllocation says how the delivery charge was attributed to lines for the estimate. The mixed-basket
	// method is an OPEN LEGAL DECISION (open decision 5) until the tax adviser approves it.
	ShippingTaxAllocation *ShippingTaxAllocation `json:"shippingTaxAllocation,omitempty"`
	// BlockedLines are the cart lines that can't be sold yet (no definite tax decision): checkout can't be placed
	// while any remains; the shopper removes them. Empty when nothing is blocked.
	BlockedLines []CheckoutBlockedLine `json:"blockedLines"`
	// CanPlaceOrder is false while a blocked line remains, a package is too far for standard delivery (quote_required) or
	// the delivery fee is not known.
	CanPlaceOrder bool `json:"canPlaceOrder"`
	// DeliveryBlockedReason says in plain words why delivery stops the order (empty when it does not).
	DeliveryBlockedReason string          `json:"deliveryBlockedReason,omitempty"`
	TotalMinor            int64           `json:"totalMinor"`
	Currency              string          `json:"currency"`
	SellerCount           int             `json:"sellerCount"`
	Coupon                *CheckoutCoupon `json:"coupon,omitempty"`
	// PublishableKey is the Stripe key for Stripe.js (Payment Element, Express Checkout Element).
	PublishableKey string `json:"publishableKey,omitempty"`
	// StripeCustomerID is the shopper's Stripe Customer (cus_...); passed to the Payment Element to show saved cards.
	StripeCustomerID string `json:"stripeCustomerId,omitempty"`
	// CrossSell is the checkout_cross_sell placement from content (optional product rail).
	CrossSell *Rail `json:"crossSell,omitempty"`
	// Credit is what the shopper's AfroTransact credit would pay of this order (S320); absent without credit.
	Credit *CheckoutCredit `json:"credit,omitempty"`
	// MembershipWaivedMinor is the delivery fee the shopper's membership waives on this order (S323): ShippingMinor
	// stays the fees as priced, the total is lower by this amount.
	MembershipWaivedMinor int64 `json:"membershipWaivedMinor"`
	// Membership says which fees were waived ("fee waived: membership") or, for a shopper who is not a member, what
	// joining would save on this order; absent when there is nothing to say.
	Membership *MembershipSavings `json:"membership,omitempty"`
}

// CartTaxEstimate is the tax estimate for a ZIP code the shopper typed before an address exists (cart page, new shopper):
// GET /v1/checkout/tax-estimate?zip=. Same fields and meaning as the summary's tax row.
type CartTaxEstimate struct {
	TaxEstimateStatus    string                `json:"taxEstimateStatus"`
	TaxUnavailableReason string                `json:"taxUnavailableReason,omitempty"`
	TaxMinor             int64                 `json:"taxMinor"`
	TaxRateLabel         string                `json:"taxRateLabel"`
	TaxJurisdictionLabel string                `json:"taxJurisdictionLabel"`
	TaxZip               string                `json:"taxZip,omitempty"`
	TaxLines             []CheckoutTaxLine     `json:"taxLines"`
	BlockedLines         []CheckoutBlockedLine `json:"blockedLines,omitempty"`
	// TaxPlaceSource says where the estimate's place came from (SAVED_ADDRESS, CHOSEN_PLACE, DETECTED_PLACE, DEFAULT_PLACE
	// or ZIP); absent when there is no place yet.
	TaxPlaceSource string `json:"taxPlaceSource,omitempty"`
	// TaxPlacePrecision is ZIP (a real ZIP: saved, chosen, typed or reported by the edge) or CITY (only the city was known;
	// the ZIP is the city's representative one), so the row can say how exact the estimate is. Absent with no place.
	TaxPlacePrecision string `json:"taxPlacePrecision,omitempty"`
	SubtotalMinor     int64  `json:"subtotalMinor"`
	ShippingMinor     int64  `json:"shippingMinor"`
	TotalMinor        int64  `json:"totalMinor"`
	Currency          string `json:"currency"`
}

// Places a CartTaxEstimate can be worked out for (CartTaxEstimate.TaxPlaceSource).
const (
	TaxPlaceSavedAddress = "SAVED_ADDRESS"
	TaxPlaceChosen       = "CHOSEN_PLACE"
	TaxPlaceDetected     = "DETECTED_PLACE"
	TaxPlaceDefault      = "DEFAULT_PLACE"
	TaxPlaceZip          = "ZIP"
)

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
	// UseCredit pays with the shopper's AfroTransact credit first (S320); the card pays only the rest, and an order that
	// credit pays in full needs no payment method.
	UseCredit bool `json:"useCredit,omitempty"`
	// CouponCode is validated and redeemed by orders with offers-service (released if the order fails).
	CouponCode string `json:"couponCode,omitempty"`
	// TaxQuoteID is the lock the summary returned (taxQuoteId). With it orders never charges more than the total the
	// shopper saw: if the total would be higher the order is refused with 409 CHECKOUT_TOTAL_CHANGED and the new numbers.
	TaxQuoteID string `json:"taxQuoteId,omitempty"`
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
	// CreditMinor is the part of the total paid with AfroTransact credit (S320); the card pays the rest.
	CreditMinor int64 `json:"creditMinor"`
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

package domain

import "encoding/json"

// Screen shapes of the shopper's account (order detail, returns, login & security), service bookings and Lola
// (contracts/openapi/gateway-bff.yaml). Optional upstream facts are pointers so absent values serialise as null.

// ---------------------------------------------------------------- orders

// OrderAddress is the order's ship-to snapshot.
type OrderAddress struct {
	Name  string `json:"name"`
	Line1 string `json:"line1"`
	Line2 string `json:"line2"`
	City  string `json:"city"`
	State string `json:"state"`
	ZIP   string `json:"zip"`
}

// OrderLineView is one bought line.
type OrderLineView struct {
	LineID         string  `json:"lineId"`
	ATIN           string  `json:"atin"`
	Title          string  `json:"title"`
	ImageURL       *string `json:"imageUrl"`
	Quantity       int     `json:"quantity"`
	UnitPriceMinor int64   `json:"unitPriceMinor"`
	ItemsMinor     int64   `json:"itemsMinor"`
}

// TimelineEvent is one step of a shipment's tracking timeline.
type TimelineEvent struct {
	Status string `json:"status"`
	Label  string `json:"label"`
	At     string `json:"at"`
}

// ShipmentDetail is one shipment of an order with tracking.
type ShipmentDetail struct {
	ShipmentID     string          `json:"shipmentId"`
	Label          string          `json:"label"`
	Status         string          `json:"status"`
	StatusLabel    string          `json:"statusLabel"`
	Items          string          `json:"items"`
	SellerName     string          `json:"sellerName"`
	DeliveryMethod string          `json:"deliveryMethod"`
	Cancellable    bool            `json:"cancellable"`
	Carrier        *string         `json:"carrier"`
	TrackingNumber *string         `json:"trackingNumber"`
	PromisedFrom   *string         `json:"promisedFrom"`
	PromisedBy     *string         `json:"promisedBy"`
	DeliveredAt    *string         `json:"deliveredAt"`
	DeliveryCode   *string         `json:"deliveryCode"`
	Lines          []OrderLineView `json:"lines"`
	Timeline       []TimelineEvent `json:"timeline"`
}

// OrderDetail is one order with its shipments, lines and tracking.
type OrderDetail struct {
	OrderNumber     string           `json:"orderNumber"`
	PlacedAt        string           `json:"placedAt"`
	Status          string           `json:"status"`
	Currency        string           `json:"currency"`
	ItemsMinor      int64            `json:"itemsMinor"`
	ShippingMinor   int64            `json:"shippingMinor"`
	TaxMinor        int64            `json:"taxMinor"`
	DiscountMinor   int64            `json:"discountMinor"`
	TotalMinor      int64            `json:"totalMinor"`
	ShipTo          string           `json:"shipTo"`
	ShippingAddress *OrderAddress    `json:"shippingAddress"`
	Shipments       []ShipmentDetail `json:"shipments"`
}

// ---------------------------------------------------------------- returns

// ReturnableLine is one delivered line and whether (and how) it can go back.
type ReturnableLine struct {
	LineID            string   `json:"lineId"`
	ATIN              string   `json:"atin"`
	Title             string   `json:"title"`
	Quantity          int      `json:"quantity"`
	MaxQuantity       int      `json:"maxQuantity"`
	UnitPriceMinor    int64    `json:"unitPriceMinor"`
	Eligible          bool     `json:"eligible"`
	ReportProblemOnly bool     `json:"reportProblemOnly"`
	SellerFaultOnly   bool     `json:"sellerFaultOnly"`
	WindowEndsAt      *string  `json:"windowEndsAt"`
	Methods           []string `json:"methods"`
	IneligibleReason  *string  `json:"ineligibleReason"`
}

// ReturnableShipment is one delivered shipment of an order.
type ReturnableShipment struct {
	ShipmentID  string           `json:"shipmentId"`
	SellerName  string           `json:"sellerName"`
	DeliveredAt *string          `json:"deliveredAt"`
	Lines       []ReturnableLine `json:"lines"`
}

// ReturnEligibility is what can be returned from an order.
type ReturnEligibility struct {
	OrderNumber string               `json:"orderNumber"`
	Currency    string               `json:"currency"`
	Shipments   []ReturnableShipment `json:"shipments"`
}

// ReturnLineView is one line of a return.
type ReturnLineView struct {
	LineID         string  `json:"lineId"`
	ATIN           string  `json:"atin"`
	Title          string  `json:"title"`
	Quantity       int     `json:"quantity"`
	UnitPriceMinor int64   `json:"unitPriceMinor"`
	Reason         string  `json:"reason"`
	Outcome        *string `json:"outcome"`
}

// RefundQuote is tax-returns' refund estimate.
type RefundQuote struct {
	ItemsMinor         int64 `json:"itemsMinor"`
	ShippingMinor      int64 `json:"shippingMinor"`
	TaxMinor           int64 `json:"taxMinor"`
	RestockingFeeMinor int64 `json:"restockingFeeMinor"`
	TotalMinor         int64 `json:"totalMinor"`
}

// ReturnDetail is one return with its lines and refund estimate.
type ReturnDetail struct {
	ReturnSummary
	Lines []ReturnLineView `json:"lines"`
	Quote *RefundQuote     `json:"quote"`
}

// ---------------------------------------------------------------- login & security

// MfaStatus is the account's second factors.
type MfaStatus struct {
	TOTP              bool    `json:"totp"`
	SMS               bool    `json:"sms"`
	SMSPhone          *string `json:"smsPhone"`
	RecoveryCodesLeft int     `json:"recoveryCodesLeft"`
	Required          bool    `json:"required"`
	MayDisable        bool    `json:"mayDisable"`
}

// Passkey is a registered passkey.
type Passkey struct {
	CredentialID string  `json:"credentialId"`
	Label        string  `json:"label"`
	CreatedAt    *string `json:"createdAt"`
	LastUsedAt   *string `json:"lastUsedAt"`
}

// SignedInSession is one place the customer is signed in.
type SignedInSession struct {
	ID           string  `json:"id"`
	ClientName   string  `json:"clientName"`
	SignedInAt   *string `json:"signedInAt"`
	LastActiveAt *string `json:"lastActiveAt"`
	Current      bool    `json:"current"`
}

// PushDevice is a device registered for push notifications.
type PushDevice struct {
	ID         string  `json:"id"`
	Platform   string  `json:"platform"`
	DeviceName string  `json:"deviceName"`
	LastSeenAt *string `json:"lastSeenAt"`
}

// LinkedAccount is a linked Google / Apple / Facebook sign-in.
type LinkedAccount struct {
	Provider  string  `json:"provider"`
	Email     *string `json:"email"`
	CanUnlink bool    `json:"canUnlink"`
}

// LinkedAccounts are the social sign-ins and whether a password is set.
type LinkedAccounts struct {
	Accounts    []LinkedAccount `json:"accounts"`
	HasPassword bool            `json:"hasPassword"`
}

// SecurityOverview is the Login & security page.
type SecurityOverview struct {
	MFA                    MfaStatus         `json:"mfa"`
	Passkeys               []Passkey         `json:"passkeys"`
	Sessions               []SignedInSession `json:"sessions"`
	Devices                []PushDevice      `json:"devices"`
	Linked                 LinkedAccounts    `json:"linkedAccounts"`
	PasskeyRegistrationURL string            `json:"passkeyRegistrationUrl"`
}

// TotpSetup starts authenticator-app enrolment.
type TotpSetup struct {
	Secret     string `json:"secret"`
	OtpauthURI string `json:"otpauthUri"`
}

// RecoveryCodes are shown once.
type RecoveryCodes struct {
	RecoveryCodes []string `json:"recoveryCodes"`
}

// NotificationPreferences are the customer's notification switches.
type NotificationPreferences struct {
	Email          bool `json:"email"`
	SMS            bool `json:"sms"`
	Push           bool `json:"push"`
	MarketingOptIn bool `json:"marketingOptIn"`
}

// PasswordChange changes the password.
type PasswordChange struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

// ---------------------------------------------------------------- services & bookings

// ServicePolicy is a provider's cancellation policy.
type ServicePolicy struct {
	Kind  string `json:"kind"`
	Label string `json:"label"`
	Text  string `json:"text"`
}

// ServiceListing is one search hit.
type ServiceListing struct {
	ServiceID       string   `json:"serviceId"`
	OrgID           string   `json:"orgId"`
	Name            string   `json:"name"`
	ProviderName    string   `json:"providerName"`
	Description     string   `json:"description"`
	Category        string   `json:"category"`
	DurationMinutes int      `json:"durationMinutes"`
	PriceFromMinor  int64    `json:"priceFromMinor"`
	DepositMinor    int64    `json:"depositMinor"`
	Currency        string   `json:"currency"`
	Modes           []string `json:"modes"`
	Rating          float64  `json:"rating"`
	RatingCount     int      `json:"ratingCount"`
	NextAvailable   *string  `json:"nextAvailable"`
	Location        string   `json:"location"`
}

// ServiceSearch is the services directory for a ZIP.
type ServiceSearch struct {
	ZIP      string           `json:"zip"`
	City     string           `json:"city"`
	Services []ServiceListing `json:"services"`
}

// ServiceAddOn is an optional extra.
type ServiceAddOn struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	PriceMinor   int64  `json:"priceMinor"`
	ExtraMinutes int    `json:"extraMinutes"`
}

// ServiceMember is a bookable practitioner.
type ServiceMember struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

// ServiceDetail is one bookable service.
type ServiceDetail struct {
	ID              string          `json:"id"`
	OrgID           string          `json:"orgId"`
	ProviderName    string          `json:"providerName"`
	Name            string          `json:"name"`
	Description     string          `json:"description"`
	Category        string          `json:"category"`
	DurationMinutes int             `json:"durationMinutes"`
	PriceMinor      int64           `json:"priceMinor"`
	DepositMinor    int64           `json:"depositMinor"`
	BalanceMinor    int64           `json:"balanceMinor"`
	Currency        string          `json:"currency"`
	Modes           []string        `json:"modes"`
	AddOns          []ServiceAddOn  `json:"addOns"`
	Policy          ServicePolicy   `json:"policy"`
	InstantBooking  bool            `json:"instantBooking"`
	Team            []ServiceMember `json:"team"`
}

// AvailableDay is a day with open slots.
type AvailableDay struct {
	Date  string `json:"date"`
	Slots int    `json:"slots"`
}

// AvailableSlot is a bookable start time.
type AvailableSlot struct {
	Start     string   `json:"start"`
	End       string   `json:"end"`
	MemberIDs []string `json:"memberIds"`
}

// BookingQuote is what booking costs now and later.
type BookingQuote struct {
	ServiceID                    string   `json:"serviceId"`
	ServiceName                  string   `json:"serviceName"`
	ProviderName                 string   `json:"providerName"`
	DurationMinutes              int      `json:"durationMinutes"`
	PriceMinor                   int64    `json:"priceMinor"`
	DepositNowMinor              int64    `json:"depositNowMinor"`
	BalanceLaterMinor            int64    `json:"balanceLaterMinor"`
	Currency                     string   `json:"currency"`
	Mode                         string   `json:"mode"`
	AddOns                       []string `json:"addOns"`
	Policy                       string   `json:"policy"`
	PolicyText                   string   `json:"policyText"`
	InstantBooking               bool     `json:"instantBooking"`
	ProviderAnswersWithinMinutes *int64   `json:"providerAnswersWithinMinutes"`
}

// ProviderSocialLink is an approved social link on a provider page.
type ProviderSocialLink struct {
	Network string `json:"network"`
	URL     string `json:"url"`
}

// ProviderGalleryItem is one gallery picture.
type ProviderGalleryItem struct {
	URL     string `json:"url"`
	Caption string `json:"caption"`
}

// ProviderFeature is a published featured-work post.
type ProviderFeature struct {
	ID        string  `json:"id"`
	Headline  string  `json:"headline"`
	Body      string  `json:"body"`
	URL       *string `json:"url"`
	ServiceID *string `json:"serviceId"`
}

// ProviderDesign is the provider's published page (storefront service): layout, header, theme tokens, approved
// links, gallery and featured work.
type ProviderDesign struct {
	Slug        string                `json:"slug"`
	Tagline     string                `json:"tagline"`
	Layout      string                `json:"layout"`
	Header      string                `json:"header"`
	ButtonShape string                `json:"buttonShape"`
	CoverURL    *string               `json:"coverUrl"`
	Tokens      map[string]string     `json:"tokens"`
	Social      []ProviderSocialLink  `json:"social"`
	Gallery     []ProviderGalleryItem `json:"gallery"`
	Featured    []ProviderFeature     `json:"featured"`
}

// ProviderPage is a provider's public page: bookings profile and services plus the designed page when published.
type ProviderPage struct {
	OrgID          string          `json:"orgId"`
	DisplayName    string          `json:"displayName"`
	TimeZone       string          `json:"timeZone"`
	Location       string          `json:"location"`
	Policy         ServicePolicy   `json:"policy"`
	InstantBooking bool            `json:"instantBooking"`
	Team           []ServiceMember `json:"team"`
	Services       []ServiceDetail `json:"services"`
	Design         *ProviderDesign `json:"design"`
}

// BookedService is the service as booked.
type BookedService struct {
	ServiceID       string   `json:"serviceId"`
	Name            string   `json:"name"`
	DurationMinutes int      `json:"durationMinutes"`
	PriceMinor      int64    `json:"priceMinor"`
	DepositMinor    int64    `json:"depositMinor"`
	BalanceMinor    int64    `json:"balanceMinor"`
	Currency        string   `json:"currency"`
	Mode            string   `json:"mode"`
	ProviderName    string   `json:"providerName"`
	MemberName      string   `json:"memberName"`
	AddOns          []string `json:"addOns"`
}

// BookingPayment is the booking's money state.
type BookingPayment struct {
	State                string `json:"state"`
	DepositCapturedMinor int64  `json:"depositCapturedMinor"`
	RefundedMinor        int64  `json:"refundedMinor"`
	BalanceState         string `json:"balanceState"`
}

// CustomerBooking is one of the customer's bookings.
type CustomerBooking struct {
	ID              string         `json:"id"`
	Reference       string         `json:"reference"`
	OrgID           string         `json:"orgId"`
	Status          string         `json:"status"`
	StatusLabel     string         `json:"statusLabel"`
	StartAt         string         `json:"startAt"`
	EndAt           string         `json:"endAt"`
	ProposedStartAt *string        `json:"proposedStartAt"`
	Location        string         `json:"location"`
	Notes           string         `json:"notes"`
	Service         BookedService  `json:"service"`
	Policy          ServicePolicy  `json:"policy"`
	Payment         BookingPayment `json:"payment"`
	CanCancel       bool           `json:"canCancel"`
	CanReschedule   bool           `json:"canReschedule"`
	CanReview       bool           `json:"canReview"`
	AwaitingPayment bool           `json:"awaitingPayment"`
}

// BookingRequest books a service.
type BookingRequest struct {
	ServiceID string   `json:"serviceId"`
	MemberID  string   `json:"memberId"`
	StartAt   string   `json:"startAt"`
	Mode      string   `json:"mode"`
	AddOnIDs  []string `json:"addOnIds"`
	Location  string   `json:"location"`
	Notes     string   `json:"notes"`
}

// DepositPayment is what Stripe.js needs to confirm the deposit hold.
type DepositPayment struct {
	ClientSecret   string `json:"clientSecret"`
	PublishableKey string `json:"publishableKey"`
}

// BookingCreated is a new booking and, when a deposit is due, the client secret to confirm it.
type BookingCreated struct {
	Booking CustomerBooking `json:"booking"`
	Payment *DepositPayment `json:"payment"`
}

// CancellationQuote is exactly what cancelling now refunds or keeps.
type CancellationQuote struct {
	RefundSharePercent int    `json:"refundSharePercent"`
	DepositMinor       int64  `json:"depositMinor"`
	RefundMinor        int64  `json:"refundMinor"`
	KeptMinor          int64  `json:"keptMinor"`
	ReleaseHold        bool   `json:"releaseHold"`
	Policy             string `json:"policy"`
	Explanation        string `json:"explanation"`
	Currency           string `json:"currency"`
}

// BookingCancelled is the cancelled booking and the refund applied.
type BookingCancelled struct {
	Booking CustomerBooking   `json:"booking"`
	Applied CancellationQuote `json:"applied"`
}

// BookingReview is a submitted review.
type BookingReview struct {
	BookingID string `json:"bookingId"`
	Rating    int    `json:"rating"`
	Text      string `json:"text"`
}

// ---------------------------------------------------------------- Lola

// LolaChatRequest is one shopper message (relayed to ai-gateway).
type LolaChatRequest struct {
	SessionID string `json:"sessionId,omitempty"`
	Message   string `json:"message"`
	ZIP       string `json:"zip,omitempty"`
	Language  string `json:"language,omitempty"`
}

// LolaTurn is one stored (redacted) turn of a Lola conversation.
type LolaTurn struct {
	Role    string `json:"role"`
	Content string `json:"content"`
	At      string `json:"at"`
}

// ---------------------------------------------------------------- tracking & PaymentSheet (customer app, S238)

// TrackedItem is one item of a tracked shipment.
type TrackedItem struct {
	ATIN       string `json:"atin"`
	Title      string `json:"title"`
	Quantity   int    `json:"quantity"`
	PriceMinor int64  `json:"priceMinor"`
	Returnable bool   `json:"returnable"`
}

// TrackingDriver is the AfroTransact Delivery driver on the way.
type TrackingDriver struct {
	Name     string `json:"name"`
	Initials string `json:"initials"`
}

// TrackedShipment is one shipment with its timeline (orders) and carrier scans (carrier-gateway).
type TrackedShipment struct {
	ShipmentID     string          `json:"shipmentId"`
	Label          string          `json:"label"`
	Status         string          `json:"status"`
	ETA            *string         `json:"eta"`
	Method         string          `json:"method"`
	Handover       *string         `json:"handover"`
	Perishable     bool            `json:"perishable"`
	DeliveryCode   *string         `json:"deliveryCode"`
	Driver         *TrackingDriver `json:"driver"`
	Carrier        *string         `json:"carrier"`
	TrackingNumber *string         `json:"trackingNumber"`
	Items          []TrackedItem   `json:"items"`
	Timeline       []TimelineEvent `json:"timeline"`
}

// OrderTracking is an order's shipments with their tracking timelines.
type OrderTracking struct {
	OrderNumber string            `json:"orderNumber"`
	PlacedAt    string            `json:"placedAt"`
	TotalMinor  int64             `json:"totalMinor"`
	Currency    string            `json:"currency"`
	Shipments   []TrackedShipment `json:"shipments"`
}

// PaymentSheetKeys let Stripe PaymentSheet (customer app) list and reuse saved methods.
type PaymentSheetKeys struct {
	CustomerID     string `json:"customerId"`
	EphemeralKey   string `json:"ephemeralKey"`
	PublishableKey string `json:"publishableKey"`
}

// DeliveryPreferences are the customer's hand-over wishes for AfroTransact Delivery.
type DeliveryPreferences struct {
	Handover     string `json:"handover"`
	SafePlace    string `json:"safePlace"`
	Instructions string `json:"instructions"`
	Contact      string `json:"contact"`
}

// PlaceOrderRequestSelection is one package's delivery choice.
type PlaceOrderRequestSelection struct {
	PackageID     string               `json:"packageId"`
	Option        string               `json:"option"`
	PickupPointID string               `json:"pickupPointId"`
	Preferences   *DeliveryPreferences `json:"preferences"`
}

// Selections decodes the delivery selections; entries that do not decode come back empty (and fail validation).
func (r PlaceOrderRequest) Selections() []PlaceOrderRequestSelection {
	out := make([]PlaceOrderRequestSelection, 0, len(r.DeliverySelections))
	for _, raw := range r.DeliverySelections {
		var s PlaceOrderRequestSelection
		_ = json.Unmarshal(raw, &s)
		out = append(out, s)
	}
	return out
}

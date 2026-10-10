package port

import (
	"context"
	"io"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Storefront-side upstreams of the shopper's account, checkout, services and Lola. Every call relays the
// customer's own access token (bearer) unless it reads public data.

// ---------------------------------------------------------------- payments (contracts/openapi/payments.yaml)

// WalletMethod is payments' PaymentMethodResponse (display data only).
type WalletMethod struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	Brand       string `json:"brand"`
	Last4       string `json:"last4"`
	ExpMonth    int    `json:"expMonth"`
	ExpYear     int    `json:"expYear"`
	BankName    string `json:"bankName"`
	AccountType string `json:"accountType"`
	Verified    *bool  `json:"verified"`
	IsDefault   bool   `json:"isDefault"`
}

// WalletSetupIntent is payments' SetupIntentResponse.
type WalletSetupIntent struct {
	SetupIntentID string `json:"setupIntentId"`
	ClientSecret  string `json:"clientSecret"`
	Status        string `json:"status"`
}

// WalletEphemeralKey is a Stripe customer ephemeral key for PaymentSheet.
type WalletEphemeralKey struct {
	CustomerID string `json:"customerId"`
	Secret     string `json:"secret"`
}

// WalletClient manages the shopper's saved Stripe payment methods (/v1/customers/me/...).
type WalletClient interface {
	List(ctx context.Context, bearer string) ([]WalletMethod, error)
	SetupIntent(ctx context.Context, bearer string, types []string) (WalletSetupIntent, error)
	MakeDefault(ctx context.Context, bearer, id string) (WalletMethod, error)
	Remove(ctx context.Context, bearer, id string) error
	// EphemeralKey is the planned POST /v1/customers/me/ephemeral-keys (payments does not serve it yet).
	EphemeralKey(ctx context.Context, bearer string) (WalletEphemeralKey, error)
}

// ---------------------------------------------------------------- orders (contracts/openapi/orders.yaml)

// UpOrderLine is orders' LineView.
type UpOrderLine struct {
	ID             string `json:"id"`
	VariantATIN    string `json:"variantAtin"`
	ProductATIN    string `json:"productAtin"`
	Title          string `json:"title"`
	ImageURL       string `json:"imageUrl"`
	Quantity       int    `json:"quantity"`
	UnitPriceMinor int64  `json:"unitPriceMinor"`
	ItemsMinor     int64  `json:"itemsMinor"`
	Perishable     bool   `json:"perishable"`
}

// UpTimelineEntry is orders' TimelineEntry.
type UpTimelineEntry struct {
	From string `json:"from"`
	To   string `json:"to"`
	At   string `json:"at"`
}

// UpPreferences is orders' DeliveryPreferences (upper-case enums).
type UpPreferences struct {
	Handover     string `json:"handover,omitempty"`
	SafePlace    string `json:"safePlace,omitempty"`
	Instructions string `json:"instructions,omitempty"`
	Contact      string `json:"contact,omitempty"`
}

// UpShipment is orders' ShipmentView.
type UpShipment struct {
	ID               string            `json:"id"`
	ShipmentID       string            `json:"shipmentId"`
	Label            string            `json:"label"`
	SellerName       string            `json:"sellerName"`
	FulfilmentType   string            `json:"fulfilmentType"`
	DeliveryMethod   string            `json:"deliveryMethod"`
	ServiceLevel     string            `json:"serviceLevel"`
	Preferences      *UpPreferences    `json:"preferences"`
	Perishable       bool              `json:"perishable"`
	HandoverRequired bool              `json:"handoverRequired"`
	HandoverCode     string            `json:"handoverCode"`
	Status           string            `json:"status"`
	Items            string            `json:"items"`
	Carrier          string            `json:"carrier"`
	TrackingNumber   string            `json:"trackingNumber"`
	PromisedFrom     string            `json:"promisedFrom"`
	PromisedBy       string            `json:"promisedBy"`
	DeliveredAt      string            `json:"deliveredAt"`
	Cancellable      bool              `json:"cancellable"`
	Lines            []UpOrderLine     `json:"lines"`
	Timeline         []UpTimelineEntry `json:"timeline"`
}

// UpShipTo is orders' ShipTo snapshot.
type UpShipTo struct {
	AddressID  string `json:"addressId"`
	FullName   string `json:"fullName"`
	Phone      string `json:"phone"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
}

// UpOrder is orders' OrderView.
type UpOrder struct {
	ID              string       `json:"id"`
	OrderNumber     string       `json:"orderNumber"`
	Status          string       `json:"status"`
	PlacedAt        string       `json:"placedAt"`
	CreatedAt       string       `json:"createdAt"`
	Currency        string       `json:"currency"`
	ItemsMinor      int64        `json:"itemsMinor"`
	ShippingMinor   int64        `json:"shippingMinor"`
	TaxMinor        int64        `json:"taxMinor"`
	DiscountMinor   int64        `json:"discountMinor"`
	TotalMinor      int64        `json:"totalMinor"`
	ShipTo          string       `json:"shipTo"`
	ShippingAddress *UpShipTo    `json:"shippingAddress"`
	Shipments       []UpShipment `json:"shipments"`
}

// UpCheckoutShipment is orders' checkout Shipment.
type UpCheckoutShipment struct {
	ShipmentID     string `json:"shipmentId"`
	Label          string `json:"label"`
	ETA            string `json:"eta"`
	PromisedBy     string `json:"promisedBy"`
	DeliveryMethod string `json:"deliveryMethod"`
	DeliveryCode   string `json:"deliveryCode"`
}

// UpCheckout is orders' CheckoutView.
type UpCheckout struct {
	OrderID             string               `json:"orderId"`
	OrderNumber         string               `json:"orderNumber"`
	Status              string               `json:"status"`
	ClientSecret        string               `json:"clientSecret"`
	PaymentClientSecret string               `json:"paymentClientSecret"`
	TotalMinor          int64                `json:"totalMinor"`
	Currency            string               `json:"currency"`
	Shipments           []UpCheckoutShipment `json:"shipments"`
}

// UpDeliverySelection is orders' DeliverySelection.
type UpDeliverySelection struct {
	PackageID     string         `json:"packageId"`
	Option        string         `json:"option"`
	PickupPointID string         `json:"pickupPointId,omitempty"`
	Preferences   *UpPreferences `json:"preferences,omitempty"`
}

// UpCheckoutRequest is orders' CheckoutRequest.
type UpCheckoutRequest struct {
	AddressID          string                `json:"addressId"`
	DeliverySelections []UpDeliverySelection `json:"deliverySelections"`
	Lines              []OrderLine           `json:"lines,omitempty"`
	PaymentMethodID    string                `json:"paymentMethodId,omitempty"`
	Wallet             string                `json:"wallet,omitempty"`
	Gift               bool                  `json:"gift,omitempty"`
	CouponCode         string                `json:"couponCode,omitempty"`
}

// CustomerOrdersClient calls orders with the customer's token: Your orders, cancel, checkout saga.
type CustomerOrdersClient interface {
	List(ctx context.Context, bearer, cursor string, limit int) ([]UpOrder, *string, error)
	Get(ctx context.Context, bearer, number string) (UpOrder, error)
	Cancel(ctx context.Context, bearer, number, shipmentID, reason string) (UpOrder, error)
	// Checkout starts the saga (POST /v1/checkout, Idempotency-Key); status is 201 or 202.
	Checkout(ctx context.Context, bearer, key string, req UpCheckoutRequest) (UpCheckout, int, error)
	CheckoutStatus(ctx context.Context, bearer, number string) (UpCheckout, error)
	ConfirmPayment(ctx context.Context, bearer, number string) (UpCheckout, error)
}

// ---------------------------------------------------------------- tax-returns (contracts/openapi/tax-returns.yaml)

// UpRmaLine is tax-returns' RmaLine.
type UpRmaLine struct {
	LineID         string `json:"lineId"`
	ATIN           string `json:"atin"`
	Title          string `json:"title"`
	Quantity       int    `json:"quantity"`
	UnitPriceMinor int64  `json:"unitPriceMinor"`
	Reason         string `json:"reason"`
	Outcome        string `json:"outcome"`
}

// UpRma is tax-returns' Rma.
type UpRma struct {
	ID            string      `json:"id"`
	Number        string      `json:"number"`
	OrderID       string      `json:"orderId"`
	OrderNumber   string      `json:"orderNumber"`
	ShipmentID    string      `json:"shipmentId"`
	Method        string      `json:"method"`
	Status        string      `json:"status"`
	RefundState   string      `json:"refundState"`
	Currency      string      `json:"currency"`
	EstimateMinor int64       `json:"estimateMinor"`
	Carrier       string      `json:"carrier"`
	Tracking      string      `json:"trackingNumber"`
	LabelURL      string      `json:"labelUrl"`
	QRPayload     string      `json:"qrPayload"`
	HandoverCode  string      `json:"handoverCode"`
	DropOffBy     string      `json:"dropOffBy"`
	Cancellable   bool        `json:"cancellable"`
	Lines         []UpRmaLine `json:"lines"`
	CreatedAt     string      `json:"createdAt"`
}

// UpLineEligibility is tax-returns' LineEligibility.
type UpLineEligibility struct {
	LineID            string   `json:"lineId"`
	ATIN              string   `json:"atin"`
	Title             string   `json:"title"`
	Quantity          int      `json:"quantity"`
	UnitPriceMinor    int64    `json:"unitPriceMinor"`
	Eligible          bool     `json:"eligible"`
	ReportProblemOnly bool     `json:"reportProblemOnly"`
	SellerFaultOnly   bool     `json:"sellerFaultOnly"`
	MaxQuantity       int      `json:"maxQuantity"`
	WindowEndsAt      string   `json:"windowEndsAt"`
	Methods           []string `json:"methods"`
	IneligibleCode    string   `json:"ineligibleCode"`
}

// UpShipmentEligibility is tax-returns' ShipmentEligibility.
type UpShipmentEligibility struct {
	ShipmentID  string              `json:"shipmentId"`
	SellerName  string              `json:"sellerName"`
	DeliveredAt string              `json:"deliveredAt"`
	Lines       []UpLineEligibility `json:"lines"`
}

// UpReturnEligibility is tax-returns' ReturnEligibility.
type UpReturnEligibility struct {
	OrderID     string                  `json:"orderId"`
	OrderNumber string                  `json:"orderNumber"`
	Currency    string                  `json:"currency"`
	Shipments   []UpShipmentEligibility `json:"shipments"`
}

// UpReturnAddress is tax-returns' Address (returnFrom).
type UpReturnAddress struct {
	Name       string `json:"name,omitempty"`
	Phone      string `json:"phone,omitempty"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2,omitempty"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
}

// UpCreateReturnLine is tax-returns' CreateReturnLine.
type UpCreateReturnLine struct {
	LineID   string `json:"lineId"`
	Quantity int    `json:"quantity"`
	Reason   string `json:"reason"`
	Comment  string `json:"comment,omitempty"`
}

// UpCreateReturn is tax-returns' CreateReturn.
type UpCreateReturn struct {
	OrderID    string               `json:"orderId"`
	ShipmentID string               `json:"shipmentId"`
	Method     string               `json:"method"`
	Lines      []UpCreateReturnLine `json:"lines"`
	ReturnFrom *UpReturnAddress     `json:"returnFrom,omitempty"`
}

// ReturnsClient calls tax-returns with the customer's token.
type ReturnsClient interface {
	Eligibility(ctx context.Context, bearer, orderID string) (UpReturnEligibility, error)
	Create(ctx context.Context, bearer, key string, req UpCreateReturn) (UpRma, error)
	List(ctx context.Context, bearer string) ([]UpRma, error)
	Get(ctx context.Context, bearer, id string) (UpRma, error)
	Cancel(ctx context.Context, bearer, id string) (UpRma, error)
	Quote(ctx context.Context, bearer, id string) (domain.RefundQuote, error)
}

// ---------------------------------------------------------------- identity /v1/me security (contracts/openapi/identity.yaml)

// UpMfa is identity's MfaStatus.
type UpMfa struct {
	TOTP              bool   `json:"totp"`
	SMS               bool   `json:"sms"`
	SMSPhone          string `json:"smsPhone"`
	RecoveryCodesLeft int    `json:"recoveryCodesLeft"`
	Required          bool   `json:"required"`
	MayDisable        bool   `json:"mayDisable"`
}

// UpPasskey is identity's Passkey.
type UpPasskey struct {
	CredentialID string `json:"credentialId"`
	Label        string `json:"label"`
	CreatedAt    string `json:"createdAt"`
	LastUsedAt   string `json:"lastUsedAt"`
}

// UpSession is identity's Session.
type UpSession struct {
	ID           string `json:"id"`
	ClientID     string `json:"clientId"`
	ClientName   string `json:"clientName"`
	SignedInAt   string `json:"signedInAt"`
	LastActiveAt string `json:"lastActiveAt"`
	Current      bool   `json:"current"`
}

// UpDevice is identity's Device.
type UpDevice struct {
	ID         string `json:"id"`
	Platform   string `json:"platform"`
	DeviceName string `json:"deviceName"`
	LastSeenAt string `json:"lastSeenAt"`
}

// UpLinkedAccounts is identity's LinkedAccounts.
type UpLinkedAccounts struct {
	Accounts []struct {
		Provider  string `json:"provider"`
		Email     string `json:"email"`
		CanUnlink bool   `json:"canUnlink"`
	} `json:"accounts"`
	HasPassword bool `json:"hasPassword"`
}

// SecurityClient manages the signed-in customer's sign-in methods, sessions, devices and notification switches.
type SecurityClient interface {
	MFA(ctx context.Context, bearer string) (UpMfa, error)
	Passkeys(ctx context.Context, bearer string) ([]UpPasskey, error)
	Sessions(ctx context.Context, bearer string) ([]UpSession, error)
	Devices(ctx context.Context, bearer string) ([]UpDevice, error)
	Linked(ctx context.Context, bearer string) (UpLinkedAccounts, error)
	Unlink(ctx context.Context, bearer, provider string) (UpLinkedAccounts, error)
	ChangePassword(ctx context.Context, bearer string, in domain.PasswordChange) error
	StartTOTP(ctx context.Context, bearer string) (domain.TotpSetup, error)
	ConfirmTOTP(ctx context.Context, bearer, code string) (domain.RecoveryCodes, error)
	DisableTOTP(ctx context.Context, bearer string) error
	StartSMS(ctx context.Context, bearer string) (string, error)
	ConfirmSMS(ctx context.Context, bearer, code string) (domain.RecoveryCodes, error)
	DisableSMS(ctx context.Context, bearer string) error
	RecoveryCodes(ctx context.Context, bearer string) (domain.RecoveryCodes, error)
	RemovePasskey(ctx context.Context, bearer, id string) error
	RevokeSession(ctx context.Context, bearer, id string) error
	RevokeOtherSessions(ctx context.Context, bearer string) (int, error)
	RemoveDevice(ctx context.Context, bearer, id string) error
	Preferences(ctx context.Context, bearer string) (domain.NotificationPreferences, error)
	SavePreferences(ctx context.Context, bearer string, p domain.NotificationPreferences) (domain.NotificationPreferences, error)
}

// ---------------------------------------------------------------- bookings public + customer (contracts/openapi/bookings.yaml)

// UpBkPolicy is bookings' PublicPolicy / BookingPolicy.
type UpBkPolicy struct {
	Kind  string `json:"kind"`
	Label string `json:"label"`
	Text  string `json:"text"`
}

// UpBkMember is bookings' PublicTeamMember.
type UpBkMember struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

// UpBkService is bookings' PublicService.
type UpBkService struct {
	ID              string                `json:"id"`
	OrgID           string                `json:"orgId"`
	ProviderName    string                `json:"providerName"`
	Name            string                `json:"name"`
	Description     string                `json:"description"`
	Category        string                `json:"category"`
	DurationMinutes int                   `json:"durationMinutes"`
	PriceMinor      int64                 `json:"priceMinor"`
	DepositMinor    int64                 `json:"depositMinor"`
	BalanceMinor    int64                 `json:"balanceMinor"`
	Currency        string                `json:"currency"`
	Modes           []string              `json:"modes"`
	AddOns          []domain.ServiceAddOn `json:"addOns"`
	Policy          UpBkPolicy            `json:"policy"`
	InstantBooking  bool                  `json:"instantBooking"`
	Team            []UpBkMember          `json:"team"`
}

// UpBkProvider is bookings' PublicProvider.
type UpBkProvider struct {
	OrgID          string        `json:"orgId"`
	DisplayName    string        `json:"displayName"`
	TimeZone       string        `json:"timeZone"`
	Location       string        `json:"location"`
	Policy         UpBkPolicy    `json:"policy"`
	InstantBooking bool          `json:"instantBooking"`
	Services       []UpBkService `json:"services"`
	Team           []UpBkMember  `json:"team"`
}

// UpBkListing is bookings' PublicServiceListing.
type UpBkListing struct {
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
	NextAvailable   string   `json:"nextAvailable"`
	Location        string   `json:"location"`
}

// UpBkBooking is bookings' Booking (customer view).
type UpBkBooking struct {
	ID              string `json:"id"`
	Reference       string `json:"reference"`
	OrgID           string `json:"orgId"`
	Status          string `json:"status"`
	StatusLabel     string `json:"statusLabel"`
	StartAt         string `json:"startAt"`
	EndAt           string `json:"endAt"`
	ProposedStartAt string `json:"proposedStartAt"`
	Location        string `json:"location"`
	Notes           string `json:"notes"`
	Service         struct {
		ServiceID       string   `json:"serviceId"`
		Name            string   `json:"name"`
		DurationMinutes int      `json:"durationMinutes"`
		PriceMinor      int64    `json:"priceMinor"`
		DepositMinor    int64    `json:"depositMinor"`
		BalanceMinor    int64    `json:"balanceMinor"`
		Currency        string   `json:"currency"`
		AddOns          []string `json:"addOns"`
		Mode            string   `json:"mode"`
		ProviderName    string   `json:"providerName"`
		MemberName      string   `json:"memberName"`
	} `json:"service"`
	Policy  UpBkPolicy `json:"policy"`
	Payment struct {
		State                string `json:"state"`
		ClientSecret         string `json:"clientSecret"`
		DepositCapturedMinor int64  `json:"depositCapturedMinor"`
		RefundedMinor        int64  `json:"refundedMinor"`
		Balance              *struct {
			State string `json:"state"`
		} `json:"balance"`
	} `json:"payment"`
}

// UpBkQuote is bookings' CancellationQuote.
type UpBkQuote struct {
	RefundSharePercent   int    `json:"refundSharePercent"`
	DepositMinor         int64  `json:"depositMinor"`
	RefundMinor          int64  `json:"refundMinor"`
	ReleaseAuthorisation bool   `json:"releaseAuthorisation"`
	KeptMinor            int64  `json:"keptMinor"`
	Policy               string `json:"policy"`
	Explanation          string `json:"explanation"`
	Currency             string `json:"currency"`
}

// UpBkCreate is bookings' CreateBookingRequest.
type UpBkCreate struct {
	ServiceID string   `json:"serviceId"`
	MemberID  string   `json:"memberId,omitempty"`
	StartAt   string   `json:"startAt"`
	Mode      string   `json:"mode"`
	AddOnIDs  []string `json:"addOnIds,omitempty"`
	Customer  struct {
		Name  string `json:"name"`
		Email string `json:"email,omitempty"`
		Phone string `json:"phone,omitempty"`
	} `json:"customer"`
	Location string `json:"location,omitempty"`
	Notes    string `json:"notes,omitempty"`
	Channel  string `json:"channel"`
}

// SlotQuery narrows days, slots and the booking summary.
type SlotQuery struct {
	From     string
	Days     int
	Date     string
	MemberID string
	AddOnIDs []string
	Mode     string
}

// CustomerBookingsClient calls bookings' public directory and the customer's own bookings.
type CustomerBookingsClient interface {
	Search(ctx context.Context, q, category, zip string) ([]UpBkListing, error)
	Service(ctx context.Context, id string) (UpBkService, error)
	Provider(ctx context.Context, orgID string) (UpBkProvider, error)
	Days(ctx context.Context, serviceID string, q SlotQuery) ([]domain.AvailableDay, error)
	Slots(ctx context.Context, serviceID string, q SlotQuery) ([]domain.AvailableSlot, error)
	Summary(ctx context.Context, serviceID string, q SlotQuery) (domain.BookingQuote, error)
	Create(ctx context.Context, bearer, key string, req UpBkCreate) (UpBkBooking, error)
	Mine(ctx context.Context, bearer string) ([]UpBkBooking, error)
	Get(ctx context.Context, bearer, id string) (UpBkBooking, error)
	Quote(ctx context.Context, bearer, id string) (UpBkQuote, error)
	Cancel(ctx context.Context, bearer, key, id, reason string) (UpBkBooking, UpBkQuote, error)
	Reschedule(ctx context.Context, bearer, key, id, startAt string) (UpBkBooking, error)
	NewTime(ctx context.Context, bearer, key, id string, accept bool) (UpBkBooking, error)
	Review(ctx context.Context, bearer, id string, rating int, text string) (domain.BookingReview, error)
	SyncPayment(ctx context.Context, bearer, id string) (UpBkBooking, error)
}

// ---------------------------------------------------------------- storefront published pages (contracts/openapi/storefront.yaml)

// UpPublishedPage is the storefront service's public Storefront.
type UpPublishedPage struct {
	OrgID    string            `json:"orgId"`
	Kind     string            `json:"kind"`
	Slug     string            `json:"slug"`
	Name     string            `json:"name"`
	Tagline  string            `json:"tagline"`
	Tokens   map[string]string `json:"tokens"`
	Provider *struct {
		Layout      string `json:"layout"`
		Header      string `json:"header"`
		CoverURL    string `json:"coverUrl"`
		ButtonShape string `json:"buttonShape"`
	} `json:"provider"`
	Gallery []struct {
		URL     string `json:"url"`
		Caption string `json:"caption"`
	} `json:"gallery"`
	Featured []struct {
		ID        string `json:"id"`
		Headline  string `json:"headline"`
		Body      string `json:"body"`
		URL       string `json:"url"`
		ServiceID string `json:"serviceId"`
		Published *bool  `json:"published"`
	} `json:"featured"`
	Social []struct {
		Network string `json:"network"`
		URL     string `json:"url"`
		Status  string `json:"status"`
	} `json:"social"`
}

// PublishedPagesClient reads published storefront pages by slug (approved links only).
type PublishedPagesClient interface {
	Published(ctx context.Context, slug string) (UpPublishedPage, error)
}

// ---------------------------------------------------------------- ai-gateway (contracts/openapi/ai-gateway.yaml)

// LolaClient streams Lola turns (SSE) and reads stored conversations, always with the shopper's token.
type LolaClient interface {
	// Chat starts POST /v1/lola/chat and returns the open event stream; the caller closes it.
	Chat(ctx context.Context, bearer string, req domain.LolaChatRequest) (io.ReadCloser, error)
	History(ctx context.Context, bearer, sessionID string) ([]domain.LolaTurn, error)
}

// ---------------------------------------------------------------- delivery facts for checkout

// PromiseOffer asks promise for one package option (POST /v1/promises, contracts/openapi/promise.yaml).
type PromiseOffer struct {
	OfferID string         `json:"offerId"`
	Origin  map[string]any `json:"origin"`
	Method  map[string]any `json:"method"`
}

// PromiseQuote is one promise.
type PromiseQuote struct {
	OfferID      string `json:"offerId"`
	Available    bool   `json:"available"`
	EarliestDate string `json:"earliestDate"`
	LatestDate   string `json:"latestDate"`
	CutoffLabel  string `json:"cutoffLabel"`
	ReadyAt      string `json:"readyAt"`
	Label        string `json:"label"`
}

// PickupLocation is carrier-gateway's PickupLocation.
type PickupLocation struct {
	ID       string `json:"id"`
	Kind     string `json:"kind"`
	OwnerOrg string `json:"ownerOrg"`
	Name     string `json:"name"`
	Address  struct {
		Line1      string `json:"line1"`
		City       string `json:"city"`
		State      string `json:"state"`
		PostalCode string `json:"postalCode"`
	} `json:"address"`
	Timezone string `json:"timezone"`
	Hours    []struct {
		Weekday int    `json:"weekday"`
		Open    string `json:"open"`
		Close   string `json:"close"`
	} `json:"hours"`
	ReadyHours int  `json:"readyHours"`
	Active     bool `json:"active"`
}

// DeliveryZone is a geo zone covering a point.
type DeliveryZone struct {
	Type    string `json:"type"`
	OwnerID string `json:"ownerId"`
	Name    string `json:"name"`
}

// CarrierScan is one carrier tracking event.
type CarrierScan struct {
	Description string `json:"description"`
	State       string `json:"state"`
	OccurredAt  string `json:"occurredAt"`
	Location    string `json:"location"`
}

// DeliveryFactsClient gathers what checkout and tracking need from promise, carrier-gateway and geo.
type DeliveryFactsClient interface {
	Promises(ctx context.Context, zip string, offers []PromiseOffer) ([]PromiseQuote, error)
	PickupLocations(ctx context.Context, ownerOrg, postalPrefix string) ([]PickupLocation, error)
	ZonesCovering(ctx context.Context, lat, lon float64, zoneType string) ([]DeliveryZone, error)
	// Tracking needs shipping:rate (a gateway-bff service token).
	Tracking(ctx context.Context, bearer, carrier, number string) ([]CarrierScan, error)
}

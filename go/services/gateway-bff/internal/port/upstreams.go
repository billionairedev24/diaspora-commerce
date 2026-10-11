package port

import (
	"context"
	"encoding/json"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- search (contracts/openapi/search.yaml)

// SearchParams is a search-service query.
type SearchParams struct {
	Q          string
	Market     string
	Department string
	Community  string
	Fulfilment string
	SellerKind string
	MinRating  *float64
	MinPrice   int64
	MaxPrice   int64
	MaxDays    int
	InStock    bool
	Sort       string
	Page       int
	Size       int
	// Ads blends sponsored products into the page (results pages only; S240).
	Ads bool
	// NoTrack keeps machine-made queries (home rails, Lola) out of search analytics (track=false).
	NoTrack bool
}

// SearchHit is a search-service product card.
type SearchHit struct {
	ATIN         string  `json:"atin"`
	Title        string  `json:"title"`
	Brand        string  `json:"brand"`
	CategoryPath string  `json:"categoryPath"`
	SellerName   string  `json:"sellerName"`
	SellerKind   string  `json:"sellerKind"`
	Fulfilment   string  `json:"fulfilment"`
	PriceMinor   int64   `json:"priceMinor"`
	Currency     string  `json:"currency"`
	Rating       float64 `json:"rating"`
	ReviewCount  int     `json:"reviewCount"`
	InStock      bool    `json:"inStock"`
	LowStock     bool    `json:"lowStock"`
	AvailableQty int     `json:"availableQty"`
	DeliveryDays int     `json:"deliveryDays"`
	ImageURL     string  `json:"imageUrl"`
	OfferCount   int     `json:"offerCount"`
	// Sponsored is present on ads only (S240).
	Sponsored *SearchSponsored `json:"sponsored,omitempty"`
}

// SearchFacetBucket is a search-service facet value.
type SearchFacetBucket struct {
	Value string `json:"value"`
	Count int64  `json:"count"`
}

// SearchResult is a search-service page.
type SearchResult struct {
	Hits   []SearchHit                    `json:"hits"`
	Total  int64                          `json:"total"`
	Page   int                            `json:"page"`
	Size   int                            `json:"size"`
	Facets map[string][]SearchFacetBucket `json:"facets"`
	TookMs int64                          `json:"tookMs"`
	// SearchID identifies the search for click reporting.
	SearchID string `json:"searchId"`
}

// SuggestResult is the search-service typeahead.
type SuggestResult struct {
	Phrases []struct {
		Text       string `json:"text"`
		Department string `json:"department"`
	} `json:"phrases"`
	Products []SearchHit `json:"products"`
}

// SearchClient reads the search service.
type SearchClient interface {
	Search(ctx context.Context, p SearchParams) (SearchResult, error)
	Suggest(ctx context.Context, q, market string) (SuggestResult, error)
}

// ---------------------------------------------------------------- catalog (public reads)

// CatalogVariant is a sellable variant.
type CatalogVariant struct {
	ATIN       string            `json:"atin"`
	Options    map[string]string `json:"options"`
	PriceMinor int64             `json:"priceMinor"`
	Currency   string            `json:"currency"`
}

// CatalogProduct is a live product page from catalog (GET /v1/public/products/{atin}).
type CatalogProduct struct {
	ATIN        string            `json:"atin"`
	OwnerKind   string            `json:"ownerKind"`
	SellerOrgID string            `json:"sellerOrgId"`
	CategoryID  string            `json:"categoryId"`
	ProductType string            `json:"productType"`
	Title       string            `json:"title"`
	Attributes  map[string]string `json:"attributes"`
	Images      []struct {
		URL string `json:"url"`
	} `json:"images"`
	VariationTheme string           `json:"variationTheme"`
	Variants       []CatalogVariant `json:"variants"`
}

// CatalogVariantView is a variant with its product (GET /v1/public/variants/{atin}).
type CatalogVariantView struct {
	Product CatalogProduct `json:"product"`
	Variant CatalogVariant `json:"variant"`
}

// Category is a node of the category tree.
type Category struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Path string `json:"path"`
}

// CatalogClient reads catalog's public endpoints. Not-live products return ErrNotFound.
type CatalogClient interface {
	Product(ctx context.Context, atin string) (CatalogProduct, error)
	Variant(ctx context.Context, atin string) (CatalogVariantView, error)
	Categories(ctx context.Context) ([]Category, error)
}

// InventoryClient reads sellable availability per SKU (variant ATIN).
type InventoryClient interface {
	Available(ctx context.Context, skus []string) (map[string]int, error)
}

// ---------------------------------------------------------------- cart (contracts/openapi/cart.yaml)

// CartOwner identifies the cart: a relayed customer token and/or a guest cart id.
type CartOwner struct {
	BearerToken string
	GuestID     string
	// Country, Region and City are the shopper's delivery place (pre-order packaging); empty when unknown.
	Country string
	Region  string
	City    string
}

// UpstreamCartLine is a cart-service line. OfferID is the offers-service offer of the line (empty for lines added
// before offers, which follow the catalog's single seller).
type UpstreamCartLine struct {
	OfferID        string            `json:"offerId"`
	VariantATIN    string            `json:"variantAtin"`
	ProductATIN    string            `json:"productAtin"`
	Title          string            `json:"title"`
	Options        map[string]string `json:"options"`
	ImageURL       string            `json:"imageUrl"`
	SellerOrgID    string            `json:"sellerOrgId"`
	SellerName     string            `json:"sellerName"`
	SellerKind     string            `json:"sellerKind"`
	Fulfilment     string            `json:"fulfilment"`
	PriceMinor     int64             `json:"priceMinor"`
	Currency       string            `json:"currency"`
	Quantity       int               `json:"quantity"`
	Available      int               `json:"available"`
	LowStock       bool              `json:"lowStock"`
	Unavailable    bool              `json:"unavailable"`
	PriceChanged   bool              `json:"priceChanged"`
	LineTotalMinor int64             `json:"lineTotalMinor"`
}

// UpstreamCartPackage is a cart-service package.
type UpstreamCartPackage struct {
	SellerOrgID   string             `json:"sellerOrgId"`
	SellerName    string             `json:"sellerName"`
	Fulfilment    string             `json:"fulfilment"`
	Lines         []UpstreamCartLine `json:"lines"`
	SubtotalMinor int64              `json:"subtotalMinor"`
	// Preorder is the pre-order block of a PREORDER package (window, stage, fulfilment choice, effective fees),
	// relayed as the cart service shapes it; absent for packages that ship now.
	Preorder json.RawMessage `json:"preorder,omitempty"`
}

// UpstreamCart is the cart-service view.
type UpstreamCart struct {
	Packages      []UpstreamCartPackage `json:"packages"`
	Saved         []UpstreamCartLine    `json:"saved"`
	ItemCount     int                   `json:"itemCount"`
	SubtotalMinor int64                 `json:"subtotalMinor"`
	Currency      string                `json:"currency"`
	Version       int64                 `json:"version"`
	// GuestID is the X-Guest-Cart the service answered with (new guests get one issued).
	GuestID string `json:"-"`
}

// Line finds an active line by variant ATIN.
func (c UpstreamCart) Line(atin string) (UpstreamCartLine, bool) {
	for _, p := range c.Packages {
		for _, l := range p.Lines {
			if l.VariantATIN == atin {
				return l, true
			}
		}
	}
	return UpstreamCartLine{}, false
}

// SavedLine finds a saved line by variant ATIN.
func (c UpstreamCart) SavedLine(atin string) (UpstreamCartLine, bool) {
	for _, l := range c.Saved {
		if l.VariantATIN == atin {
			return l, true
		}
	}
	return UpstreamCartLine{}, false
}

// Cart problem codes from the cart service.
const (
	CartInsufficientStock = "cart.insufficient-stock"
	CartNotSellable       = "cart.not-sellable"
	CartLineNotFound      = "cart.line-not-found"
	CartInvalid           = "cart.invalid"
	CartConcurrentUpdate  = "cart.concurrent-update"
	CartOfferNotBuyable   = "cart.offer-not-buyable"
)

// CartClient calls the cart service. 4xx answers are *UpstreamProblem with the cart codes above.
type CartClient interface {
	Get(ctx context.Context, o CartOwner) (UpstreamCart, error)
	// Add adds a variant; with offerID the cart service validates and prices that offers-service offer.
	Add(ctx context.Context, o CartOwner, variantATIN, offerID string, qty int) (UpstreamCart, error)
	SetQuantity(ctx context.Context, o CartOwner, variantATIN string, qty int) (UpstreamCart, error)
	Remove(ctx context.Context, o CartOwner, variantATIN string) (UpstreamCart, error)
	SaveForLater(ctx context.Context, o CartOwner, variantATIN string) (UpstreamCart, error)
	MoveToCart(ctx context.Context, o CartOwner, variantATIN string) (UpstreamCart, error)
	Merge(ctx context.Context, o CartOwner) (UpstreamCart, error)
	// SetPreorderFulfilment chooses DOOR or PICKUP for the cart's pre-order package of a window.
	SetPreorderFulfilment(ctx context.Context, o CartOwner, windowID, fulfilment string) (UpstreamCart, error)
}

// ---------------------------------------------------------------- geo (contracts/openapi/geo.yaml)

// GeoSuggestion is an autocomplete row.
type GeoSuggestion struct {
	ID            string `json:"id"`
	Text          string `json:"text"`
	MainText      string `json:"mainText"`
	SecondaryText string `json:"secondaryText"`
	Provider      string `json:"provider"`
}

// GeoAddress is a structured address.
type GeoAddress struct {
	Line1      string  `json:"line1"`
	Line2      string  `json:"line2,omitempty"`
	City       string  `json:"city"`
	State      string  `json:"state"`
	PostalCode string  `json:"postalCode"`
	Country    string  `json:"country,omitempty"`
	Lat        float64 `json:"lat,omitempty"`
	Lon        float64 `json:"lon,omitempty"`
}

// GeoValidation is an address check result.
type GeoValidation struct {
	Status      string       `json:"status"` // VALID, CORRECTED, MISSING_UNIT, UNDELIVERABLE, UNVERIFIED
	Standard    *GeoAddress  `json:"standardized"`
	Suggestions []GeoAddress `json:"suggestions"`
	Messages    []string     `json:"messages"`
}

// GeoClient calls the geo service. Reverse geocoding is GET /v1/reverse?lat=&lng= (Google, then Nominatim).
type GeoClient interface {
	Autocomplete(ctx context.Context, q, session, country string) ([]GeoSuggestion, error)
	Resolve(ctx context.Context, id, session string) (GeoAddress, error)
	Validate(ctx context.Context, a GeoAddress) (GeoValidation, error)
	Zip(ctx context.Context, zip string) (GeoAddress, error)
	Reverse(ctx context.Context, lat, lon float64) (GeoAddress, error)
}

// ---------------------------------------------------------------- identity (OIDC + REST)

// IdentityMe is identity's GET /v1/me.
type IdentityMe struct {
	ID        string   `json:"id"`
	FirstName string   `json:"firstName"`
	LastName  string   `json:"lastName"`
	Email     string   `json:"email"`
	Roles     []string `json:"roles"`
	// Phone is E.164; PhoneVerified says whether the SMS check (S234) passed.
	Phone         string `json:"phone"`
	PhoneVerified bool   `json:"phoneVerified"`
}

// PhoneCodeSent is identity's POST /v1/me/phone/verification answer.
type PhoneCodeSent struct {
	SentTo    string    `json:"sentTo"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// PhoneVerification is identity's POST /v1/me/phone/verification/confirm answer.
type PhoneVerification struct {
	Phone         string     `json:"phone"`
	PhoneVerified bool       `json:"phoneVerified"`
	VerifiedAt    *time.Time `json:"verifiedAt"`
}

// IdentityProvider is an enabled social sign-in provider.
type IdentityProvider struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

// IdentityAddress is identity's address-book entry (GET /v1/me/addresses).
type IdentityAddress struct {
	ID          string   `json:"id,omitempty"`
	Label       string   `json:"label,omitempty"`
	FullName    string   `json:"fullName"`
	Phone       string   `json:"phone"`
	Line1       string   `json:"line1"`
	Line2       string   `json:"line2,omitempty"`
	City        string   `json:"city"`
	State       string   `json:"state"`
	PostalCode  string   `json:"postalCode"`
	Country     string   `json:"country,omitempty"`
	Latitude    *float64 `json:"latitude,omitempty"`
	Longitude   *float64 `json:"longitude,omitempty"`
	Verified    bool     `json:"verified"`
	IsDefault   bool     `json:"isDefault"`
	MakeDefault bool     `json:"makeDefault,omitempty"`
}

// ErrInvalidGrant is returned when a code or refresh token is rejected (expired, reused, revoked).
var ErrInvalidGrant = &UpstreamProblem{Upstream: "identity", Status: 400, Code: "invalid_grant", Detail: "grant rejected"}

// IdentityClient talks to identity: the OAuth 2.1 token endpoint (confidential client gateway-bff), revocation,
// and the REST API (me, accounts, identity providers, address book).
type IdentityClient interface {
	ExchangeCode(ctx context.Context, code, verifier, redirectURI string) (domain.Tokens, error)
	Refresh(ctx context.Context, refreshToken string) (domain.Tokens, error)
	Revoke(ctx context.Context, token, hint string) error
	Me(ctx context.Context, bearer string) (IdentityMe, error)
	Providers(ctx context.Context) ([]IdentityProvider, error)
	Addresses(ctx context.Context, bearer string) ([]IdentityAddress, error)
	CreateAddress(ctx context.Context, bearer string, a IdentityAddress) (IdentityAddress, error)
	UpdateAddress(ctx context.Context, bearer, id string, a IdentityAddress) (IdentityAddress, error)
	DeleteAddress(ctx context.Context, bearer, id string) error
	DefaultAddress(ctx context.Context, bearer, id string) (IdentityAddress, error)
	StartPhoneVerification(ctx context.Context, bearer string) (PhoneCodeSent, error)
	ConfirmPhoneVerification(ctx context.Context, bearer, code string) (PhoneVerification, error)
}

// TokenVerifier verifies identity-issued JWTs (JWKS).
type TokenVerifier interface {
	// VerifyAccess checks an app bearer token and returns its subject and roles.
	VerifyAccess(ctx context.Context, token string) (subject string, roles []string, err error)
	// VerifyIDToken checks an ID token (issuer, audience = BFF client, expiry, nonce) and returns the profile.
	VerifyIDToken(ctx context.Context, token, nonce string) (domain.Profile, error)
}

// ---------------------------------------------------------------- content (being built)

// ContentSiteConfig is content's public GET /v1/site-config (contracts/openapi/content.yaml SiteConfig).
type ContentSiteConfig struct {
	Revision int `json:"revision"`
	Socials  []struct {
		Network string `json:"network"`
		URL     string `json:"url"`
		Label   string `json:"label"`
	} `json:"socials"`
	Contacts map[string]any `json:"contacts"`
	Legal    []struct {
		Key       string `json:"key"`
		Title     string `json:"title"`
		Version   string `json:"version"`
		Effective string `json:"effective"`
		Href      string `json:"href"`
		Draft     bool   `json:"draft"`
	} `json:"legal"`
	// IdentityProviders carries display order only; enabled flags come from identity.
	IdentityProviders []struct {
		ID    string `json:"id"`
		Name  string `json:"name"`
		Order int    `json:"order"`
	} `json:"identityProviders"`
	Locales []struct {
		Code string `json:"code"`
		Name string `json:"name"`
		Dir  string `json:"dir"`
	} `json:"locales"`
	Market struct {
		Code     string `json:"code"`
		Currency string `json:"currency"`
		Country  string `json:"country"`
	} `json:"market"`
	AppLinks struct {
		IOS     string `json:"ios"`
		Android string `json:"android"`
	} `json:"appLinks"`
	PaymentMethods []string `json:"paymentMethods"`
	SMSProgramName string   `json:"smsProgramName"`
	// CacheControl is content's Cache-Control header (not part of the body).
	CacheControl string `json:"-"`
}

// ContentClient calls the content service (contracts/openapi/content.yaml): public site config, placements resolved
// per market/ZIP (mapped from slot payloads), newsletter, careers and market eligibility.
type ContentClient interface {
	SiteConfig(ctx context.Context) (ContentSiteConfig, error)
	Placements(ctx context.Context, market, zip string) (domain.Placements, error)
	Subscribe(ctx context.Context, email string) (string, error)
	Jobs(ctx context.Context) ([]domain.Job, error)
	Apply(ctx context.Context, jobID string, a domain.JobApplication) (domain.ApplicationReceipt, error)
	// Eligibility asks content whether a place is served; city is optional (a city switched off in Markets is not).
	Eligibility(ctx context.Context, country, state, zip, city string) (domain.MarketEligibility, error)
	// LegalDocument is a document's current version (version "") or one version; port.ErrNotFound when unknown.
	LegalDocument(ctx context.Context, key, version, locale string) (domain.LegalPage, error)
	// CookiePolicy is the consent banner configuration and cookie table.
	CookiePolicy(ctx context.Context) (domain.CookiePolicy, error)
	// HelpArticles is a page of published help articles; HelpArticle one of them (port.ErrNotFound when unknown).
	HelpArticles(ctx context.Context, q domain.HelpQuery) (domain.HelpArticlePage, error)
	HelpArticle(ctx context.Context, slug string) (domain.HelpArticle, error)
	// RecordConsent stores a consent decision (content normalises it).
	RecordConsent(ctx context.Context, in ContentConsent) (domain.ConsentRecord, error)
	// SubmitPrivacyRequest stores a privacy request; bearer (may be empty) attaches the signed-in account.
	SubmitPrivacyRequest(ctx context.Context, bearer string, in domain.PrivacyRequest) (domain.PrivacyRequestReceipt, error)
}

// ContentConsent is content's ConsentRequest: the subject is decided by the BFF, never the browser.
type ContentConsent struct {
	Subject    string          `json:"subject"`
	Categories map[string]bool `json:"categories"`
	GPC        bool            `json:"gpc"`
	SaleOptOut bool            `json:"saleOptOut"`
	Source     string          `json:"source"`
}

// ---------------------------------------------------------------- payments, promise, orders, notifications (being built)

// PaymentsClient manages the customer's saved Stripe payment methods.
type PaymentsClient interface {
	PaymentMethods(ctx context.Context, bearer string) ([]domain.PaymentMethod, error)
	SetupIntent(ctx context.Context, bearer string) (domain.SetupIntent, error)
	DeletePaymentMethod(ctx context.Context, bearer, id string) error
}

// PromiseItem asks for a delivery promise for one offer.
type PromiseItem struct {
	ATIN        string `json:"atin"`
	Fulfilment  string `json:"fulfilment"`
	SellerOrgID string `json:"sellerOrgId,omitempty"`
}

// PromiseClient computes delivery promises per ZIP (POST /v1/promises). Result: business days per ATIN.
type PromiseClient interface {
	Promises(ctx context.Context, zip string, items []PromiseItem) (map[string]int, error)
}

// PlaceOrderResult is the orders answer (201 placed, 202 requires_action).
type PlaceOrderResult struct {
	Status       int
	Confirmation domain.OrderConfirmation
}

// OrderLine is a selected cart line sent to orders.
type OrderLine struct {
	VariantATIN string `json:"variantAtin"`
	Quantity    int    `json:"quantity"`
	PackageID   string `json:"packageId"`
}

// OrdersClient calls the orders service (orders, shipment cancel, returns).
type OrdersClient interface {
	List(ctx context.Context, bearer, cursor string, limit int) (domain.Page[domain.OrderSummary], error)
	Place(ctx context.Context, bearer, idempotencyKey string, req domain.PlaceOrderRequest, lines []OrderLine) (PlaceOrderResult, error)
	CancelShipment(ctx context.Context, bearer, orderNumber, shipmentID string) (domain.OrderSummary, error)
	Returns(ctx context.Context, bearer string) (domain.Page[domain.ReturnSummary], error)
	StartReturn(ctx context.Context, bearer string, r domain.StartReturn) (domain.ReturnSummary, error)
}

// StockAlertInput arms a back-in-stock alert (notifications POST /v1/stock-alerts).
type StockAlertInput struct {
	ATIN         string `json:"atin"`
	Email        string `json:"email,omitempty"`
	Market       string `json:"market,omitempty"`
	ProductTitle string `json:"productTitle,omitempty"`
}

// StockAlert is notifications' StockAlert.
type StockAlert struct {
	ATIN         string     `json:"atin"`
	Email        string     `json:"email"`
	ProductTitle string     `json:"productTitle"`
	Status       string     `json:"status"` // active | notified
	CreatedAt    time.Time  `json:"createdAt"`
	NotifiedAt   *time.Time `json:"notifiedAt"`
}

// NotificationsClient manages the shopper's back-in-stock alerts with the shopper's own token (service tokens are
// refused by notifications). Subscribing is 201 when armed and 200 when already active — both are success.
type NotificationsClient interface {
	SubscribeStockAlert(ctx context.Context, bearer string, in StockAlertInput) error
	StockAlerts(ctx context.Context, bearer string) ([]StockAlert, error)
	RemoveStockAlert(ctx context.Context, bearer, atin string) error
}

// ---------------------------------------------------------------- offers (contracts/openapi/offers.yaml, public reads)

// OfferSeller is offers' OfferSeller (city = ship-from city or "AfroTransact").
type OfferSeller struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"`
	City string `json:"city"`
}

// PublicOffer is offers' PublicOffer (maps 1:1 to the BFF Offer; delivery comes from promise).
type PublicOffer struct {
	OfferID        string      `json:"offerId"`
	ATIN           string      `json:"atin"`
	ProductATIN    string      `json:"productAtin"`
	Seller         OfferSeller `json:"seller"`
	Condition      string      `json:"condition"`
	PriceMinor     int64       `json:"priceMinor"`
	ListPriceMinor *int64      `json:"listPriceMinor"`
	ShippingMinor  int64       `json:"shippingMinor"`
	Currency       string      `json:"currency"`
	Fulfilment     string      `json:"fulfilment"`
	AvailableQty   int         `json:"availableQty"`
	StockStatus    string      `json:"stockStatus"`
	DeliveryDays   int         `json:"deliveryDays"`
	ShipsFrom      string      `json:"shipsFrom"`
	SoldBy         string      `json:"soldBy"`
	Badges         []string    `json:"badges"`
	// ShipFromLocationID is the pickup location of AFROTRANSACT_DELIVERY offers (S255).
	ShipFromLocationID string `json:"shipFromLocationId"`
}

// OfferBuyBox is offers' BuyBox: the winner (nil = currently unavailable), the other listed offers by landed price,
// the listed offer count and the lowest price. For product summaries ATIN is the product ATIN.
type OfferBuyBox struct {
	ATIN          string        `json:"atin"`
	Currency      string        `json:"currency"`
	BuyBox        *PublicOffer  `json:"buyBox"`
	OtherOffers   []PublicOffer `json:"otherOffers"`
	OfferCount    int           `json:"offerCount"`
	MinPriceMinor *int64        `json:"minPriceMinor"`
}

// OfferDetail is offers' PublicOfferDetail: one offer and whether it can be bought now.
type OfferDetail struct {
	Offer       PublicOffer `json:"offer"`
	Buyable     bool        `json:"buyable"`
	NotEligible []string    `json:"notEligible"`
}

// StorefrontOffersClient reads the offers service's public endpoints: the buy box and "Other sellers" of a variant,
// card summaries of product pages (batch) and one offer for add-to-cart. Unknown offers return ErrNotFound.
type StorefrontOffersClient interface {
	BuyBox(ctx context.Context, variantATIN string) (OfferBuyBox, error)
	ProductSummaries(ctx context.Context, productATINs []string) (map[string]OfferBuyBox, error)
	Offer(ctx context.Context, offerID string) (OfferDetail, error)
}

// ---------------------------------------------------------------- state and caches

// SessionStore persists sessions (Valkey). Tokens are encrypted at rest by the adapter.
type SessionStore interface {
	// Load returns nil (and no error) when the session does not exist.
	Load(ctx context.Context, key string) (*domain.Session, error)
	Save(ctx context.Context, key string, s *domain.Session, ttl time.Duration) error
	Delete(ctx context.Context, key string) error
	// Lock takes a short exclusive lock (token refresh); ok=false when someone else holds it.
	Lock(ctx context.Context, key string, ttl time.Duration) (unlock func(), ok bool, err error)
}

// AuthRequest is the pending authorization-code request (PKCE verifier, nonce, where to return).
type AuthRequest struct {
	Verifier  string `json:"verifier"`
	Nonce     string `json:"nonce"`
	ReturnTo  string `json:"returnTo"` // path on Origin
	SessionID string `json:"sessionId,omitempty"`
	Provider  string `json:"provider,omitempty"`
	// Origin is the web app the sign-in started from (storefront, Seller Center, staff console); the callback returns
	// there. Empty for requests stored before per-app callbacks (the storefront).
	Origin string `json:"origin,omitempty"`
	// RedirectURI is the callback sent to identity, needed again for the code exchange.
	RedirectURI string `json:"redirectUri,omitempty"`
}

// Challenge is a pending sign-up verification.
type Challenge struct {
	Email    string `json:"email"`
	ReturnTo string `json:"returnTo"`
	// Factor is the second factor a first-party MFA challenge verifies next (TOTP, SMS or RECOVERY_CODE); empty for
	// sign-up email verification.
	Factor string `json:"factor,omitempty"`
	// Destination is the masked phone number SMS codes go to.
	Destination string `json:"destination,omitempty"`
}

// EphemeralStore holds single-use OIDC states and sign-up challenges with a TTL.
type EphemeralStore interface {
	PutAuthRequest(ctx context.Context, state string, r AuthRequest, ttl time.Duration) error
	// TakeAuthRequest returns and deletes the request (nil when unknown or expired).
	TakeAuthRequest(ctx context.Context, state string) (*AuthRequest, error)
	PutChallenge(ctx context.Context, id string, c Challenge, ttl time.Duration) error
	GetChallenge(ctx context.Context, id string) (*Challenge, error)
	DeleteChallenge(ctx context.Context, id string) error
}

// SelectionStore remembers which cart lines are deselected for checkout (the cart service has no selection).
type SelectionStore interface {
	Deselected(ctx context.Context, owner string) (map[string]bool, error)
	SetSelected(ctx context.Context, owner string, atins []string, selected bool) error
}

// RateLimiter is a fixed-window counter shared by every replica.
type RateLimiter interface {
	Allow(ctx context.Context, key string, limit int, window time.Duration) (ok bool, retryAfter time.Duration, err error)
}

// Cache is a short-TTL cache for public, non-personal upstream reads.
type Cache interface {
	Get(key string) ([]byte, bool)
	Set(key string, value []byte, ttl time.Duration)
}

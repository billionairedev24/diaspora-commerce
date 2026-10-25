// Package httpapi is the BFF's inbound HTTP adapter: every operation of contracts/openapi/gateway-bff.yaml,
// behind CORS, the CSRF Origin check, body limits, session resolution and per-IP rate limits on auth endpoints.
package httpapi

import (
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Services are the use cases the handlers call.
type Services struct {
	Sessions app.SessionService
	Auth     app.AuthService
	// Native signs the customer and driver apps in with email and password (in-app form, no hosted page); routes are
	// registered when set.
	Native   app.NativeAuthService
	Site     app.SiteService
	Location app.LocationService
	Search   app.SearchService
	Products app.ProductService
	Cart     app.CartService
	Account  app.AccountService
	Checkout app.CheckoutService
	Careers  app.CareersService
	// Maintenance is the maintenance switch: the gate refuses customer and seller calls while it is on (nil = never).
	Maintenance app.MaintenanceService
	// Preorders serves the storefront's pre-order routes (S270–S275); registered when set.
	Preorders app.PreorderService
	// Seller Center & Provider Hub (contracts/openapi/gateway-bff-seller.yaml); routes are registered when set.
	SellerShell     app.SellerShellService
	SellerDashboard app.SellerDashboardService
	SellerOrders    app.SellerOrdersService
	SellerCatalog   app.SellerCatalogService
	SellerMoney     app.SellerMoneyService
	SellerFees      app.SellerFeesService
	SellerTeam      app.SellerTeamService
	ProviderHub     app.ProviderHubService
	SellerShipFrom  app.SellerShipFromService
	// SellerRunPickups gives sellers the delivery runs that collect from them, with a pick list (S264); registered when set.
	SellerRunPickups app.SellerRunPickupsService
	// SellerBusinessPage is the page settings (description, logo, brand colour).
	SellerBusinessPage app.SellerBusinessPageService

	// SellerListing is the one-step product form (S307) and SellerImports the bulk upload (S308); registered when set.
	SellerListing app.SellerListingService
	SellerImports app.SellerImportService
	// SellerOffers is the fulfilment choice of offers (S255); its routes are registered when set.
	SellerOffers app.SellerOffersService
	// SellerAssistant is Lola in Seller Center, with the plan's tier; its routes are registered when set.
	SellerAssistant app.SellerAssistantService
	// SellerCoupons manages seller-funded promotional codes (offers-service).
	SellerCoupons app.SellerCouponsService
	// SellerCases lists the organisation's returns and disputes.
	SellerCases app.SellerCasesService
	// SellerTax is the monthly statement of the sales tax collected on the seller's behalf; its routes are registered when set.
	SellerTax app.SellerTaxService
	// SellerFba is Seller Center's Send to AfroTransact (S281); its routes are registered when set.
	SellerFba app.SellerFbaService
	// SellerExports exports Seller Center tables; SellerExportFiles serves the files the BFF keeps itself (nil when
	// exports live in media). Routes are registered when SellerExports is set.
	SellerExports     app.SellerExportsService
	SellerExportFiles port.LocalExportFiles
	// Storefront account, checkout payment, services, Lola (handlers_shopper.go); routes are registered when set.
	Shopper ShopperServices
	// Staff console (handlers_staff.go, contracts/openapi/gateway-bff-staff.yaml); routes are registered when set.
	Staff StaffServices
}

// Handlers registers the BFF API.
type Handlers interface {
	Register(mux *http.ServeMux)
}

type handlersImpl struct {
	s   Services
	o   SecurityOptions
	log *slog.Logger
	rl  limiter
}

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(s Services, rl port.RateLimiter, o SecurityOptions, log *slog.Logger) Handlers {
	if o.MaxBodyBytes <= 0 {
		o.MaxBodyBytes = 64 << 10
	}
	if o.CookieName == "" {
		o.CookieName = "at_session"
	}
	return &handlersImpl{s: s, o: o, log: log, rl: limiter{rl: rl, log: log, o: o}}
}

func (h *handlersImpl) chain(next http.Handler) http.Handler {
	next = maintenanceGate(h.s.Maintenance, h.log)(next)
	next = identify(h.s.Sessions, h.o, h.log)(next)
	next = actingContext(next)
	next = csrf(h.o, h.log)(next)
	next = bodyLimit(h.o.MaxBodyBytes)(next)
	next = rewriteMedia(h.o)(next)
	return cors(h.o)(next)
}

func (h *handlersImpl) Register(mux *http.ServeMux) {
	route := func(pattern string, fn http.HandlerFunc) { mux.Handle(pattern, h.chain(fn)) }
	auth := func(fn http.HandlerFunc) http.HandlerFunc { return h.rl.wrap("auth", h.o.AuthPerMinute, fn) }
	lookup := func(fn http.HandlerFunc) http.HandlerFunc { return h.rl.wrap("lookup", h.o.LookupPerMinute, fn) }

	mux.Handle("OPTIONS /v1/", cors(h.o)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })))

	// Maintenance status (always answered, even in maintenance: it is how the apps know to show the page)
	route("GET /v1/maintenance", h.maintenance)
	// Site & content
	route("GET /v1/site-config", h.siteConfig)
	route("GET /v1/home", h.home)
	route("POST /v1/newsletter/subscriptions", lookup(h.newsletter))
	// Legal & privacy center
	route("GET /v1/legal/{key}", h.legalDocument)
	// Help center
	route("GET /v1/help/articles", h.helpArticles)
	route("GET /v1/help/articles/{slug}", h.helpArticle)
	route("GET /v1/consent/cookie-policy", h.cookiePolicy)
	route("POST /v1/consent/records", lookup(h.recordConsent))
	route("POST /v1/privacy/requests", lookup(h.privacyRequest))
	// Location
	route("GET /v1/delivery-location", h.getLocation)
	route("PUT /v1/delivery-location", h.setLocation)
	route("GET /v1/geo/address-suggestions", lookup(h.addressSuggestions))
	route("GET /v1/geo/address-suggestions/{id}", lookup(h.resolveAddressSuggestion))
	// Search & catalogue
	route("GET /v1/search", h.search)
	route("GET /v1/search/suggestions", h.suggest)
	route("GET /v1/products/{atin}", h.product)
	route("POST /v1/products/{atin}/stock-alerts", lookup(h.stockAlert))
	route("GET /v1/account/stock-alerts", h.stockAlerts)
	route("DELETE /v1/account/stock-alerts/{atin}", h.removeStockAlert)
	// Cart
	route("GET /v1/cart", h.getCart)
	route("POST /v1/cart/items", h.addToCart)
	route("PATCH /v1/cart/items/{lineId}", h.updateLine)
	route("DELETE /v1/cart/items/{lineId}", h.removeLine)
	route("PUT /v1/cart/selection", h.selectAll)
	route("POST /v1/cart/items/{lineId}/save-for-later", h.saveForLater)
	route("POST /v1/cart/saved/{savedId}/move-to-cart", h.moveToCart)
	route("DELETE /v1/cart/saved/{savedId}", h.removeSaved)
	route("PUT /v1/cart/preorder-fulfilment", h.setPreorderFulfilment)
	// Pre-orders
	h.registerPreorders(route, lookup)
	// Auth
	route("GET /v1/me", h.me)
	route("GET /v1/auth/login", auth(h.login))
	route("GET /v1/auth/oidc/{provider}/authorize", auth(h.socialLogin))
	route("GET /v1/auth/oidc/callback", auth(h.callback))
	if h.s.Native != nil {
		route("POST /v1/auth/native/password", auth(h.nativePassword))
		route("POST /v1/auth/native/mfa/sms", auth(h.nativeSMS))
		route("POST /v1/auth/native/mfa/verify", auth(h.nativeVerify))
	}
	route("POST /v1/auth/sign-out", h.signOut)
	route("GET /v1/auth/signed-out/{app}", auth(h.signedOut))
	// Account
	route("GET /v1/account/addresses", h.listAddresses)
	route("POST /v1/account/addresses", h.createAddress)
	route("PATCH /v1/account/addresses/{addressId}", h.updateAddress)
	route("DELETE /v1/account/addresses/{addressId}", h.deleteAddress)
	route("POST /v1/account/addresses/{addressId}/default", h.defaultAddress)
	route("POST /v1/account/phone/verification", auth(h.startPhoneVerification))
	route("POST /v1/account/phone/verification/confirm", auth(h.confirmPhoneVerification))
	route("GET /v1/account/payment-methods", h.paymentMethods)
	route("POST /v1/account/payment-methods/setup-intents", h.setupIntent)
	route("DELETE /v1/account/payment-methods/{paymentMethodId}", h.deletePaymentMethod)
	route("GET /v1/account/orders", h.orders)
	route("POST /v1/account/orders/{orderNumber}/shipments/{shipmentId}/cancel", h.cancelShipment)
	route("GET /v1/account/returns", h.returns)
	route("POST /v1/account/returns", h.startReturn)
	// Checkout
	route("GET /v1/checkout/summary", h.checkoutSummary)
	route("POST /v1/checkout/orders", h.placeOrder)
	// Careers
	route("GET /v1/careers/jobs", h.jobs)
	route("POST /v1/careers/jobs/{jobId}/applications", lookup(h.applyToJob))
	route("POST /v1/careers/uploads", lookup(h.careersUpload))
	route("POST /v1/careers/uploads/{assetId}/complete", lookup(h.careersUploadComplete))
	// Seller Center & Provider Hub
	h.registerSeller(route)
	// Storefront account, checkout payment, services & bookings, Lola
	h.registerShopper(route)
	// Staff console
	h.registerStaff(route)
}

// finish issues, rotates or clears the session cookie and echoes an issued guest cart id to app callers.
// It must run before the status line is written.
func (h *handlersImpl) finish(w http.ResponseWriter, c *domain.Caller) {
	if c.IssuedGuestCartID != "" {
		w.Header().Set("X-Guest-Cart", c.IssuedGuestCartID)
	}
	if c.App {
		return
	}
	switch {
	case c.SessionEnded:
		//nolint:gosec // HttpOnly+SameSite=Lax always; Secure comes from config (false only for plain-http local dev)
		http.SetCookie(w, &http.Cookie{Name: h.o.CookieName, Value: "", Path: "/", Domain: h.o.CookieDomain, MaxAge: -1,
			HttpOnly: true, Secure: h.o.CookieSecure, SameSite: http.SameSiteLaxMode})
	case (c.SessionCreated || c.SessionRotated) && c.Session != nil:
		//nolint:gosec // HttpOnly+SameSite=Lax always; Secure comes from config (false only for plain-http local dev)
		http.SetCookie(w, &http.Cookie{Name: h.o.CookieName, Value: h.s.Sessions.Cookie(c), Path: "/", Domain: h.o.CookieDomain,
			MaxAge: int(h.s.Sessions.IdleTTL().Seconds()), HttpOnly: true, Secure: h.o.CookieSecure, SameSite: http.SameSiteLaxMode})
	}
}

// reply writes v (or the problem in err) after finishing the session cookie.
func (h *handlersImpl) reply(w http.ResponseWriter, r *http.Request, status int, v any, err error) {
	c := callerFrom(r.Context())
	h.finish(w, c)
	if err != nil {
		writeProblem(w, r, h.log, err)
		return
	}
	if v == nil {
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(status)
		return
	}
	writeJSON(w, status, v)
}

func (h *handlersImpl) redirect(w http.ResponseWriter, r *http.Request, location string) {
	h.finish(w, callerFrom(r.Context()))
	w.Header().Set("Cache-Control", "no-store")
	// Locations are identity's configured authorize URL, or a configured web app origin (storefront, Seller Center,
	// staff console: the origins with a registered callback) + a relative returnTo (domain.SafeReturnTo), never any
	// other client-supplied absolute URL.
	http.Redirect(w, r, location, http.StatusFound) //nolint:gosec // see above
}

// ---------------------------------------------------------------- site

func (h *handlersImpl) siteConfig(w http.ResponseWriter, r *http.Request) {
	cfg, err := h.s.Site.SiteConfig(r.Context())
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	etag := cfg.ETag
	cc := cfg.CacheControl
	if cc == "" || strings.Contains(cc, "private") {
		cc = "public, max-age=60, stale-while-revalidate=600"
	}
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", cc)
	if match := r.Header.Get("If-None-Match"); match != "" && (match == etag || match == "*" || strings.Contains(match, etag)) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(cfg.Body)
}

func (h *handlersImpl) home(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Site.Home(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) newsletter(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Email string `json:"email"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	status, err := h.s.Site.Subscribe(r.Context(), b.Email)
	h.reply(w, r, http.StatusAccepted, map[string]string{"status": status}, err)
}

// publicJSON replies with a public, briefly cacheable document (legal pages and the cookie policy are the same for
// every visitor).
func (h *handlersImpl) publicJSON(w http.ResponseWriter, r *http.Request, v any, err error) {
	if err == nil {
		w.Header().Set("Cache-Control", "public, max-age=60, stale-while-revalidate=600")
		w.Header().Set("Vary", "Accept-Encoding")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) legalDocument(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.Site.LegalDocument(r.Context(), r.PathValue("key"), q.Get("version"), q.Get("locale"))
	h.publicJSON(w, r, v, err)
}

func (h *handlersImpl) helpArticles(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit := 0
	if l := q.Get("limit"); l != "" {
		n, err := strconv.Atoi(l)
		if err != nil {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"limit": "Between 1 and 50"}))
			return
		}
		limit = n
	}
	v, err := h.s.Site.HelpArticles(r.Context(), domain.HelpQuery{Topic: q.Get("topic"), Q: q.Get("q"), Cursor: q.Get("cursor"), Limit: limit})
	h.publicJSON(w, r, v, err)
}

func (h *handlersImpl) helpArticle(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Site.HelpArticle(r.Context(), r.PathValue("slug"))
	h.publicJSON(w, r, v, err)
}

func (h *handlersImpl) cookiePolicy(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Site.CookiePolicy(r.Context())
	h.publicJSON(w, r, v, err)
}

func (h *handlersImpl) recordConsent(w http.ResponseWriter, r *http.Request) {
	var b domain.ConsentChoice
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Site.RecordConsent(r.Context(), callerFrom(r.Context()), b, r.Header.Get("Sec-GPC") == "1")
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) privacyRequest(w http.ResponseWriter, r *http.Request) {
	var b domain.PrivacyRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Site.SubmitPrivacyRequest(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) jobs(w http.ResponseWriter, r *http.Request) {
	jobs, err := h.s.Site.Jobs(r.Context())
	h.reply(w, r, http.StatusOK, map[string]any{"jobs": jobs}, err)
}

func (h *handlersImpl) careersUpload(w http.ResponseWriter, r *http.Request) {
	var b struct {
		FileName    string `json:"fileName"`
		ContentType string `json:"contentType"`
		Size        int64  `json:"size"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Careers.ResumeUpload(r.Context(), callerFrom(r.Context()), b.FileName, b.ContentType, b.Size)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) careersUploadComplete(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Careers.CompleteResume(r.Context(), callerFrom(r.Context()), r.PathValue("assetId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) applyToJob(w http.ResponseWriter, r *http.Request) {
	var a domain.JobApplication
	if err := decode(r, &a, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Site.Apply(r.Context(), r.PathValue("jobId"), a)
	h.reply(w, r, http.StatusCreated, v, err)
}

// ---------------------------------------------------------------- location

func (h *handlersImpl) getLocation(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusOK, h.s.Location.Current(r.Context(), callerFrom(r.Context())), nil)
}

func (h *handlersImpl) setLocation(w http.ResponseWriter, r *http.Request) {
	var b struct {
		ZIP *string  `json:"zip"`
		Lat *float64 `json:"lat"`
		Lon *float64 `json:"lon"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	c := callerFrom(r.Context())
	switch {
	case b.ZIP != nil:
		v, err := h.s.Location.SetZIP(r.Context(), c, *b.ZIP)
		h.reply(w, r, http.StatusOK, v, err)
	case b.Lat != nil && b.Lon != nil:
		if *b.Lat < -90 || *b.Lat > 90 || *b.Lon < -180 || *b.Lon > 180 {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"lat": "Coordinates out of range"}))
			return
		}
		v, err := h.s.Location.SetCoordinates(r.Context(), c, *b.Lat, *b.Lon)
		h.reply(w, r, http.StatusOK, v, err)
	default:
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"zip": "Enter a valid 5-digit US ZIP code."}))
	}
}

func (h *handlersImpl) addressSuggestions(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	text := strings.TrimSpace(q.Get("q"))
	if text == "" || len(text) > 120 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"q": "Type 1–120 characters"}))
		return
	}
	v, err := h.s.Location.Suggest(r.Context(), text, q.Get("country"), q.Get("sessionToken"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) resolveAddressSuggestion(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Location.ResolveSuggestion(r.Context(), r.PathValue("id"), r.URL.Query().Get("sessionToken"))
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- search & catalogue

var (
	fulfilments = map[string]bool{domain.FulfilledByAfroTransact: true, domain.ShipsFromSeller: true}
	sellerKinds = map[string]bool{domain.SellerKindPlatform: true, domain.SellerKindSeller: true}
	sorts       = map[string]bool{domain.SortRelevance: true, domain.SortPriceAsc: true, domain.SortPriceDesc: true, domain.SortRating: true, domain.SortFastest: true}
)

// parseSearch validates GET /v1/search parameters (400 INVALID_QUERY on bad values).
func parseSearch(r *http.Request) (domain.SearchQuery, error) {
	v := r.URL.Query()
	bad := func(detail string) error {
		return domain.NewProblem(http.StatusBadRequest, domain.CodeInvalidQuery, "Invalid search query", detail)
	}
	q := domain.SearchQuery{Q: v.Get("q"), Department: v.Get("department"), Community: v.Get("community"),
		Fulfilment: v.Get("fulfilment"), SellerKind: v.Get("sellerKind"), Price: v.Get("price"), Sort: v.Get("sort"), Size: domain.DefaultSearchSize}
	if len(q.Q) > 200 {
		return q, bad("The search text is too long.")
	}
	if q.Fulfilment != "" && !fulfilments[q.Fulfilment] {
		return q, bad("Unknown fulfilment filter.")
	}
	if q.SellerKind != "" && !sellerKinds[q.SellerKind] {
		return q, bad("Unknown seller filter.")
	}
	if q.Price != "" {
		if _, ok := domain.PriceBucketByID(q.Price); !ok {
			return q, bad("Unknown price range.")
		}
	}
	if q.Sort == "" {
		q.Sort = domain.SortRelevance
	} else if !sorts[q.Sort] {
		return q, bad("Unknown sort.")
	}
	if s := v.Get("minRating"); s != "" {
		f, err := strconv.ParseFloat(s, 64)
		if err != nil || f < 0 || f > 5 {
			return q, bad("minRating must be between 0 and 5.")
		}
		q.MinRating = &f
	}
	if s := v.Get("maxDays"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 0 {
			return q, bad("maxDays must be a non-negative integer.")
		}
		q.MaxDays = &n
	}
	if s := v.Get("page"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 0 {
			return q, bad("page must be a non-negative integer.")
		}
		q.Page = n
	}
	if s := v.Get("size"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 1 || n > 60 {
			return q, bad("size must be between 1 and 60.")
		}
		q.Size = n
	}
	q.Ads = v.Get("ads") != "false" // results pages carry sponsored products unless the client opts out (S240)
	return q, nil
}

func (h *handlersImpl) search(w http.ResponseWriter, r *http.Request) {
	q, err := parseSearch(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Search.Search(r.Context(), callerFrom(r.Context()), q)
	if err == nil && v.Sponsored {
		w.Header().Set("Cache-Control", "private, no-store") // ad click tokens are per page view
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) suggest(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Search.Suggest(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) product(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Products.Detail(r.Context(), callerFrom(r.Context()), r.PathValue("atin"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) stockAlerts(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Products.StockAlerts(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) removeStockAlert(w http.ResponseWriter, r *http.Request) {
	err := h.s.Products.RemoveStockAlert(r.Context(), callerFrom(r.Context()), r.PathValue("atin"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) stockAlert(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Email string `json:"email"`
	}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	atin := strings.ToUpper(r.PathValue("atin"))
	err := h.s.Products.StockAlert(r.Context(), callerFrom(r.Context()), atin, b.Email)
	h.reply(w, r, http.StatusCreated, map[string]string{"atin": atin, "status": "subscribed"}, err)
}

// ---------------------------------------------------------------- cart

func (h *handlersImpl) getCart(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Cart.Get(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func quantityProblem() error {
	return domain.ValidationProblem(map[string]string{"quantity": "Choose a quantity from 1 to 99"})
}

func (h *handlersImpl) addToCart(w http.ResponseWriter, r *http.Request) {
	var b struct {
		OfferID  string `json:"offerId"`
		Quantity *int   `json:"quantity"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	errs := map[string]string{}
	if strings.TrimSpace(b.OfferID) == "" {
		errs["offerId"] = "Required"
	}
	if b.Quantity == nil || *b.Quantity < 1 || *b.Quantity > domain.MaxLineQuantity {
		errs["quantity"] = "Choose a quantity from 1 to 99"
	}
	if len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	v, err := h.s.Cart.Add(r.Context(), callerFrom(r.Context()), strings.TrimSpace(b.OfferID), *b.Quantity)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) updateLine(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Quantity *int  `json:"quantity"`
		Selected *bool `json:"selected"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if b.Quantity == nil && b.Selected == nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"_": "Provide quantity or selected"}))
		return
	}
	if b.Quantity != nil && (*b.Quantity < 1 || *b.Quantity > domain.MaxLineQuantity) {
		h.reply(w, r, 0, nil, quantityProblem())
		return
	}
	v, err := h.s.Cart.UpdateLine(r.Context(), callerFrom(r.Context()), r.PathValue("lineId"), b.Quantity, b.Selected)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) removeLine(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Cart.RemoveLine(r.Context(), callerFrom(r.Context()), r.PathValue("lineId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) selectAll(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Selected *bool `json:"selected"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if b.Selected == nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"selected": "Required"}))
		return
	}
	v, err := h.s.Cart.SelectAll(r.Context(), callerFrom(r.Context()), *b.Selected)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) saveForLater(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Cart.SaveForLater(r.Context(), callerFrom(r.Context()), r.PathValue("lineId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) moveToCart(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Cart.MoveToCart(r.Context(), callerFrom(r.Context()), r.PathValue("savedId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) removeSaved(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Cart.RemoveSaved(r.Context(), callerFrom(r.Context()), r.PathValue("savedId"))
	h.reply(w, r, http.StatusOK, v, err)
}

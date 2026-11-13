package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerMatchService is "Add a product" for a seller (docs/engineering/product-matching-without-barcode.md): one search box (is
// the product already on AfroTransact? up to six pages, "This is my product" or "Not here, add it as new"), the optional barcode
// shortcut, the price-and-stock form for a page that exists, the
// suggest-a-change, wrong-product and duplicate reports, and the Improve-your-image tasks of live listings (section 6.7).
// catalog owns the page and the checks; offers owns the offer and its tax hold. The BFF checks the Listings module,
// validates what it can cheaply (an offer never carries page content), adds the seller count and relays plain answers.
type SellerMatchService interface {
	// Match is the barcode shortcut: catalog POST /v1/catalog/matches for the active organisation, with the number of sellers from offers.
	Match(ctx context.Context, c *domain.Caller, in domain.MatchInput) (domain.CatalogMatch, error)
	// Candidates is catalog POST /v1/catalog/matches/candidates: the pages that look like the typed name, with the number of sellers.
	Candidates(ctx context.Context, c *domain.Caller, in domain.CandidateInput) (domain.CandidateList, error)
	// Decide is catalog POST /v1/catalog/matches/decisions: "This is my product" (size confirmed) or "Not here, add it as new".
	Decide(ctx context.Context, c *domain.Caller, in domain.DecisionInput) (domain.MatchDecision, error)
	// CreateOffer is offers POST /v1/offers for a page that exists; body is the request's JSON (page content is refused).
	CreateOffer(ctx context.Context, c *domain.Caller, idempotencyKey string, body json.RawMessage) (domain.OfferOnlyResult, error)
	// FileChangeRequest is catalog POST /v1/products/{atin}/change-requests.
	FileChangeRequest(ctx context.Context, c *domain.Caller, atin string, in domain.ChangeRequestInput) (domain.ChangeRequest, error)
	// ChangeRequests are the organisation's own requests with the decision and its reason.
	ChangeRequests(ctx context.Context, c *domain.Caller, status string, page, size int) (domain.ChangeRequestPage, error)
	// WithdrawChangeRequest withdraws a request that is still waiting.
	WithdrawChangeRequest(ctx context.Context, c *domain.Caller, id string) (domain.ChangeRequest, error)
	// ImageTasks are the Improve-your-image tasks of the organisation's live listings.
	ImageTasks(ctx context.Context, c *domain.Caller) (domain.ImageTaskList, error)
}

type sellerMatchImpl struct {
	r     *orgResolver
	cache *scanCache[domain.ListingRow]
}

var _ SellerMatchService = (*sellerMatchImpl)(nil)

// NewSellerMatchService returns the SellerMatchService; d.ScopeCatalog is catalog (nil answers 503), d.Offers is offers
// and d.OfferListings the public buy boxes the seller count comes from (optional).
func NewSellerMatchService(d SellerDeps) SellerMatchService {
	return &sellerMatchImpl{r: newResolver(d), cache: d.listingCache()}
}

// Limits of the match request and of an offer.
const (
	// MaxMatchBodyBytes bounds the small JSON bodies of the matching routes.
	MaxMatchBodyBytes     = 16 << 10
	maxOfferMinor         = int64(100_000_000)
	maxOfferQuantity      = 100_000
	maxOfferHandlingDays  = 30
	maxConditionNote      = 200
	maxChangeNote         = 1000
	maxChangeValue        = 5000
	taxHeldMessage        = "We are checking the tax details for this item."
	offerLiveMessage      = "Your offer is live."
	contentLockedMessage  = "That detail belongs to the product page. Suggest a change instead."
	badBarcodeMessage     = "Barcodes have 8, 12, 13 or 14 digits."
	defaultHandlingDays   = 2
	offerSKUMaxLen        = 40
	changeRequestPageSize = 100
)

var (
	barcodeRe      = regexp.MustCompile(`^(\d{8}|\d{12,14}|\d{9}[\dXx])$`)
	barcodeStripRe = regexp.MustCompile(`[\s-]`)
	offerConds     = []string{"NEW", "RENEWED", "USED_LIKE_NEW", "USED_VERY_GOOD", "USED_GOOD", "USED_ACCEPTABLE"}
	// pageContentFields belong to the page, never to an offer (docs section 3.4).
	pageContentFields = []string{"title", "description", "bullets", "attributes", "attrs", "brand", "category", "categoryId", "categoryPath", "productType",
		"productTypeCode", "gtin", "gtinExempt", "variationTheme", "variants", "options", "images", "image", "taxFacts", "facts", "keywords", "sizeChartId", "mpn"}
	offerFields = []string{"atin", "sellerSku", "condition", "conditionNote", "priceMinor", "listPriceMinor", "shippingMinor", "fulfilment", "quantity",
		"handlingDays", "shipFromLocationId", "packaging"}
)

// matchProblems keeps the plain refusals of catalog and offers under a stable code of their own, so Seller Center can
// show the right state (not "something went wrong").
var matchProblems = map[string]struct {
	status int
	code   string
}{
	"offers.offer-exists":               {http.StatusConflict, "OFFER_EXISTS"},
	"offers.selling-approval-required":  {http.StatusUnprocessableEntity, "SELLING_APPROVAL_REQUIRED"},
	"offers.listing-limit-reached":      {http.StatusUnprocessableEntity, "LISTING_LIMIT_REACHED"},
	"offers.brand-unavailable":          {http.StatusUnprocessableEntity, "BRAND_UNAVAILABLE"},
	"offers.page-content-locked":        {http.StatusUnprocessableEntity, "PAGE_CONTENT_LOCKED"},
	"catalog.wrong-match-open":          {http.StatusConflict, "WRONG_MATCH_OPEN"},
	"catalog.match-rate-limited":        {http.StatusTooManyRequests, "RATE_LIMITED"},
	"catalog.match-size-unconfirmed":    {http.StatusUnprocessableEntity, "SIZE_UNCONFIRMED"},
	"catalog.match-choice-not-shown":    {http.StatusUnprocessableEntity, "CHOICE_NOT_SHOWN"},
	"catalog.match-choice-missing":      {http.StatusUnprocessableEntity, domain.CodeValidationFailed},
	"catalog.change-request-exists":     {http.StatusConflict, "CHANGE_REQUEST_EXISTS"},
	"catalog.not-your-offer":            {http.StatusForbidden, "NOT_YOUR_OFFER"},
	"catalog.change-request-decided":    {http.StatusConflict, "CHANGE_REQUEST_DECIDED"},
	"catalog.change-request-not-found":  {http.StatusNotFound, "NOT_FOUND"},
	"catalog.product-not-found":         {http.StatusNotFound, "NOT_FOUND"},
	"catalog.variant-not-found":         {http.StatusNotFound, "NOT_FOUND"},
	"offers.product-not-found":          {http.StatusNotFound, "NOT_FOUND"},
	"offers.condition-not-allowed":      {http.StatusUnprocessableEntity, domain.CodeValidationFailed},
	"offers.condition-note-required":    {http.StatusUnprocessableEntity, domain.CodeValidationFailed},
	"offers.package-not-allowed":        {http.StatusUnprocessableEntity, domain.CodeValidationFailed},
	"catalog.change-request-invalid":    {http.StatusUnprocessableEntity, domain.CodeValidationFailed},
	"catalog.change-request-not-yours":  {http.StatusForbidden, "NOT_YOUR_OFFER"},
	"catalog.department-not-allowed":    {http.StatusForbidden, "catalog.department-not-allowed"},
	"catalog.no-departments":            {http.StatusForbidden, "catalog.no-departments"},
	"catalog.change-request-image-fail": {http.StatusUnprocessableEntity, domain.CodeValidationFailed},
}

// matchErr maps an upstream refusal; the codes in matchProblems keep their own plain detail.
func matchErr(upstream string, err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		if m, known := matchProblems[p.Code]; known {
			if m.code == domain.CodeValidationFailed && len(p.Fields) > 0 {
				return domain.ValidationProblem(p.Fields).WithCause(err)
			}
			if p.Detail != "" {
				return domain.NewProblem(m.status, m.code, "", p.Detail).WithCause(err)
			}
		}
	}
	return mapErr(upstream, err)
}

func (s *sellerMatchImpl) catalog() (port.StaffUpstream, error) {
	if s.r.d.ScopeCatalog == nil {
		return nil, domain.UpstreamUnavailable("catalog")
	}
	return s.r.d.ScopeCatalog, nil
}

// ---------------------------------------------------------------- match

// upstreamMatch is catalog's MatchResult.
type upstreamMatch struct {
	Result        string `json:"result"`
	Digits        string `json:"digits"`
	ATIN          string `json:"atin"`
	ParentATIN    string `json:"parentAtin"`
	ProductStatus string `json:"productStatus"`
	Title         string `json:"title"`
	Brand         string `json:"brand"`
	Image         *struct {
		URL    string `json:"url"`
		Width  *int   `json:"width"`
		Height *int   `json:"height"`
	} `json:"image"`
	Category       string                `json:"category"`
	VariantOptions map[string]string     `json:"variantOptions"`
	Variants       []domain.MatchVariant `json:"variants"`
	CanOffer       bool                  `json:"canOffer"`
	Blockers       []domain.Blocker      `json:"blockers"`
	CanCreate      bool                  `json:"canCreate"`
	CreateBlockers []domain.Blocker      `json:"createBlockers"`
	Suggestion     string                `json:"suggestion"`
	Message        string                `json:"message"`
}

func (s *sellerMatchImpl) Match(ctx context.Context, c *domain.Caller, in domain.MatchInput) (domain.CatalogMatch, error) {
	in.GTIN = strings.TrimSpace(in.GTIN)
	errs := map[string]string{}
	switch {
	case in.GTIN == "":
		errs["gtin"] = "Enter the barcode"
	case utf8.RuneCountInString(in.GTIN) > 20:
		errs["gtin"] = "At most 20 characters"
	}
	if len(errs) > 0 {
		return domain.CatalogMatch{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.CatalogMatch{}, err
	}
	if !barcodeRe.MatchString(barcodeStripRe.ReplaceAllString(in.GTIN, "")) {
		// Too short or not digits: an answer, not an error (the field asks again), like the GTIN check.
		digits := barcodeStripRe.ReplaceAllString(in.GTIN, "")
		return domain.CatalogMatch{Result: domain.MatchInvalid, Digits: strPtr(digits), Blockers: []domain.Blocker{}, CreateBlockers: []domain.Blocker{},
			Message: strPtr(badBarcodeMessage), Variants: []domain.MatchVariant{}}, nil
	}
	up, err := s.catalog()
	if err != nil {
		return domain.CatalogMatch{}, err
	}
	var m upstreamMatch
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodPost, Path: "/v1/catalog/matches", Body: map[string]string{"gtin": in.GTIN}}, &m); err != nil {
		return domain.CatalogMatch{}, matchErr("catalog", err)
	}
	out := domain.CatalogMatch{Result: m.Result, Digits: strPtr(m.Digits), ATIN: strPtr(m.ATIN), ParentATIN: strPtr(m.ParentATIN),
		ProductStatus: strPtr(m.ProductStatus), Title: strPtr(m.Title), Brand: strPtr(m.Brand), Category: strPtr(m.Category), VariantOptions: m.VariantOptions,
		Variants: m.Variants, CanOffer: m.CanOffer, Blockers: m.Blockers, CanCreate: m.CanCreate, CreateBlockers: m.CreateBlockers,
		Suggestion: strPtr(m.Suggestion), Message: strPtr(m.Message)}
	if m.Image != nil && m.Image.URL != "" {
		out.Image = &domain.MatchImage{URL: m.Image.URL, Width: m.Image.Width, Height: m.Image.Height}
	}
	if out.Variants == nil {
		out.Variants = []domain.MatchVariant{}
	}
	if out.Blockers == nil {
		out.Blockers = []domain.Blocker{}
	}
	if out.CreateBlockers == nil {
		out.CreateBlockers = []domain.Blocker{}
	}
	if m.Result == domain.MatchFound && m.ATIN != "" && s.r.d.OfferListings != nil {
		// The number of sellers is best effort: the card shows without it when offers cannot be read.
		if box, berr := s.r.d.OfferListings.BuyBox(ctx, m.ATIN); berr == nil {
			out.SellerCount = ptr(max(0, box.OfferCount))
		}
	}
	return out, nil
}

// ---------------------------------------------------------------- search-first candidates

const (
	maxCandidateQuery  = 200
	maxCandidateSeller = 6
)

// upstreamCandidates is catalog's CandidateSearchResult.
type upstreamCandidates struct {
	Candidates []struct {
		ParentATIN string `json:"parentAtin"`
		ATIN       string `json:"atin"`
		Title      string `json:"title"`
		Brand      string `json:"brand"`
		Image      *struct {
			URL    string `json:"url"`
			Width  *int   `json:"width"`
			Height *int   `json:"height"`
		} `json:"image"`
		Category  string                `json:"category"`
		SizeLabel string                `json:"sizeLabel"`
		Variants  []domain.MatchVariant `json:"variants"`
		Kind      string                `json:"kind"`
		Likely    bool                  `json:"likely"`
		CanOffer  bool                  `json:"canOffer"`
		Blockers  []domain.Blocker      `json:"blockers"`
	} `json:"candidates"`
	SuggestedCategory *domain.MatchCategory `json:"suggestedCategory"`
	Category          *domain.MatchCategory `json:"category"`
	TooShort          bool                  `json:"tooShort"`
	SearchUnavailable bool                  `json:"searchUnavailable"`
}

func (s *sellerMatchImpl) Candidates(ctx context.Context, c *domain.Caller, in domain.CandidateInput) (domain.CandidateList, error) {
	in.Query, in.CategoryID = strings.Join(strings.Fields(in.Query), " "), strings.TrimSpace(in.CategoryID)
	errs := map[string]string{}
	switch {
	case in.Query == "":
		errs["query"] = "Type what you are selling"
	case utf8.RuneCountInString(in.Query) > maxCandidateQuery:
		errs["query"] = "At most 200 characters"
	}
	if in.CategoryID != "" && !uuidRe.MatchString(in.CategoryID) {
		errs["categoryId"] = "Choose a category from the list"
	}
	if len(errs) > 0 {
		return domain.CandidateList{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.CandidateList{}, err
	}
	up, err := s.catalog()
	if err != nil {
		return domain.CandidateList{}, err
	}
	body := map[string]string{"query": in.Query}
	if in.CategoryID != "" {
		body["categoryId"] = in.CategoryID
	}
	var m upstreamCandidates
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodPost, Path: "/v1/catalog/matches/candidates", Body: body}, &m); err != nil {
		return domain.CandidateList{}, matchErr("catalog", err)
	}
	out := domain.CandidateList{Candidates: []domain.MatchCandidate{}, SuggestedCategory: m.SuggestedCategory, Category: m.Category, TooShort: m.TooShort, SearchUnavailable: m.SearchUnavailable}
	for i, x := range m.Candidates {
		if i >= maxCandidateSeller {
			break
		}
		cand := domain.MatchCandidate{ParentATIN: x.ParentATIN, ATIN: strPtr(x.ATIN), Title: x.Title, Brand: strPtr(x.Brand), Category: strPtr(x.Category),
			SizeLabel: strPtr(x.SizeLabel), Variants: x.Variants, Kind: x.Kind, Likely: x.Likely, CanOffer: x.CanOffer, Blockers: x.Blockers}
		if x.Image != nil && x.Image.URL != "" {
			cand.Image = &domain.MatchImage{URL: x.Image.URL, Width: x.Image.Width, Height: x.Image.Height}
		}
		if cand.Variants == nil {
			cand.Variants = []domain.MatchVariant{}
		}
		if cand.Blockers == nil {
			cand.Blockers = []domain.Blocker{}
		}
		if s.r.d.OfferListings != nil && len(x.Variants) > 0 {
			// The number of sellers is best effort: the card shows without it when offers cannot be read.
			if box, berr := s.r.d.OfferListings.BuyBox(ctx, x.Variants[0].ATIN); berr == nil {
				cand.SellerCount = ptr(max(0, box.OfferCount))
			}
		}
		out.Candidates = append(out.Candidates, cand)
	}
	return out, nil
}

func (s *sellerMatchImpl) Decide(ctx context.Context, c *domain.Caller, in domain.DecisionInput) (domain.MatchDecision, error) {
	in.Query, in.CategoryID, in.ATIN = strings.Join(strings.Fields(in.Query), " "), strings.TrimSpace(in.CategoryID), strings.ToUpper(strings.TrimSpace(in.ATIN))
	errs := map[string]string{}
	if in.Choice != domain.ChoiceSellExisting && in.Choice != domain.ChoiceNoneOfThese {
		errs["choice"] = "Choose This is my product or Not here"
	}
	if in.Query == "" || utf8.RuneCountInString(in.Query) > maxCandidateQuery {
		errs["query"] = "Type what you are selling"
	}
	if in.CategoryID != "" && !uuidRe.MatchString(in.CategoryID) {
		errs["categoryId"] = "Choose a category from the list"
	}
	if in.Choice == domain.ChoiceSellExisting && !atinRe.MatchString(in.ATIN) {
		errs["atin"] = "Choose the product you are selling"
	}
	if len(errs) > 0 {
		return domain.MatchDecision{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.MatchDecision{}, err
	}
	up, err := s.catalog()
	if err != nil {
		return domain.MatchDecision{}, err
	}
	body := map[string]any{"choice": in.Choice, "query": in.Query}
	if in.CategoryID != "" {
		body["categoryId"] = in.CategoryID
	}
	if in.Choice == domain.ChoiceSellExisting {
		body["atin"], body["sizeConfirmed"] = in.ATIN, in.SizeConfirmed
	}
	var out domain.MatchDecision
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodPost, Path: "/v1/catalog/matches/decisions", Body: body}, &out); err != nil {
		return domain.MatchDecision{}, matchErr("catalog", err)
	}
	if out.Blockers == nil {
		out.Blockers = []domain.Blocker{}
	}
	return out, nil
}

// ---------------------------------------------------------------- offer

func (s *sellerMatchImpl) CreateOffer(ctx context.Context, c *domain.Caller, key string, body json.RawMessage) (domain.OfferOnlyResult, error) {
	var raw map[string]json.RawMessage
	if len(body) > MaxMatchBodyBytes || json.Unmarshal(body, &raw) != nil || raw == nil {
		return domain.OfferOnlyResult{}, domain.ValidationProblem(map[string]string{"_": "Send the offer as a JSON object"})
	}
	// An offer sells a page that exists: page content is refused by name, any other unknown field too.
	errs := map[string]string{}
	for k := range raw {
		switch {
		case slices.Contains(pageContentFields, k):
			errs[k] = contentLockedMessage
		case !slices.Contains(offerFields, k):
			errs[k] = "Not an offer detail"
		}
	}
	if len(errs) > 0 {
		pr := domain.NewProblem(http.StatusUnprocessableEntity, "PAGE_CONTENT_LOCKED", "Validation failed", contentLockedMessage)
		pr.Errors = errs
		return domain.OfferOnlyResult{}, pr
	}
	var in domain.OfferOnlyInput
	if err := json.Unmarshal(body, &in); err != nil {
		return domain.OfferOnlyResult{}, domain.ValidationProblem(map[string]string{"_": "Some offer details are not valid"})
	}
	payload, err := offerPayload(&in)
	if err != nil {
		return domain.OfferOnlyResult{}, err
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.OfferOnlyResult{}, err
	}
	if s.r.d.Offers == nil {
		return domain.OfferOnlyResult{}, domain.UpstreamUnavailable("offers")
	}
	if s.cache != nil {
		defer s.cache.drop("listings")
	}
	o, err := s.r.d.Offers.Create(ctx, c.BearerToken, strings.TrimSpace(key), payload)
	if err != nil {
		return domain.OfferOnlyResult{}, matchErr("offers", err)
	}
	status := firstNonEmpty(strings.ToUpper(o.Status), domain.OfferActive)
	res := domain.OfferOnlyResult{OfferID: o.ID, ATIN: firstNonEmpty(o.ATIN, in.ATIN), SellerSKU: firstNonEmpty(o.SellerSKU, in.SellerSKU), Status: status,
		HeldReason: strPtr(o.HeldReason), Message: offerLiveMessage}
	if status == domain.OfferTaxHeld {
		res.Message = taxHeldMessage
	}
	return res, nil
}

// offerPayload checks the offer's terms and builds offers' CreateOfferRequest (the organisation is the token's, never sent).
func offerPayload(in *domain.OfferOnlyInput) (map[string]any, error) {
	errs := map[string]string{}
	in.ATIN = strings.ToUpper(strings.TrimSpace(in.ATIN))
	if !atinRe.MatchString(in.ATIN) {
		errs["atin"] = "Choose the product you are selling"
	}
	in.Condition = strings.ToUpper(strings.TrimSpace(in.Condition))
	if in.Condition == "" {
		in.Condition = "NEW"
	}
	if !slices.Contains(offerConds, in.Condition) {
		errs["condition"] = "Choose a condition from the list"
	}
	in.ConditionNote = strings.TrimSpace(in.ConditionNote)
	switch {
	case in.Condition != "NEW" && in.ConditionNote == "":
		errs["conditionNote"] = "Describe the condition"
	case utf8.RuneCountInString(in.ConditionNote) > maxConditionNote:
		errs["conditionNote"] = "At most 200 characters"
	}
	if in.PriceMinor <= 0 || in.PriceMinor > maxOfferMinor {
		errs["priceMinor"] = "Enter a price above zero"
	}
	if in.ListPriceMinor != nil && (*in.ListPriceMinor < in.PriceMinor || *in.ListPriceMinor > maxOfferMinor) {
		errs["listPriceMinor"] = "The list price can't be lower than the price"
	}
	if in.ShippingMinor != nil && (*in.ShippingMinor < 0 || *in.ShippingMinor > maxOfferMinor) {
		errs["shippingMinor"] = "Enter zero or more"
	}
	in.Fulfilment = strings.ToUpper(strings.TrimSpace(in.Fulfilment))
	if !slices.Contains(offerFulfilments, in.Fulfilment) {
		errs["fulfilment"] = "Choose how the item gets to the shopper"
	}
	switch {
	case in.Fulfilment == domain.ShipsFromSeller && in.Quantity == nil:
		errs["quantity"] = "Enter how many you have"
	case in.Quantity != nil && (*in.Quantity < 0 || *in.Quantity > maxOfferQuantity):
		errs["quantity"] = "Between 0 and 100,000"
	}
	days := defaultHandlingDays
	if in.HandlingDays != nil {
		days = *in.HandlingDays
	}
	if days < 0 || days > maxOfferHandlingDays {
		errs["handlingDays"] = "Between 0 and 30 days"
	}
	in.ShipFromLocationID = strings.TrimSpace(in.ShipFromLocationID)
	if len(in.ShipFromLocationID) > 64 {
		errs["shipFromLocationId"] = "Choose a ship-from location"
	}
	if in.Fulfilment == domain.AfroTransactDelivery && in.ShipFromLocationID == "" {
		errs["shipFromLocationId"] = "Choose the store AfroTransact picks up from"
	}
	if p := in.Packaging; p != nil {
		switch {
		case in.Fulfilment != domain.FulfilledByAfroTransact:
			errs["packaging"] = "The package is only for Fulfilled by AfroTransact"
		case p.LengthIn <= 0 || p.WidthIn <= 0 || p.HeightIn <= 0 || p.WeightLb <= 0 || p.LengthIn > 108 || p.WidthIn > 108 || p.HeightIn > 108 || p.WeightLb > 150:
			errs["packaging"] = "Enter the box size (up to 108 in) and weight (up to 150 lb)"
		}
	}
	in.SellerSKU = strings.TrimSpace(in.SellerSKU)
	if utf8.RuneCountInString(in.SellerSKU) > offerSKUMaxLen {
		errs["sellerSku"] = "At most 40 characters"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	if in.SellerSKU == "" {
		// Generated when blank (like the product form): the item's ATIN and the condition keep two conditions apart.
		in.SellerSKU = in.ATIN + "-" + strings.ReplaceAll(in.Condition, "_", "")
	}
	body := map[string]any{"atin": in.ATIN, "sellerSku": in.SellerSKU, "condition": in.Condition, "priceMinor": in.PriceMinor, "currency": domain.Currency,
		"fulfilment": in.Fulfilment, "handlingDays": days}
	if in.ConditionNote != "" {
		body["conditionNote"] = in.ConditionNote
	}
	if in.ListPriceMinor != nil {
		body["listPriceMinor"] = *in.ListPriceMinor
	}
	if in.ShippingMinor != nil {
		body["shippingMinor"] = *in.ShippingMinor
	}
	if in.Quantity != nil {
		body["quantity"] = *in.Quantity
	}
	if in.ShipFromLocationID != "" {
		body["shipFrom"] = map[string]any{"locationId": in.ShipFromLocationID}
	}
	if p := in.Packaging; p != nil {
		body["packaging"] = map[string]any{"lengthIn": p.LengthIn, "widthIn": p.WidthIn, "heightIn": p.HeightIn, "weightLb": p.WeightLb}
	}
	return body, nil
}

// ---------------------------------------------------------------- change requests

// upstreamChange is catalog's ChangeRequest.
type upstreamChange struct {
	ID          string         `json:"id"`
	ATIN        string         `json:"atin"`
	Title       string         `json:"title"`
	Type        string         `json:"type"`
	Field       string         `json:"field"`
	Payload     map[string]any `json:"payload"`
	Note        string         `json:"note"`
	Status      string         `json:"status"`
	RequestedAt time.Time      `json:"requestedAt"`
	SLADueAt    *time.Time     `json:"slaDueAt"`
	DecidedAt   *time.Time     `json:"decidedAt"`
	Reason      string         `json:"reason"`
	Resolution  string         `json:"resolution"`
}

func changeOf(u upstreamChange) domain.ChangeRequest {
	return domain.ChangeRequest{ID: u.ID, ATIN: u.ATIN, Title: strPtr(u.Title), Type: u.Type, Field: strPtr(u.Field), Payload: u.Payload, Note: strPtr(u.Note),
		Status: firstNonEmpty(strings.ToUpper(u.Status), "PENDING"), RequestedAt: u.RequestedAt, SLADueAt: u.SLADueAt, DecidedAt: u.DecidedAt,
		Reason: strPtr(u.Reason), Resolution: strPtr(u.Resolution)}
}

// payloadValueTypes are the types whose payload is {value}.
var payloadValueTypes = []string{"TITLE", "DESCRIPTION", "BRAND"}

func validateChange(in *domain.ChangeRequestInput) map[string]string {
	errs := map[string]string{}
	in.Type = strings.ToUpper(strings.TrimSpace(in.Type))
	in.Note = strings.TrimSpace(in.Note)
	in.Field = strings.TrimSpace(in.Field)
	if !slices.Contains(domain.ChangeRequestTypes, in.Type) {
		errs["type"] = "Choose what you want to report"
		return errs
	}
	if utf8.RuneCountInString(in.Note) > maxChangeNote {
		errs["note"] = "At most 1,000 characters"
	}
	if len(in.Field) > 60 {
		errs["field"] = "At most 60 characters"
	}
	text := func(key string) string {
		v, _ := in.Payload[key].(string)
		return strings.TrimSpace(v)
	}
	switch {
	case slices.Contains(payloadValueTypes, in.Type):
		if v := text("value"); v == "" {
			errs["payload.value"] = "Enter the new text"
		} else if utf8.RuneCountInString(v) > maxChangeValue {
			errs["payload.value"] = "At most 5,000 characters"
		}
	case in.Type == "ATTRIBUTE":
		if text("key") == "" {
			errs["payload.key"] = "Choose the detail"
		}
		if text("value") == "" {
			errs["payload.value"] = "Enter the new value"
		}
	case in.Type == "CATEGORY":
		if text("categoryId") == "" {
			errs["payload.categoryId"] = "Choose the category"
		}
	case in.Type == "WRONG_MATCH":
		if text("variantAtin") == "" && text("gtin") == "" {
			errs["payload.variantAtin"] = "Say which product this is about"
		}
	case in.Type == "DUPLICATE_PAGE":
		if !atinRe.MatchString(strings.ToUpper(text("duplicateOfAtin"))) {
			errs["payload.duplicateOfAtin"] = "Enter the product number of the other page"
		}
	}
	return errs
}

func (s *sellerMatchImpl) FileChangeRequest(ctx context.Context, c *domain.Caller, atin string, in domain.ChangeRequestInput) (domain.ChangeRequest, error) {
	atin = strings.ToUpper(strings.TrimSpace(atin))
	if !atinRe.MatchString(atin) {
		return domain.ChangeRequest{}, taxNotFound("product")
	}
	if errs := validateChange(&in); len(errs) > 0 {
		return domain.ChangeRequest{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.ChangeRequest{}, err
	}
	up, err := s.catalog()
	if err != nil {
		return domain.ChangeRequest{}, err
	}
	var out upstreamChange
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodPost, Path: "/v1/products/" + url.PathEscape(atin) + "/change-requests", Body: in}, &out); err != nil {
		return domain.ChangeRequest{}, matchErr("catalog", err)
	}
	return changeOf(out), nil
}

func (s *sellerMatchImpl) ChangeRequests(ctx context.Context, c *domain.Caller, status string, page, size int) (domain.ChangeRequestPage, error) {
	errs := map[string]string{}
	status = strings.ToUpper(strings.TrimSpace(status))
	if status != "" && !slices.Contains(domain.ChangeRequestStatuses, status) {
		errs["status"] = "PENDING, APPLIED, REJECTED or WITHDRAWN"
	}
	if size == 0 {
		size = 25
	}
	if page < 0 {
		errs["page"] = "Must be 0 or more"
	}
	if size < 1 || size > changeRequestPageSize {
		errs["size"] = "Between 1 and 100"
	}
	if len(errs) > 0 {
		return domain.ChangeRequestPage{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView); err != nil {
		return domain.ChangeRequestPage{}, err
	}
	up, err := s.catalog()
	if err != nil {
		return domain.ChangeRequestPage{}, err
	}
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if status != "" {
		q.Set("status", status)
	}
	var p struct {
		Items []upstreamChange `json:"items"`
		Total int64            `json:"total"`
		Page  int              `json:"page"`
		Size  int              `json:"size"`
	}
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodGet, Path: "/v1/change-requests/mine", Query: q}, &p); err != nil {
		return domain.ChangeRequestPage{}, matchErr("catalog", err)
	}
	out := domain.ChangeRequestPage{Items: make([]domain.ChangeRequest, 0, len(p.Items)), Total: p.Total, Page: p.Page, Size: firstPositive(p.Size, size)}
	for _, it := range p.Items {
		out.Items = append(out.Items, changeOf(it))
	}
	return out, nil
}

func (s *sellerMatchImpl) WithdrawChangeRequest(ctx context.Context, c *domain.Caller, id string) (domain.ChangeRequest, error) {
	if !taxUUIDRe.MatchString(id) {
		return domain.ChangeRequest{}, taxNotFound("request")
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.ChangeRequest{}, err
	}
	up, err := s.catalog()
	if err != nil {
		return domain.ChangeRequest{}, err
	}
	var out upstreamChange
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodPost, Path: "/v1/change-requests/" + url.PathEscape(id) + "/withdraw"}, &out); err != nil {
		return domain.ChangeRequest{}, matchErr("catalog", err)
	}
	return changeOf(out), nil
}

// ---------------------------------------------------------------- image tasks

func (s *sellerMatchImpl) ImageTasks(ctx context.Context, c *domain.Caller) (domain.ImageTaskList, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView); err != nil {
		return domain.ImageTaskList{}, err
	}
	up, err := s.catalog()
	if err != nil {
		return domain.ImageTaskList{}, err
	}
	var in struct {
		Items []domain.ImageTask `json:"items"`
	}
	if err := up.Call(ctx, c.BearerToken, port.StaffCall{Method: http.MethodGet, Path: "/v1/products/image-tasks"}, &in); err != nil {
		return domain.ImageTaskList{}, matchErr("catalog", err)
	}
	out := domain.ImageTaskList{Items: make([]domain.ImageTask, 0, len(in.Items))}
	for _, t := range in.Items {
		if t.Reasons == nil {
			t.Reasons = []domain.ImageTaskReason{}
		}
		out.Items = append(out.Items, t)
	}
	return out, nil
}

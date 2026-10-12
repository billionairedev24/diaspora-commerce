package app

import (
	"context"
	"net/http"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerListingService serves the one-step product form (S307): load a listing for editing, submit a new product,
// save an edit, autosaved drafts, what the form offers this member (AI listing writer, moderation) and the writer.
type SellerListingService interface {
	Product(ctx context.Context, c *domain.Caller, productID string) (domain.SellerProductDetail, error)
	Submit(ctx context.Context, c *domain.Caller, d domain.ProductDraft) (domain.ListingRow, error)
	Update(ctx context.Context, c *domain.Caller, productID string, d domain.ProductDraft) (domain.ListingRow, error)
	Drafts(ctx context.Context, c *domain.Caller) (domain.ProductDraftList, error)
	Draft(ctx context.Context, c *domain.Caller, id string) (domain.SavedProductDraft, error)
	CreateDraft(ctx context.Context, c *domain.Caller, in domain.SaveProductDraftRequest) (domain.SavedProductDraft, error)
	SaveDraft(ctx context.Context, c *domain.Caller, id string, in domain.SaveProductDraftRequest) (domain.SavedProductDraft, error)
	DeleteDraft(ctx context.Context, c *domain.Caller, id string) error
	Context(ctx context.Context, c *domain.Caller) (domain.ListingContext, error)
	Write(ctx context.Context, c *domain.Caller, in domain.ListingWriterRequest) (domain.ListingWriterDraft, error)
}

type sellerListingImpl struct {
	r     *orgResolver
	cache *scanCache[domain.ListingRow]
}

var _ SellerListingService = (*sellerListingImpl)(nil)

// NewSellerListingService returns the SellerListingService (SellerDeps.Listing must be set).
func NewSellerListingService(d SellerDeps) SellerListingService {
	r := newResolver(d)
	return &sellerListingImpl{r: r, cache: newScanCache[domain.ListingRow](r.d.clock())}
}

func (s *sellerListingImpl) listing() (port.SellerListingClient, error) {
	if s.r.d.Listing == nil {
		return nil, domain.UpstreamUnavailable("catalog")
	}
	return s.r.d.Listing, nil
}

// shipFrom resolves a ship-from address id to the snapshot catalog stores (nil id: the seller's default).
func (s *sellerListingImpl) shipFrom(ctx context.Context, c *domain.Caller, orgID string, id *string) (*port.CatalogShipFrom, error) {
	if id == nil || strings.TrimSpace(*id) == "" {
		return nil, nil
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, orgID)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	for _, a := range list {
		if a.ID == *id {
			return &port.CatalogShipFrom{LocationID: a.ID, Label: a.Label, City: a.City, Region: a.State,
				Country: firstNonEmpty(a.Country, "US"), PostalCode: a.PostalCode}, nil
		}
	}
	return nil, domain.ValidationProblem(map[string]string{"listing.shipFromId": "Choose one of your ship-from addresses"})
}

func (s *sellerListingImpl) Submit(ctx context.Context, c *domain.Caller, d domain.ProductDraft) (domain.ListingRow, error) {
	if errs := d.Validate(); len(errs) > 0 {
		return domain.ListingRow{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit)
	if err != nil {
		return domain.ListingRow{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.ListingRow{}, err
	}
	var shipFromID *string
	if d.Listing != nil {
		shipFromID = d.Listing.ShipFromID
	}
	ship, err := s.shipFrom(ctx, c, sc.org.ID, shipFromID)
	if err != nil {
		return domain.ListingRow{}, err
	}
	p, err := l.CreateFull(ctx, c.BearerToken, d, ship)
	if err != nil {
		return domain.ListingRow{}, mapErr("catalog", err)
	}
	row := listingOf(p)
	if row.Status == "" {
		row.Status = "IN_REVIEW"
		if d.CategoryRequestID != "" {
			row.Status = "PENDING_CATEGORY_APPROVAL"
		}
		row.StatusLabel = domain.ListingStatusLabel(row.Status)
	}
	return row, nil
}

func (s *sellerListingImpl) Update(ctx context.Context, c *domain.Caller, productID string, d domain.ProductDraft) (domain.ListingRow, error) {
	if errs := d.ValidateEdit(); len(errs) > 0 {
		return domain.ListingRow{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit)
	if err != nil {
		return domain.ListingRow{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.ListingRow{}, err
	}
	var shipFromID *string
	if d.Listing != nil {
		shipFromID = d.Listing.ShipFromID
	}
	ship, err := s.shipFrom(ctx, c, sc.org.ID, shipFromID)
	if err != nil {
		return domain.ListingRow{}, err
	}
	p, err := l.Update(ctx, c.BearerToken, productID, d, ship)
	if err != nil {
		return domain.ListingRow{}, mapErr("catalog", err)
	}
	return listingOf(p), nil
}

func (s *sellerListingImpl) Product(ctx context.Context, c *domain.Caller, productID string) (domain.SellerProductDetail, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView); err != nil {
		return domain.SellerProductDetail{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.SellerProductDetail{}, err
	}
	p, err := l.Product(ctx, c.BearerToken, productID)
	if err != nil {
		return domain.SellerProductDetail{}, mapErr("catalog", err)
	}
	return s.detailOf(ctx, c, p), nil
}

func (s *sellerListingImpl) detailOf(ctx context.Context, c *domain.Caller, p port.CatalogOwnProduct) domain.SellerProductDetail {
	status := strings.ToUpper(p.Status)
	out := domain.SellerProductDetail{ProductID: p.ID, ATIN: p.ATIN, Status: status, StatusLabel: domain.ListingStatusLabel(status),
		ModerationNote: strPtr(p.ModerationNote), CategoryID: strPtr(p.CategoryID), CategoryRequestID: strPtr(p.CategoryRequestID),
		ProductType: p.ProductType, Title: p.Title, Attributes: p.Attributes, Images: p.Images, VariationTheme: p.VariationTheme,
		Variants: []domain.ProductVariant{}, PreorderEligible: p.PreorderEligible, Currency: domain.Currency, Version: p.Version,
		UpdatedAt: p.UpdatedAt}
	if out.Attributes == nil {
		out.Attributes = map[string]string{}
	}
	if out.Images == nil {
		out.Images = []domain.ProductImageInput{}
	}
	for _, v := range p.Variants {
		images := v.Images
		if images == nil {
			images = []domain.ProductImageInput{}
		}
		opts := v.Options
		if opts == nil {
			opts = map[string]string{}
		}
		out.Variants = append(out.Variants, domain.ProductVariant{ATIN: v.ATIN, SellerSKU: v.SellerSKU, GTIN: strPtr(v.GTIN),
			GTINExempt: v.GTINExempt, Options: opts, PriceMinor: v.PriceMinor, CompareAtMinor: v.CompareAtMinor, Quantity: v.Quantity,
			Images: images})
		if v.Currency != "" {
			out.Currency = v.Currency
		}
	}
	out.Listing = domain.ListingDetails{Condition: "NEW", Keywords: []string{}, Fulfilment: "SELLER", HandlingDays: 2}
	if l := p.Listing; l != nil {
		out.Listing = domain.ListingDetails{Description: strPtr(l.Description), Condition: firstNonEmpty(l.Condition, "NEW"),
			Keywords: l.Keywords, Fulfilment: firstNonEmpty(l.Fulfilment, "SELLER"), Packaging: l.Packaging,
			HandlingDays: max(1, l.HandlingDays)}
		if out.Listing.Keywords == nil {
			out.Listing.Keywords = []string{}
		}
		if l.ShipFrom != nil {
			out.Listing.ShipFromID = strPtr(l.ShipFrom.LocationID)
		}
	}
	// The category's path for the picker (catalog stores the id); a requested category shows its request's path.
	if p.CategoryID != "" {
		if nodes, err := s.r.d.Catalog.CategoryTree(ctx); err == nil {
			for _, n := range nodes {
				if n.ID == p.CategoryID {
					out.CategoryPath = n.Path
				}
			}
		}
	} else if p.CategoryRequestID != "" {
		if reqs, err := s.r.d.Catalog.CategoryRequests(ctx, c.BearerToken); err == nil {
			for _, r := range reqs {
				if r.ID == p.CategoryRequestID {
					out.CategoryPath = r.Path
				}
			}
		}
	}
	return out
}

func draftSummaryOf(d port.CatalogDraft) domain.ProductDraftSummary {
	return domain.ProductDraftSummary{ID: d.ID, ProductID: strPtr(d.ProductID), Title: d.Title, CategoryPath: strPtr(d.CategoryPath),
		UpdatedAt: d.UpdatedAt, Version: d.Version}
}

func savedDraftOf(d port.CatalogDraft) domain.SavedProductDraft {
	payload := d.Payload
	if len(payload) == 0 {
		payload = []byte("{}")
	}
	return domain.SavedProductDraft{ProductDraftSummary: draftSummaryOf(d), Payload: payload}
}

func (s *sellerListingImpl) Drafts(ctx context.Context, c *domain.Caller) (domain.ProductDraftList, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.ProductDraftList{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.ProductDraftList{}, err
	}
	list, err := l.Drafts(ctx, c.BearerToken)
	if err != nil {
		return domain.ProductDraftList{}, mapErr("catalog", err)
	}
	out := domain.ProductDraftList{Items: make([]domain.ProductDraftSummary, 0, len(list))}
	for _, d := range list {
		out.Items = append(out.Items, draftSummaryOf(d))
	}
	return out, nil
}

func (s *sellerListingImpl) Draft(ctx context.Context, c *domain.Caller, id string) (domain.SavedProductDraft, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.SavedProductDraft{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.SavedProductDraft{}, err
	}
	d, err := l.Draft(ctx, c.BearerToken, id)
	if err != nil {
		return domain.SavedProductDraft{}, mapErr("catalog", err)
	}
	return savedDraftOf(d), nil
}

func (s *sellerListingImpl) CreateDraft(ctx context.Context, c *domain.Caller, in domain.SaveProductDraftRequest) (domain.SavedProductDraft, error) {
	if errs := in.Validate(false); len(errs) > 0 {
		return domain.SavedProductDraft{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.SavedProductDraft{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.SavedProductDraft{}, err
	}
	d, err := l.CreateDraft(ctx, c.BearerToken, in)
	if err != nil {
		return domain.SavedProductDraft{}, mapErr("catalog", err)
	}
	return savedDraftOf(d), nil
}

func (s *sellerListingImpl) SaveDraft(ctx context.Context, c *domain.Caller, id string, in domain.SaveProductDraftRequest) (domain.SavedProductDraft, error) {
	if errs := in.Validate(true); len(errs) > 0 {
		return domain.SavedProductDraft{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.SavedProductDraft{}, err
	}
	l, err := s.listing()
	if err != nil {
		return domain.SavedProductDraft{}, err
	}
	d, err := l.SaveDraft(ctx, c.BearerToken, id, in)
	if err != nil {
		return domain.SavedProductDraft{}, mapErr("catalog", err)
	}
	return savedDraftOf(d), nil
}

func (s *sellerListingImpl) DeleteDraft(ctx context.Context, c *domain.Caller, id string) error {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return err
	}
	l, err := s.listing()
	if err != nil {
		return err
	}
	return mapErr("catalog", l.DeleteDraft(ctx, c.BearerToken, id))
}

// aiUsePermission is identity's permission for AI tools (organisation owners and staff).
const aiUsePermission = "ai:use"

// aiWriter decides whether the member may use the AI listing writer: an organisation owner (ai:use) on a plan
// whose features include it.
func (s *sellerListingImpl) aiWriter(ctx context.Context, c *domain.Caller, sc sellerCtx) domain.AIListingWriter {
	reason := func(r string) domain.AIListingWriter { return domain.AIListingWriter{Reason: &r} }
	if s.r.d.Writer == nil || (!slices.Contains(sc.member.Permissions, aiUsePermission) && !strings.HasSuffix(sc.org.Role, ".owner")) {
		return reason(domain.AIUnavailableRole)
	}
	b, err := s.r.d.Sellers.Billing(ctx, c.BearerToken, sc.org.ID)
	if err != nil || !slices.Contains(domain.AIListingWriterPlans, b.PlanCode) {
		return reason(domain.AIUnavailablePlan)
	}
	return domain.AIListingWriter{Available: true}
}

func (s *sellerListingImpl) Context(ctx context.Context, c *domain.Caller) (domain.ListingContext, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView)
	if err != nil {
		return domain.ListingContext{}, err
	}
	return domain.ListingContext{AIListingWriter: s.aiWriter(ctx, c, sc), Moderated: true,
		ImportLimits: domain.BulkUploadLimits}, nil
}

func (s *sellerListingImpl) Write(ctx context.Context, c *domain.Caller, in domain.ListingWriterRequest) (domain.ListingWriterDraft, error) {
	errs := map[string]string{}
	if strings.TrimSpace(in.ProductType) == "" {
		errs["productType"] = "Required"
	}
	if n := len([]rune(strings.TrimSpace(in.Title))); n < 3 || n > 200 {
		errs["title"] = "Between 3 and 200 characters"
	}
	if in.Tone != "" && !slices.Contains([]string{"warm", "concise", "premium"}, in.Tone) {
		errs["tone"] = "Unknown tone"
	}
	if len(errs) > 0 {
		return domain.ListingWriterDraft{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit)
	if err != nil {
		return domain.ListingWriterDraft{}, err
	}
	switch w := s.aiWriter(ctx, c, sc); {
	case w.Available:
	case *w.Reason == domain.AIUnavailablePlan:
		return domain.ListingWriterDraft{}, domain.NewProblem(http.StatusForbidden, domain.CodePlanFeatureRequired, "Upgrade to use it",
			"The AI listing writer comes with Seller Growth and Seller Pro.")
	default:
		return domain.ListingWriterDraft{}, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden",
			"Ask an owner of your organisation to use the AI listing writer.")
	}
	out, err := s.r.d.Writer.Write(ctx, c.BearerToken, in)
	if err != nil {
		return domain.ListingWriterDraft{}, mapErr("ai-gateway", err)
	}
	return out, nil
}

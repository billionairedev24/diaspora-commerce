package app

import (
	"context"
	"net/http"
	"regexp"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerCatalogService serves the listings table, the product form's reference data (category tree, product-type
// schema, GTIN checks, category requests, uploads) and stock (FBA positions, seller stock adjustments).
type SellerCatalogService interface {
	// Listings is the listings table: one search over every column, any sort, status (links), page and size.
	Listings(ctx context.Context, c *domain.Caller, q SellerTableQuery) (domain.ListingPage, error)
	SetActive(ctx context.Context, c *domain.Caller, productID string, active bool) (domain.ListingRow, error)
	Submit(ctx context.Context, c *domain.Caller, d domain.ProductDraft) (domain.ListingRow, error)
	Categories(ctx context.Context, c *domain.Caller) ([]domain.CategoryNode, error)
	ProductType(ctx context.Context, c *domain.Caller, code string) (domain.ProductTypeSchema, error)
	CheckGTIN(ctx context.Context, c *domain.Caller, code string, isbn bool) (domain.GtinCheck, error)
	CategoryRequests(ctx context.Context, c *domain.Caller) ([]domain.CategoryRequest, error)
	RequestCategory(ctx context.Context, c *domain.Caller, in domain.CategoryRequestInput) (domain.CategoryRequest, error)
	// Upload signs a direct image upload; library adds a product image to the image library (S310).
	Upload(ctx context.Context, c *domain.Caller, purpose, fileName, contentType string, size int64, library bool) (domain.UploadTicket, error)
	CompleteUpload(ctx context.Context, c *domain.Caller, assetID string) (domain.Asset, error)
	Inventory(ctx context.Context, c *domain.Caller, lowStock bool) (domain.InventoryView, error)
	AdjustStock(ctx context.Context, c *domain.Caller, idempotencyKey, sku string, quantity int, reason string) (domain.StockAdjustment, error)
}

type sellerCatalogImpl struct {
	r     *orgResolver
	cache *scanCache[domain.ListingRow]
}

var _ SellerCatalogService = (*sellerCatalogImpl)(nil)

// NewSellerCatalogService returns the SellerCatalogService.
func NewSellerCatalogService(d SellerDeps) SellerCatalogService {
	r := newResolver(d)
	return &sellerCatalogImpl{r: r, cache: d.listingCache()}
}

func listingOf(p port.CatalogOwnProduct) domain.ListingRow {
	row := domain.ListingRow{ProductID: p.ID, ATIN: strPtr(p.ATIN), Title: p.Title, VariantCount: len(p.Variants), Currency: domain.Currency,
		Fulfilment: domain.ShipsFromSeller, Status: strings.ToUpper(p.Status), ModerationNote: strPtr(p.ModerationNote), UpdatedAt: p.UpdatedAt}
	if p.Listing != nil {
		switch {
		case strings.EqualFold(p.Listing.Fulfilment, domain.FulfilledByAfroTransact):
			row.Fulfilment = domain.FulfilledByAfroTransact
		case strings.EqualFold(p.Listing.Fulfilment, domain.AfroTransactDelivery):
			row.Fulfilment = domain.AfroTransactDelivery
		case strings.EqualFold(p.Listing.Fulfilment, domain.ShipsFromSeller):
			row.Fulfilment = domain.ShipsFromSeller
		}
	}
	row.StatusLabel = domain.ListingStatusLabel(row.Status)
	for i, v := range p.Variants {
		if i == 0 || (v.PriceMinor > 0 && v.PriceMinor < row.PriceMinor) {
			row.PriceMinor = v.PriceMinor
		}
		if i == 0 {
			row.SKU = v.SellerSKU
			if v.Currency != "" {
				row.Currency = v.Currency
			}
		}
		row.Stock += max(0, v.Quantity)
	}
	if len(p.Images) > 0 {
		row.ImageURL = strPtr(p.Images[0].URL)
	}
	return row
}

func (s *sellerCatalogImpl) Listings(ctx context.Context, c *domain.Caller, q SellerTableQuery) (domain.ListingPage, error) {
	errs := map[string]string{}
	if q.Status != "" && !domain.IsListingStatus(q.Status) {
		errs["status"] = "Unknown status"
	}
	if q.Size == 0 {
		q.Size = 25
	}
	if q.Page < 0 {
		errs["page"] = "Must be 0 or more"
	}
	if q.Size < 1 || q.Size > 100 {
		errs["size"] = "Between 1 and 100"
	}
	if len(errs) > 0 {
		return domain.ListingPage{}, domain.ValidationProblem(errs)
	}
	sort, err := q.sort(SellerListingColumns)
	if err != nil {
		return domain.ListingPage{}, err
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView)
	if err != nil {
		return domain.ListingPage{}, err
	}
	if strings.TrimSpace(q.Q) == "" && len(sort) == 0 {
		p, err := s.r.d.Catalog.Products(ctx, c.BearerToken, port.ProductQuery{Status: q.Status, Page: q.Page, Size: q.Size})
		if err != nil {
			return domain.ListingPage{}, mapErr("catalog", err)
		}
		out := domain.ListingPage{Items: []domain.ListingRow{}, Total: p.Total, Page: q.Page, Size: q.Size, Sort: []string{}}
		for _, it := range p.Items {
			out.Items = append(out.Items, listingOf(it))
		}
		s.sellableStock(ctx, c.BearerToken, out.Items)
		return out, nil
	}
	// Search over every column and any sort: the BFF reads the organisation's listings and pages them.
	rows, partial, err := s.cache.load(sc.member.ID+"@"+sc.org.ID, "listings", q.Status, func() ([]domain.ListingRow, bool, error) {
		rows, partial, err := s.scanListings(ctx, c.BearerToken, q.Status, domain.TableScanMaxRows)
		if err == nil {
			s.sellableStock(ctx, c.BearerToken, rows)
		}
		return rows, partial, err
	})
	if err != nil {
		return domain.ListingPage{}, err
	}
	picked := pickRows(rows, SellerListingColumns, sellerListingRowCells, q.Q, nil, sort)
	items, _ := pageSlice(picked, q.Page*q.Size, q.Size)
	return domain.ListingPage{Items: items, Total: int64(len(picked)), Page: q.Page, Size: q.Size, Sort: domain.SortStrings(sort), Partial: partial}, nil
}

// sellableStock replaces each listing's stock with what shoppers can buy: the offers' available units (the seller's
// declared quantity for seller-fulfilled offers, warehouse stock for Fulfilled by AfroTransact, 0 while the listing is
// not live). The catalog quantity alone made a listing look "29 in stock" while the storefront showed it unavailable.
// Listings offers does not know keep the catalog quantity; offers being down leaves every row as it is.
func (s *sellerCatalogImpl) sellableStock(ctx context.Context, bearer string, rows []domain.ListingRow) {
	if s.r.d.Offers == nil || len(rows) == 0 {
		return
	}
	byProduct := map[string]int{}
	byProductID := map[string]int{}
	ffByProduct := map[string][]string{}
	ffByProductID := map[string][]string{}
	for page := 0; page < 25; page++ {
		p, err := s.r.d.Offers.List(ctx, bearer, page, 200)
		if err != nil {
			return
		}
		for _, o := range p.Items {
			if strings.EqualFold(o.Status, "WITHDRAWN") {
				continue
			}
			atin := strings.ToUpper(o.ProductATIN)
			if atin != "" {
				byProduct[atin] += max(0, o.AvailableQty)
				if o.Fulfilment != "" {
					ffByProduct[atin] = append(ffByProduct[atin], strings.ToUpper(o.Fulfilment))
				}
			}
			if o.ProductID != "" {
				byProductID[o.ProductID] += max(0, o.AvailableQty)
				if o.Fulfilment != "" {
					ffByProductID[o.ProductID] = append(ffByProductID[o.ProductID], strings.ToUpper(o.Fulfilment))
				}
			}
		}
		if len(p.Items) == 0 || int64((page+1)*200) >= p.Total {
			break
		}
	}
	for i := range rows {
		var ffs []string
		qty := 0
		hasQty := false
		if rows[i].ATIN != nil {
			atin := strings.ToUpper(*rows[i].ATIN)
			if q, ok := byProduct[atin]; ok {
				qty = q
				hasQty = true
			}
			if f, ok := ffByProduct[atin]; ok {
				ffs = f
			}
		}
		if len(ffs) == 0 && rows[i].ProductID != "" {
			if q, ok := byProductID[rows[i].ProductID]; ok {
				qty = q
				hasQty = true
			}
			if f, ok := ffByProductID[rows[i].ProductID]; ok {
				ffs = f
			}
		}
		if len(ffs) > 0 {
			firstFF := ffs[0]
			allSame := true
			for _, ff := range ffs[1:] {
				if ff != firstFF {
					allSame = false
					break
				}
			}
			if allSame {
				switch firstFF {
				case domain.FulfilledByAfroTransact:
					rows[i].Fulfilment = domain.FulfilledByAfroTransact
				case domain.AfroTransactDelivery:
					rows[i].Fulfilment = domain.AfroTransactDelivery
				case domain.ShipsFromSeller:
					rows[i].Fulfilment = domain.ShipsFromSeller
				}
			}
		}
		if hasQty {
			if rows[i].Fulfilment == domain.FulfilledByAfroTransact {
				rows[i].Stock = qty
			} else if rows[i].Status != "LIVE" && qty == 0 {
				rows[i].Stock = 0
			} else if qty > rows[i].Stock || rows[i].Stock == 0 {
				rows[i].Stock = qty
			}
		}
	}
}

// scanListings reads up to limit of the organisation's listings.
func (s *sellerCatalogImpl) scanListings(ctx context.Context, bearer, status string, limit int) ([]domain.ListingRow, bool, error) {
	out := []domain.ListingRow{}
	for page := 0; ; page++ {
		p, err := s.r.d.Catalog.Products(ctx, bearer, port.ProductQuery{Status: status, Page: page, Size: exportPageSize})
		if err != nil {
			return nil, false, mapErr("catalog", err)
		}
		for _, it := range p.Items {
			if len(out) == limit {
				return out, true, nil
			}
			out = append(out, listingOf(it))
		}
		if len(p.Items) == 0 || int64((page+1)*exportPageSize) >= p.Total {
			return out, false, nil
		}
	}
}

func (s *sellerCatalogImpl) SetActive(ctx context.Context, c *domain.Caller, productID string, active bool) (domain.ListingRow, error) {
	defer s.cache.drop("listings")
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.ListingRow{}, err
	}
	p, err := s.r.d.Catalog.SetActive(ctx, c.BearerToken, productID, active)
	if err != nil {
		return domain.ListingRow{}, mapErr("catalog", err)
	}
	return listingOf(p), nil
}

func (s *sellerCatalogImpl) Submit(ctx context.Context, c *domain.Caller, d domain.ProductDraft) (domain.ListingRow, error) {
	defer s.cache.drop("listings")
	if errs := d.Validate(); len(errs) > 0 {
		return domain.ListingRow{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.ListingRow{}, err
	}
	d.Title = strings.TrimSpace(d.Title)
	if d.Attributes == nil {
		d.Attributes = map[string]string{}
	}
	p, err := s.r.d.Catalog.Create(ctx, c.BearerToken, d)
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

// treeOf nests catalog's flat category nodes (parentId) in their original order.
func treeOf(nodes []port.CatalogCategoryNode) []domain.CategoryNode {
	children := map[string][]port.CatalogCategoryNode{}
	ids := map[string]bool{}
	for _, n := range nodes {
		ids[n.ID] = true
	}
	var roots []port.CatalogCategoryNode
	for _, n := range nodes {
		if n.ParentID == "" || !ids[n.ParentID] {
			roots = append(roots, n)
		} else {
			children[n.ParentID] = append(children[n.ParentID], n)
		}
	}
	var build func(list []port.CatalogCategoryNode, depth int) []domain.CategoryNode
	build = func(list []port.CatalogCategoryNode, depth int) []domain.CategoryNode {
		out := []domain.CategoryNode{}
		if depth > 8 {
			return out
		}
		for _, n := range list {
			out = append(out, domain.CategoryNode{ID: n.ID, Name: n.Name, Level: strings.ToUpper(n.Level), ProductType: strPtr(n.ProductType),
				Path: n.Path, Children: build(children[n.ID], depth+1)})
		}
		return out
	}
	return build(roots, 0)
}

func (s *sellerCatalogImpl) Categories(ctx context.Context, c *domain.Caller) ([]domain.CategoryNode, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	nodes, err := s.r.d.Catalog.CategoryTree(ctx)
	if err != nil {
		return nil, mapErr("catalog", err)
	}
	return treeOf(nodes), nil
}

func (s *sellerCatalogImpl) ProductType(ctx context.Context, c *domain.Caller, code string) (domain.ProductTypeSchema, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ProductTypeSchema{}, err
	}
	t, err := s.r.d.Catalog.ProductType(ctx, code)
	if err != nil {
		return domain.ProductTypeSchema{}, mapErr("catalog", err)
	}
	rule := strings.ToLower(t.IdentifierRule)
	if rule != "gtin" && rule != "isbn" {
		rule = "none"
	}
	out := domain.ProductTypeSchema{Code: t.Code, Label: t.Label, IdentifierRule: rule, Attributes: []domain.AttributeDef{},
		Themes: []domain.VariationTheme{}, Traits: t.Traits}
	for _, a := range t.Attributes {
		opts := a.Options
		if opts == nil {
			opts = []string{}
		}
		units := a.Units
		if units == nil {
			units = []string{}
		}
		kind := strings.ToUpper(a.Kind)
		switch {
		case kind != "":
		case len(opts) > 0:
			kind = "ENUM"
		case a.Multiline:
			kind = "MULTILINE"
		default:
			kind = "TEXT"
		}
		out.Attributes = append(out.Attributes, domain.AttributeDef{Key: a.Key, Label: a.Label, Options: opts, Required: a.Required,
			Multiline: a.Multiline, Kind: kind, Units: units, Section: firstNonEmpty(strings.ToUpper(a.Section), "DETAILS"), Help: strPtr(a.Help)})
	}
	for _, th := range t.Themes {
		if th.Dimensions == nil {
			th.Dimensions = []domain.VariationDimension{}
		}
		for i := range th.Dimensions {
			if th.Dimensions[i].Options == nil {
				th.Dimensions[i].Options = []string{}
			}
		}
		out.Themes = append(out.Themes, th)
	}
	return out, nil
}

var gtinRe = regexp.MustCompile(`^\d{8,14}$`)

func (s *sellerCatalogImpl) CheckGTIN(ctx context.Context, c *domain.Caller, code string, isbn bool) (domain.GtinCheck, error) {
	if err := requireCustomer(c); err != nil {
		return domain.GtinCheck{}, err
	}
	if !gtinRe.MatchString(code) {
		return domain.GtinCheck{Digits: code, Valid: false, Message: "Barcodes have 8, 12, 13 or 14 digits."}, nil
	}
	g, err := s.r.d.Catalog.CheckGTIN(ctx, c.BearerToken, code, isbn)
	if err != nil {
		return domain.GtinCheck{}, mapErr("catalog", err)
	}
	return domain.GtinCheck{Digits: firstNonEmpty(g.Digits, code), Valid: g.Valid, Suggestion: strPtr(g.Suggestion), ExistingATIN: strPtr(g.ExistingATIN), Message: g.Message}, nil
}

func requestOf(r port.CatalogCategoryRequest) domain.CategoryRequest {
	return domain.CategoryRequest{ID: r.ID, Reference: r.Reference, Path: r.Path, Status: firstNonEmpty(strings.ToUpper(r.Status), "PENDING_REVIEW"),
		DecisionNote: strPtr(r.DecisionNote), CreatedAt: r.CreatedAt}
}

func (s *sellerCatalogImpl) CategoryRequests(ctx context.Context, c *domain.Caller) ([]domain.CategoryRequest, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	list, err := s.r.d.Catalog.CategoryRequests(ctx, c.BearerToken)
	if err != nil {
		return nil, mapErr("catalog", err)
	}
	out := make([]domain.CategoryRequest, 0, len(list))
	for _, r := range list {
		out = append(out, requestOf(r))
	}
	return out, nil
}

func (s *sellerCatalogImpl) RequestCategory(ctx context.Context, c *domain.Caller, in domain.CategoryRequestInput) (domain.CategoryRequest, error) {
	errs := map[string]string{}
	for k, v := range map[string]string{"parentPath": in.ParentPath, "name": in.Name, "exampleProduct": in.ExampleProduct, "detailsNeeded": in.DetailsNeeded} {
		if len([]rune(strings.TrimSpace(v))) < 3 {
			errs[k] = "Required"
		}
	}
	if len(errs) > 0 {
		return domain.CategoryRequest{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit); err != nil {
		return domain.CategoryRequest{}, err
	}
	r, err := s.r.d.Catalog.RequestCategory(ctx, c.BearerToken, in)
	if err != nil {
		return domain.CategoryRequest{}, mapErr("catalog", err)
	}
	return requestOf(r), nil
}

var imageTypes = []string{"image/jpeg", "image/png", "image/webp"}

func (s *sellerCatalogImpl) Upload(ctx context.Context, c *domain.Caller, purpose, fileName, contentType string, size int64, library bool) (domain.UploadTicket, error) {
	errs := map[string]string{}
	if purpose != "product-image" && purpose != "storefront" {
		errs["purpose"] = "Unknown purpose"
	} else if library && purpose != "product-image" {
		errs["library"] = "Only product images go into the image library"
	}
	if strings.TrimSpace(fileName) == "" {
		errs["fileName"] = "Required"
	}
	if !slices.Contains(imageTypes, contentType) {
		errs["contentType"] = "Use JPG, PNG or WebP"
	}
	if size <= 0 || size > 8<<20 {
		errs["size"] = "Images must be 8 MB or smaller"
	}
	if len(errs) > 0 {
		return domain.UploadTicket{}, domain.ValidationProblem(errs)
	}
	if err := requireCustomer(c); err != nil {
		return domain.UploadTicket{}, err
	}
	var u port.MediaUpload
	var err error
	if library {
		u, err = s.r.d.Media.RequestLibraryUpload(ctx, c.BearerToken, fileName, contentType, size)
	} else {
		u, err = s.r.d.Media.RequestUpload(ctx, c.BearerToken, purpose, fileName, contentType, size)
	}
	if err != nil {
		return domain.UploadTicket{}, mapErr("media", err)
	}
	headers := u.Upload.Headers
	if headers == nil {
		headers = map[string]string{}
	}
	return domain.UploadTicket{AssetID: u.Asset.ID, Method: firstNonEmpty(u.Upload.Method, http.MethodPut), URL: u.Upload.URL, Headers: headers,
		PublicURL: strPtr(u.Upload.PublicURL), ExpiresAt: u.Upload.ExpiresAt}, nil
}

func (s *sellerCatalogImpl) CompleteUpload(ctx context.Context, c *domain.Caller, assetID string) (domain.Asset, error) {
	if err := requireCustomer(c); err != nil {
		return domain.Asset{}, err
	}
	a, err := s.r.d.Media.Complete(ctx, c.BearerToken, assetID)
	if err != nil {
		return domain.Asset{}, mapErr("media", err)
	}
	return domain.Asset{ID: a.ID, URL: a.URL, Width: a.Width, Height: a.Height, Bytes: a.Bytes, ContentType: a.ContentType,
		Status: firstNonEmpty(strings.ToUpper(a.Status), "READY")}, nil
}

func (s *sellerCatalogImpl) Inventory(ctx context.Context, c *domain.Caller, lowStock bool) (domain.InventoryView, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "inventory", domain.LevelView)
	if err != nil {
		return domain.InventoryView{}, err
	}
	list, err := s.r.d.Stock.Positions(ctx, c.BearerToken, sc.org.ID, lowStock)
	if err != nil {
		return domain.InventoryView{}, mapErr("inventory", err)
	}
	out := domain.InventoryView{Positions: []domain.StockPosition{}}
	for _, p := range list {
		out.Positions = append(out.Positions, p)
		out.Totals.Available += p.Available
		out.Totals.Reserved += p.Reserved
		out.Totals.Inbound += p.Inbound
		if p.LowStock {
			out.Totals.LowStockSKUs++
		}
	}
	return out, nil
}

func (s *sellerCatalogImpl) AdjustStock(ctx context.Context, c *domain.Caller, key, sku string, quantity int, reason string) (domain.StockAdjustment, error) {
	if s.cache != nil {
		defer s.cache.drop("listings")
	}
	errs := map[string]string{}
	if strings.TrimSpace(sku) == "" {
		errs["sku"] = "Required"
	}
	if quantity < 0 || quantity > 100000 {
		errs["quantity"] = "Between 0 and 100,000"
	}
	if !slices.Contains(domain.StockReasons, reason) {
		errs["reason"] = "Choose a reason"
	}
	if len(errs) > 0 {
		return domain.StockAdjustment{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "inventory", domain.LevelEdit); err != nil {
		return domain.StockAdjustment{}, err
	}
	a, err := s.r.d.Offers.SetStock(ctx, c.BearerToken, key, sku, quantity, reason)
	if err != nil {
		return domain.StockAdjustment{}, mapErr("offers", err)
	}
	if a.SKU == "" {
		a = domain.StockAdjustment{SKU: sku, Quantity: quantity}
	}
	return a, nil
}

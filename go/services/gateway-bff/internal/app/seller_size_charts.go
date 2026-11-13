package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerSizeChartService is the seller's size charts (fashion and any sized category): the organisation writes, edits,
// duplicates and retires its own charts and uploads many at once, and asks what a category suggests (sizes and
// measurement columns). catalog-service owns the chart rules and scopes every chart to the token's organisation; the
// BFF checks the Listings module, validates what it can cheaply (ids, sizes of bodies and files, upload types) and
// relays catalog's JSON, so field errors such as rows[2].values.bust reach the editor as they are.
type SellerSizeChartService interface {
	List(ctx context.Context, c *domain.Caller, q url.Values) (json.RawMessage, error)
	Get(ctx context.Context, c *domain.Caller, id string) (json.RawMessage, error)
	Create(ctx context.Context, c *domain.Caller, body json.RawMessage) (json.RawMessage, error)
	Update(ctx context.Context, c *domain.Caller, id string, body json.RawMessage) (json.RawMessage, error)
	// Retire is DELETE: the chart stays on the products that use it but cannot be linked to others; it answers the chart.
	Retire(ctx context.Context, c *domain.Caller, id string) (json.RawMessage, error)
	Duplicate(ctx context.Context, c *domain.Caller, id string, body json.RawMessage) (json.RawMessage, error)
	// Suggestions are the sizes and measurement columns a category (productTypeId or productType) suggests.
	Suggestions(ctx context.Context, c *domain.Caller, productTypeID, productType string) (json.RawMessage, error)
	ImportTemplate(ctx context.Context, c *domain.Caller, productType, productTypeID, format string) (domain.FileDownload, error)
	// Import saves the charts of an uploaded file (dryRun: only report); needs the bulk upload feature of the plan.
	Import(ctx context.Context, c *domain.Caller, f port.UploadFile, productType, productTypeID string, dryRun bool) (json.RawMessage, error)
}

type sellerSizeChartsImpl struct{ r *orgResolver }

var _ SellerSizeChartService = (*sellerSizeChartsImpl)(nil)

// NewSellerSizeChartService returns the SellerSizeChartService; d.SizeCharts is catalog's client (nil answers 503).
func NewSellerSizeChartService(d SellerDeps) SellerSizeChartService {
	return &sellerSizeChartsImpl{r: newResolver(d)}
}

// MaxSizeChartBodyBytes bounds a chart body (a chart has at most 60 sizes and 12 measurements).
const MaxSizeChartBodyBytes = 256 << 10

var (
	chartIDRe   = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	chartTypeRe = regexp.MustCompile(`^[a-z][a-z0-9_]{1,39}$`)
)

func (s *sellerSizeChartsImpl) client() (port.SizeChartClient, error) {
	if s.r.d.SizeCharts == nil {
		return nil, domain.UpstreamUnavailable("catalog")
	}
	return s.r.d.SizeCharts, nil
}

func chartNotFound() error {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such size chart.")
}

// chartBody checks that a request body is a JSON object of a sane size.
func chartBody(body json.RawMessage, required bool) error {
	trimmed := strings.TrimSpace(string(body))
	switch {
	case trimmed == "" && !required:
		return nil
	case trimmed == "" || trimmed[0] != '{':
		return domain.ValidationProblem(map[string]string{"body": "Send the size chart as a JSON object"})
	case len(body) > MaxSizeChartBodyBytes:
		return domain.ValidationProblem(map[string]string{"body": "The size chart is too large"})
	}
	return nil
}

// need resolves the organisation and the module level; reads need View, changes Edit.
func (s *sellerSizeChartsImpl) need(ctx context.Context, c *domain.Caller, level string) (port.SizeChartClient, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "listings", level); err != nil {
		return nil, err
	}
	return s.client()
}

func (s *sellerSizeChartsImpl) relay(ctx context.Context, c *domain.Caller, level, method, sub string, q url.Values, body json.RawMessage) (json.RawMessage, error) {
	cl, err := s.need(ctx, c, level)
	if err != nil {
		return nil, err
	}
	out, err := cl.Call(ctx, c.BearerToken, method, sub, q, rawWithoutIdentityKeys(body))
	if err != nil {
		return nil, mapErr("catalog", err)
	}
	return out, nil
}

func (s *sellerSizeChartsImpl) List(ctx context.Context, c *domain.Caller, in url.Values) (json.RawMessage, error) {
	errs := map[string]string{}
	q := url.Values{}
	if v := strings.TrimSpace(in.Get("productTypeId")); v != "" {
		if !chartIDRe.MatchString(v) {
			errs["productTypeId"] = "Choose a product type"
		}
		q.Set("productTypeId", v)
	}
	if v := strings.TrimSpace(in.Get("productType")); v != "" {
		if !chartTypeRe.MatchString(v) {
			errs["productType"] = "Unknown product type"
		}
		q.Set("productType", v)
	}
	if v := strings.TrimSpace(in.Get("q")); v != "" {
		if len([]rune(v)) > 80 {
			errs["q"] = "At most 80 characters"
		}
		q.Set("q", v)
	}
	if v := in.Get("includeRetired"); v != "" {
		if _, err := strconv.ParseBool(v); err != nil {
			errs["includeRetired"] = "true or false"
		}
		q.Set("includeRetired", v)
	}
	page, size := 0, 25
	if v := in.Get("page"); v != "" {
		if n, err := strconv.Atoi(v); err != nil || n < 0 {
			errs["page"] = "A page number from 0"
		} else {
			page = n
		}
	}
	if v := in.Get("size"); v != "" {
		if n, err := strconv.Atoi(v); err != nil || n < 1 || n > 100 {
			errs["size"] = "Use a page of 1 to 100"
		} else {
			size = n
		}
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	q.Set("page", strconv.Itoa(page))
	q.Set("size", strconv.Itoa(size))
	return s.relay(ctx, c, domain.LevelView, http.MethodGet, "", q, nil)
}

func (s *sellerSizeChartsImpl) Get(ctx context.Context, c *domain.Caller, id string) (json.RawMessage, error) {
	if !chartIDRe.MatchString(id) {
		return nil, chartNotFound()
	}
	return s.relay(ctx, c, domain.LevelView, http.MethodGet, "/"+url.PathEscape(strings.ToLower(id)), nil, nil)
}

func (s *sellerSizeChartsImpl) Create(ctx context.Context, c *domain.Caller, body json.RawMessage) (json.RawMessage, error) {
	if err := chartBody(body, true); err != nil {
		return nil, err
	}
	return s.relay(ctx, c, domain.LevelEdit, http.MethodPost, "", nil, body)
}

func (s *sellerSizeChartsImpl) Update(ctx context.Context, c *domain.Caller, id string, body json.RawMessage) (json.RawMessage, error) {
	if !chartIDRe.MatchString(id) {
		return nil, chartNotFound()
	}
	if err := chartBody(body, true); err != nil {
		return nil, err
	}
	return s.relay(ctx, c, domain.LevelEdit, http.MethodPut, "/"+url.PathEscape(strings.ToLower(id)), nil, body)
}

func (s *sellerSizeChartsImpl) Retire(ctx context.Context, c *domain.Caller, id string) (json.RawMessage, error) {
	if !chartIDRe.MatchString(id) {
		return nil, chartNotFound()
	}
	return s.relay(ctx, c, domain.LevelEdit, http.MethodDelete, "/"+url.PathEscape(strings.ToLower(id)), nil, nil)
}

func (s *sellerSizeChartsImpl) Duplicate(ctx context.Context, c *domain.Caller, id string, body json.RawMessage) (json.RawMessage, error) {
	if !chartIDRe.MatchString(id) {
		return nil, chartNotFound()
	}
	if err := chartBody(body, false); err != nil {
		return nil, err
	}
	return s.relay(ctx, c, domain.LevelEdit, http.MethodPost, "/"+url.PathEscape(strings.ToLower(id))+"/duplicate", nil, body)
}

func (s *sellerSizeChartsImpl) Suggestions(ctx context.Context, c *domain.Caller, productTypeID, productType string) (json.RawMessage, error) {
	productTypeID, productType = strings.TrimSpace(productTypeID), strings.TrimSpace(productType)
	errs := map[string]string{}
	switch {
	case productTypeID == "" && productType == "":
		errs["productTypeId"] = "Choose a product type"
	case productTypeID != "" && !chartIDRe.MatchString(productTypeID):
		errs["productTypeId"] = "Choose a product type"
	case productTypeID == "" && !chartTypeRe.MatchString(productType):
		errs["productType"] = "Unknown product type"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	q := url.Values{}
	if productTypeID != "" {
		q.Set("productTypeId", productTypeID)
	} else {
		q.Set("productType", productType)
	}
	return s.relay(ctx, c, domain.LevelView, http.MethodGet, "/suggestions", q, nil)
}

// checkTypeSelector validates the optional product type of a template or upload.
func checkTypeSelector(productType, productTypeID string) map[string]string {
	errs := map[string]string{}
	if productTypeID != "" && !chartIDRe.MatchString(productTypeID) {
		errs["productTypeId"] = "Choose a product type"
	}
	if productType != "" && !chartTypeRe.MatchString(productType) {
		errs["productType"] = "Unknown product type"
	}
	return errs
}

func (s *sellerSizeChartsImpl) ImportTemplate(ctx context.Context, c *domain.Caller, productType, productTypeID, format string) (domain.FileDownload, error) {
	f, err := checkFormat(format)
	if err != nil {
		return domain.FileDownload{}, err
	}
	productType, productTypeID = strings.TrimSpace(productType), strings.TrimSpace(productTypeID)
	if errs := checkTypeSelector(productType, productTypeID); len(errs) > 0 {
		return domain.FileDownload{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapBulkUpload); err != nil {
		return domain.FileDownload{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.FileDownload{}, err
	}
	file, err := cl.ImportTemplate(ctx, c.BearerToken, productType, productTypeID, f)
	if err != nil {
		return domain.FileDownload{}, mapErr("catalog", err)
	}
	return file, nil
}

func (s *sellerSizeChartsImpl) Import(ctx context.Context, c *domain.Caller, f port.UploadFile, productType, productTypeID string, dryRun bool) (json.RawMessage, error) {
	productType, productTypeID = strings.TrimSpace(productType), strings.TrimSpace(productTypeID)
	errs := checkTypeSelector(productType, productTypeID)
	ext := strings.ToLower(path.Ext(f.Name))
	ct, ok := uploadTypes[ext]
	switch {
	case len(f.Data) == 0:
		errs["file"] = "Choose an Excel (.xlsx) or CSV (.csv) file"
	case !ok:
		errs["file"] = "Upload an Excel (.xlsx) or CSV (.csv) file; save .xls workbooks as .xlsx"
	case int64(len(f.Data)) > domain.BulkUploadLimits.MaxFileBytes:
		errs["file"] = "The file is larger than 10 MB; split it into several files"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit, domain.CapBulkUpload); err != nil {
		return nil, err
	}
	cl, err := s.client()
	if err != nil {
		return nil, err
	}
	f.ContentType = ct
	out, err := cl.Import(ctx, c.BearerToken, f, productType, productTypeID, dryRun)
	if err != nil {
		return nil, mapErr("catalog", err)
	}
	return out, nil
}

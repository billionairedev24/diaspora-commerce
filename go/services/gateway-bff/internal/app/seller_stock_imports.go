package app

import (
	"context"
	"fmt"
	"net/url"
	"path"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerStockImportService is the stock CSV import (S395): a seller uploads counts for its own stock, reviews every row,
// starts a background import and downloads the row report. inventory-service validates and applies (ledger entries
// with the reason BULK_COUNT and the import id); the BFF relays with the member's token.
//
// Access: the "inventory" module (view to read, edit to upload and start) and a plan with bulk upload. The organisation
// is the one the BFF resolved for the member, never a value from the request, and inventory reads it from the token
// again: a file only ever sees and changes the signed-in organisation's own stock. A SKU of another organisation
// looks like an unknown one, and AfroTransact Retail's own stock is refused (403 inventory.retail-stock).
type SellerStockImportService interface {
	Template(ctx context.Context, c *domain.Caller, mode string) (domain.FileDownload, error)
	Upload(ctx context.Context, c *domain.Caller, f port.UploadFile, mode string) (domain.StockImport, error)
	List(ctx context.Context, c *domain.Caller, page, size int) (domain.StockImportPage, error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.StockImport, error)
	Rows(ctx context.Context, c *domain.Caller, id, status, outcome string, page, size int) (domain.StockImportRowPage, error)
	Report(ctx context.Context, c *domain.Caller, id string) (domain.FileDownload, error)
	Start(ctx context.Context, c *domain.Caller, id string, validOnly bool) (domain.StockImport, error)
}

type sellerStockImportImpl struct {
	r        *orgResolver
	basePath string
}

var _ SellerStockImportService = (*sellerStockImportImpl)(nil)

// NewSellerStockImportService returns the SellerStockImportService; basePath prefixes app-relative download links.
func NewSellerStockImportService(d SellerDeps, basePath string) SellerStockImportService {
	return &sellerStockImportImpl{r: newResolver(d), basePath: strings.TrimSuffix(basePath, "/")}
}

func (s *sellerStockImportImpl) client() (port.SellerStockImportClient, error) {
	if s.r.d.StockImports == nil {
		return nil, domain.UpstreamUnavailable("inventory")
	}
	return s.r.d.StockImports, nil
}

func (s *sellerStockImportImpl) access(ctx context.Context, c *domain.Caller, level string) (sellerCtx, error) {
	return s.r.requireCap(ctx, c, domain.OrgKindSeller, "inventory", level, domain.CapBulkUpload)
}

func (s *sellerStockImportImpl) shape(i domain.StockImport) domain.StockImport {
	if i.Header == nil {
		i.Header = []string{}
	}
	i.Status = strings.ToUpper(i.Status)
	i.Mode = firstNonEmpty(strings.ToUpper(i.Mode), domain.StockImportAbsolute)
	i.ReportURL = s.basePath + "/v1/seller/stock/imports/" + url.PathEscape(i.ID) + "/report"
	return i
}

func checkStockMode(mode string) (string, error) {
	m := strings.ToUpper(firstNonEmpty(strings.TrimSpace(mode), domain.StockImportAbsolute))
	if !slices.Contains(domain.StockImportModes, m) {
		return "", domain.ValidationProblem(map[string]string{"mode": "ABSOLUTE or DELTA"})
	}
	return m, nil
}

func (s *sellerStockImportImpl) Template(ctx context.Context, c *domain.Caller, mode string) (domain.FileDownload, error) {
	m, err := checkStockMode(mode)
	if err != nil {
		return domain.FileDownload{}, err
	}
	if _, err := s.access(ctx, c, domain.LevelView); err != nil {
		return domain.FileDownload{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.FileDownload{}, err
	}
	f, err := cl.Template(ctx, c.BearerToken, m)
	if err != nil {
		return domain.FileDownload{}, mapErr("inventory", err)
	}
	return f, nil
}

func (s *sellerStockImportImpl) Upload(ctx context.Context, c *domain.Caller, f port.UploadFile, mode string) (domain.StockImport, error) {
	errs := map[string]string{}
	m, merr := checkStockMode(mode)
	if merr != nil {
		errs["mode"] = "ABSOLUTE or DELTA"
	}
	switch {
	case len(f.Data) == 0:
		errs["file"] = "Choose a CSV (.csv) file"
	case strings.ToLower(path.Ext(f.Name)) != ".csv":
		errs["file"] = "Upload a CSV (.csv) file; save a spreadsheet as CSV first"
	case int64(len(f.Data)) > domain.BulkUploadLimits.MaxFeedFileBytes:
		errs["file"] = fmt.Sprintf("The file is larger than %d MB; split it into several files", domain.BulkUploadLimits.MaxFeedFileBytes>>20)
	}
	if len(errs) > 0 {
		return domain.StockImport{}, domain.ValidationProblem(errs)
	}
	sc, err := s.access(ctx, c, domain.LevelEdit)
	if err != nil {
		return domain.StockImport{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.StockImport{}, err
	}
	f.ContentType = "text/csv"
	f.Name = path.Base(strings.ReplaceAll(f.Name, "\\", "/"))
	i, err := cl.Upload(ctx, c.BearerToken, sc.org.ID, f, m)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == 422 && len(p.Fields) == 0 {
			return domain.StockImport{}, domain.ValidationProblem(map[string]string{"file": firstNonEmpty(p.Detail, "The file can't be imported")})
		}
		return domain.StockImport{}, mapErr("inventory", err)
	}
	return s.shape(i), nil
}

func (s *sellerStockImportImpl) List(ctx context.Context, c *domain.Caller, page, size int) (domain.StockImportPage, error) {
	if size == 0 {
		size = 25
	}
	if page < 0 || size < 1 || size > 100 {
		return domain.StockImportPage{}, domain.ValidationProblem(map[string]string{"size": "Between 1 and 100", "page": "Must be 0 or more"})
	}
	sc, err := s.access(ctx, c, domain.LevelView)
	if err != nil {
		return domain.StockImportPage{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.StockImportPage{}, err
	}
	p, err := cl.List(ctx, c.BearerToken, sc.org.ID, page, size)
	if err != nil {
		return domain.StockImportPage{}, mapErr("inventory", err)
	}
	if p.Items == nil {
		p.Items = []domain.StockImport{}
	}
	for n := range p.Items {
		p.Items[n] = s.shape(p.Items[n])
	}
	return p, nil
}

func (s *sellerStockImportImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.StockImport, error) {
	if _, err := s.access(ctx, c, domain.LevelView); err != nil {
		return domain.StockImport{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.StockImport{}, err
	}
	i, err := cl.Get(ctx, c.BearerToken, id)
	if err != nil {
		return domain.StockImport{}, mapErr("inventory", err)
	}
	return s.shape(i), nil
}

func (s *sellerStockImportImpl) Rows(ctx context.Context, c *domain.Caller, id, status, outcome string, page, size int) (domain.StockImportRowPage, error) {
	errs := map[string]string{}
	if status != "" && !slices.Contains(domain.StockImportStatuses, status) {
		errs["status"] = "VALID, WARNING or ERROR"
	}
	if outcome != "" && !slices.Contains(domain.StockImportOutcomes, outcome) {
		errs["outcome"] = "Unknown outcome"
	}
	if size == 0 {
		size = 50
	}
	if page < 0 {
		errs["page"] = "Must be 0 or more"
	}
	if size < 1 || size > 200 {
		errs["size"] = "Between 1 and 200"
	}
	if len(errs) > 0 {
		return domain.StockImportRowPage{}, domain.ValidationProblem(errs)
	}
	if _, err := s.access(ctx, c, domain.LevelView); err != nil {
		return domain.StockImportRowPage{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.StockImportRowPage{}, err
	}
	p, err := cl.Rows(ctx, c.BearerToken, id, status, outcome, page, size)
	if err != nil {
		return domain.StockImportRowPage{}, mapErr("inventory", err)
	}
	if p.Items == nil {
		p.Items = []domain.StockImportRow{}
	}
	for n := range p.Items {
		r := &p.Items[n]
		r.Outcome = firstNonEmpty(strings.ToUpper(r.Outcome), "PENDING")
		r.Status = strings.ToUpper(r.Status)
		if r.Messages == nil {
			r.Messages = []domain.StockImportMessage{}
		}
		for m := range r.Messages {
			r.Messages[m].Severity = strings.ToUpper(r.Messages[m].Severity)
		}
		if r.Cells == nil {
			r.Cells = []string{}
		}
	}
	return p, nil
}

func (s *sellerStockImportImpl) Report(ctx context.Context, c *domain.Caller, id string) (domain.FileDownload, error) {
	if _, err := s.access(ctx, c, domain.LevelView); err != nil {
		return domain.FileDownload{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.FileDownload{}, err
	}
	f, err := cl.Report(ctx, c.BearerToken, id)
	if err != nil {
		return domain.FileDownload{}, mapErr("inventory", err)
	}
	return f, nil
}

func (s *sellerStockImportImpl) Start(ctx context.Context, c *domain.Caller, id string, validOnly bool) (domain.StockImport, error) {
	if _, err := s.access(ctx, c, domain.LevelEdit); err != nil {
		return domain.StockImport{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.StockImport{}, err
	}
	i, err := cl.Start(ctx, c.BearerToken, id, validOnly)
	if err != nil {
		return domain.StockImport{}, mapErr("inventory", err)
	}
	return s.shape(i), nil
}

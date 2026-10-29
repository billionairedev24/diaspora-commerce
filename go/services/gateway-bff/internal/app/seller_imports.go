package app

import (
	"context"
	"net/http"
	"net/url"
	"path"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerImportService is the bulk product upload (S308): templates, upload and validation, preview rows, the error
// report, starting the background import and the upload history. catalog-service owns parsing, validation and the
// import job (Apache POI); the BFF relays with the member's token and shapes the answers.
type SellerImportService interface {
	Template(ctx context.Context, c *domain.Caller, productType, categoryID, format string) (domain.FileDownload, error)
	Upload(ctx context.Context, c *domain.Caller, f port.UploadFile, mode, productType string) (domain.ProductImport, error)
	List(ctx context.Context, c *domain.Caller, page, size int) (domain.ProductImportPage, error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.ProductImport, error)
	Rows(ctx context.Context, c *domain.Caller, id, status, outcome string, page, size int) (domain.ProductImportRowPage, error)
	Report(ctx context.Context, c *domain.Caller, id, format string) (domain.FileDownload, error)
	Start(ctx context.Context, c *domain.Caller, id string, validOnly bool) (domain.ProductImport, error)
	// ImageLibrary is the organisation's image library (media) with the SKUs that use each image (catalog), S310.
	ImageLibrary(ctx context.Context, c *domain.Caller, q, status, matched string, page, size int) (domain.ImageLibraryPage, error)
	RemoveLibraryImage(ctx context.Context, c *domain.Caller, assetID string) error
	RenameLibraryImage(ctx context.Context, c *domain.Caller, assetID, fileName string) (domain.ImageLibraryItem, error)
	AttachLibraryImages(ctx context.Context, c *domain.Caller, skus []string) (domain.AttachLibraryImagesResult, error)
	Brands(ctx context.Context, c *domain.Caller, q string) (domain.BrandList, error)
}

type sellerImportImpl struct {
	r        *orgResolver
	basePath string
}

var _ SellerImportService = (*sellerImportImpl)(nil)

// NewSellerImportService returns the SellerImportService; basePath prefixes app-relative download links ("/api/bff").
func NewSellerImportService(d SellerDeps, basePath string) SellerImportService {
	return &sellerImportImpl{r: newResolver(d), basePath: strings.TrimSuffix(basePath, "/")}
}

func (s *sellerImportImpl) client() (port.SellerListingClient, error) {
	if s.r.d.Listing == nil {
		return nil, domain.UpstreamUnavailable("catalog")
	}
	return s.r.d.Listing, nil
}

func (s *sellerImportImpl) importOf(i port.CatalogImport) domain.ProductImport {
	header := i.Header
	if header == nil {
		header = []string{}
	}
	warnings := i.FileWarnings
	if warnings == nil {
		warnings = []string{}
	}
	return domain.ProductImport{ID: i.ID, Reference: i.Reference, FileName: i.FileName, Format: strings.ToUpper(i.Format),
		Mode: firstNonEmpty(strings.ToUpper(i.Mode), "CREATE"), ProductType: strPtr(i.ProductType), Status: strings.ToUpper(i.Status),
		ValidOnly: i.ValidOnly, TotalRows: i.TotalRows, ValidRows: i.ValidRows, WarningRows: i.WarningRows, ErrorRows: i.ErrorRows,
		ProductCount: i.ProductCount, ProcessedRows: i.ProcessedRows, CreatedProducts: i.CreatedProducts, UpdatedProducts: i.UpdatedProducts,
		FailedRows: i.FailedRows, Header: header, FileWarnings: warnings, UploadedByName: strPtr(i.UploadedByName), CreatedAt: i.CreatedAt,
		StartedAt: i.StartedAt, FinishedAt: i.FinishedAt,
		ReportURL: s.basePath + "/v1/seller/imports/" + url.PathEscape(i.ID) + "/report?format=xlsx"}
}

func checkFormat(format string) (string, error) {
	if format == "" {
		return "xlsx", nil
	}
	f := strings.ToLower(format)
	if !slices.Contains(domain.SpreadsheetFormats, f) {
		return "", domain.ValidationProblem(map[string]string{"format": "xlsx or csv"})
	}
	return f, nil
}

func (s *sellerImportImpl) Template(ctx context.Context, c *domain.Caller, productType, categoryID, format string) (domain.FileDownload, error) {
	f, err := checkFormat(format)
	if err != nil {
		return domain.FileDownload{}, err
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapBulkUpload); err != nil {
		return domain.FileDownload{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.FileDownload{}, err
	}
	file, err := cl.ImportTemplate(ctx, c.BearerToken, strings.TrimSpace(productType), strings.TrimSpace(categoryID), f)
	if err != nil {
		return domain.FileDownload{}, mapErr("catalog", err)
	}
	return file, nil
}

// uploadTypes are the content types browsers send for spreadsheets (the file name decides the format).
var uploadTypes = map[string]string{
	".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	".csv":  "text/csv",
}

func (s *sellerImportImpl) Upload(ctx context.Context, c *domain.Caller, f port.UploadFile, mode, productType string) (domain.ProductImport, error) {
	errs := map[string]string{}
	mode = strings.ToUpper(firstNonEmpty(mode, "CREATE"))
	if !slices.Contains(domain.ImportModes, mode) {
		errs["mode"] = "CREATE or UPDATE"
	}
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
	if len(productType) > 40 {
		errs["productType"] = "Unknown product type"
	}
	if len(errs) > 0 {
		return domain.ProductImport{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit, domain.CapBulkUpload)
	if err != nil {
		return domain.ProductImport{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.ProductImport{}, err
	}
	f.ContentType = ct
	f.Name = path.Base(strings.ReplaceAll(f.Name, "\\", "/"))
	name := strings.TrimSpace(sc.member.FirstName + " " + sc.member.LastName)
	i, err := cl.Upload(ctx, c.BearerToken, f, mode, strings.TrimSpace(productType), name)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnprocessableEntity && len(p.Fields) == 0 {
			// catalog.upload-rejected without field errors: the file as a whole.
			return domain.ProductImport{}, domain.ValidationProblem(map[string]string{"file": firstNonEmpty(p.Detail, "The file can't be imported")})
		}
		return domain.ProductImport{}, mapErr("catalog", err)
	}
	return s.importOf(i), nil
}

func (s *sellerImportImpl) List(ctx context.Context, c *domain.Caller, page, size int) (domain.ProductImportPage, error) {
	if size == 0 {
		size = 25
	}
	if page < 0 || size < 1 || size > 100 {
		return domain.ProductImportPage{}, domain.ValidationProblem(map[string]string{"size": "Between 1 and 100", "page": "Must be 0 or more"})
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapBulkUpload); err != nil {
		return domain.ProductImportPage{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.ProductImportPage{}, err
	}
	p, err := cl.Imports(ctx, c.BearerToken, page, size)
	if err != nil {
		return domain.ProductImportPage{}, mapErr("catalog", err)
	}
	out := domain.ProductImportPage{Items: make([]domain.ProductImport, 0, len(p.Items)), Total: p.Total, Page: p.Page, Size: p.Size}
	for _, i := range p.Items {
		out.Items = append(out.Items, s.importOf(i))
	}
	return out, nil
}

func (s *sellerImportImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.ProductImport, error) {
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapBulkUpload); err != nil {
		return domain.ProductImport{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.ProductImport{}, err
	}
	i, err := cl.Import(ctx, c.BearerToken, id)
	if err != nil {
		return domain.ProductImport{}, mapErr("catalog", err)
	}
	return s.importOf(i), nil
}

func (s *sellerImportImpl) Rows(ctx context.Context, c *domain.Caller, id, status, outcome string, page, size int) (domain.ProductImportRowPage, error) {
	errs := map[string]string{}
	if status != "" && !slices.Contains(domain.ImportRowStatuses, status) {
		errs["status"] = "VALID, WARNING or ERROR"
	}
	if outcome != "" && !slices.Contains(domain.ImportRowOutcomes, outcome) {
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
		return domain.ProductImportRowPage{}, domain.ValidationProblem(errs)
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapBulkUpload); err != nil {
		return domain.ProductImportRowPage{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.ProductImportRowPage{}, err
	}
	p, err := cl.ImportRows(ctx, c.BearerToken, id, status, outcome, page, size)
	if err != nil {
		return domain.ProductImportRowPage{}, mapErr("catalog", err)
	}
	out := domain.ProductImportRowPage{Items: make([]domain.ProductImportRow, 0, len(p.Items)), Total: p.Total, Page: p.Page, Size: p.Size}
	for _, r := range p.Items {
		row := domain.ProductImportRow{RowNumber: r.RowNumber, GroupKey: r.GroupKey, SKU: r.SKU, Status: strings.ToUpper(r.Status),
			Outcome: firstNonEmpty(strings.ToUpper(r.Outcome), "PENDING"), OutcomeNote: strPtr(r.OutcomeNote), ProductID: strPtr(r.ProductID),
			Messages: make([]domain.ImportRowMessage, 0, len(r.Messages)), Cells: r.Cells}
		if row.Cells == nil {
			row.Cells = []string{}
		}
		for _, m := range r.Messages {
			row.Messages = append(row.Messages, domain.ImportRowMessage{Column: m.Column, ColumnIndex: m.ColumnIndex,
				Severity: strings.ToUpper(m.Severity), Message: m.Message})
		}
		row.Images = make([]domain.ImportRowImage, 0, len(r.Images))
		for _, i := range r.Images {
			row.Images = append(row.Images, domain.ImportRowImage{Slot: i.Slot, Source: i.Source, Ref: i.Ref, State: i.State,
				URL: i.URL, Message: i.Message})
		}
		out.Items = append(out.Items, row)
	}
	return out, nil
}

func (s *sellerImportImpl) Report(ctx context.Context, c *domain.Caller, id, format string) (domain.FileDownload, error) {
	f, err := checkFormat(format)
	if err != nil {
		return domain.FileDownload{}, err
	}
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelView, domain.CapBulkUpload); err != nil {
		return domain.FileDownload{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.FileDownload{}, err
	}
	file, err := cl.ImportReport(ctx, c.BearerToken, id, f)
	if err != nil {
		return domain.FileDownload{}, mapErr("catalog", err)
	}
	return file, nil
}

func (s *sellerImportImpl) Start(ctx context.Context, c *domain.Caller, id string, validOnly bool) (domain.ProductImport, error) {
	if _, err := s.r.requireCap(ctx, c, domain.OrgKindSeller, "listings", domain.LevelEdit, domain.CapBulkUpload); err != nil {
		return domain.ProductImport{}, err
	}
	cl, err := s.client()
	if err != nil {
		return domain.ProductImport{}, err
	}
	i, err := cl.StartImport(ctx, c.BearerToken, id, validOnly)
	if err != nil {
		return domain.ProductImport{}, mapErr("catalog", err)
	}
	return s.importOf(i), nil
}

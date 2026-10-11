package app

import (
	"context"
	"slices"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffExportsService builds exports on the server: CSV, XLSX (Data sheet plus a Summary sheet of native charts) or
// PDF (summary page plus the table). A staff resource is streamed page by page from its upstream with the table's
// search, sort and visible columns (or only the selected rows); a client-side table sends its rows (dataset). Files
// are stored (BFF file store or media) behind signed links that expire after 24 hours.
type StaffExportsService interface {
	// Create starts an export; ready is true when the file was built within the request (201) and false when the job
	// was queued (202).
	Create(ctx context.Context, c *domain.Caller, req ExportRequest, idem string) (job domain.ExportJob, ready bool, err error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.ExportJob, error)
	List(ctx context.Context, c *domain.Caller) ([]domain.ExportJob, error)
	// Download returns the signed URL of a ready export.
	Download(ctx context.Context, c *domain.Caller, id string) (string, error)
}

type staffExportsImpl struct {
	r   *staffResolver
	res map[string]*staffResource
	e   *exportEngine
}

var _ StaffExportsService = (*staffExportsImpl)(nil)

// NewStaffExportsService returns the exports use case.
func NewStaffExportsService(d StaffDeps) StaffExportsService {
	r := newStaffResolver(d)
	return &staffExportsImpl{r: r, res: staffResources(), e: &exportEngine{jobs: r.d.Jobs, blobs: r.d.Blobs, render: r.d.Renderer, clock: r.d.Clock,
		log: r.d.Log, background: r.d.Background, basePath: r.d.ExportBasePath, portal: "staff"}}
}

func (s *staffExportsImpl) Create(ctx context.Context, c *domain.Caller, req ExportRequest, _ string) (domain.ExportJob, bool, error) {
	errs := map[string]string{}
	validateExport(req, errs)
	if req.Dataset != nil {
		if len(errs) > 0 {
			return domain.ExportJob{}, false, domain.ValidationProblem(errs)
		}
		sc, err := s.r.resolve(ctx, c)
		if err != nil {
			return domain.ExportJob{}, false, err
		}
		plan, err := datasetPlan(req.Dataset, "")
		if err != nil {
			return domain.ExportJob{}, false, err
		}
		return s.e.start(ctx, sc.me.ID, "", sc.bearer, req.Format, plan)
	}
	if req.Table == "preorder-orders" {
		return s.preorderOrders(ctx, c, req, errs)
	}
	r, ok := s.res[req.Resource]
	if !ok {
		errs["resource"] = "Choose a table to export"
	} else if !r.exportable {
		errs["resource"] = "This table can't be exported"
	}
	if len(errs) > 0 {
		return domain.ExportJob{}, false, domain.ValidationProblem(errs)
	}
	sort, p := domain.ParseSort(req.Sort, r.hasColumn)
	if p != nil {
		return domain.ExportJob{}, false, p
	}
	cols, err := pickColumns(r.columns, req.Columns)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	sc, err := s.r.require(ctx, c, r.module, domain.LevelView)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	if err := s.e.ready(); err != nil {
		return domain.ExportJob{}, false, err
	}
	plan, err := s.plan(ctx, sc, r, cols, req, sort)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	return s.e.start(ctx, sc.me.ID, "", sc.bearer, req.Format, plan)
}

// plan streams every upstream page when the export takes the whole table in upstream order; a search, a sort or a
// selection reads the table into the BFF first (up to domain.ExportScanMaxRows rows), then filters and sorts it.
func (s *staffExportsImpl) plan(ctx context.Context, sc staffCtx, r *staffResource, cols []domain.Column, req ExportRequest, sort []domain.SortKey) (exportPlan, error) {
	ecols := exportColumns(cols)
	emitRow := func(row domain.Row, emit func([]any) error) error {
		cells := make([]any, len(cols))
		for i, c := range cols {
			cells[i] = exportCell(c.Kind, row.Cells[c.Key])
		}
		return emit(cells)
	}
	if req.Q == "" && len(sort) == 0 && len(req.IDs) == 0 {
		q := staffQuery{Status: req.Status, Size: exportPageSize}
		first, err := r.list(ctx, sc, q)
		if err != nil {
			return exportPlan{}, err
		}
		small := (first.total != nil && *first.total <= domain.ExportSyncRows) || (first.total == nil && first.next == nil)
		return exportPlan{key: r.key, title: r.title, small: small,
			meta: domain.ExportMeta{Title: r.title, Columns: ecols, Scope: exportScope(cols, "", nil, 0, false)},
			rows: func(ctx context.Context, emit func([]any) error) error {
				return walkResource(ctx, sc, r, q, first, domain.ExportMaxRows, func(o jobj) error { return emitRow(rowOf(r, o), emit) })
			}}, nil
	}
	rows, partial, err := scanResource(ctx, sc, r, req.Status, domain.ExportScanMaxRows)
	if err != nil {
		return exportPlan{}, err
	}
	rows = domain.FilterRows(rows, r.columns, domain.SearchTerms(req.Q), req.IDs)
	domain.SortRows(rows, r.columns, sort)
	return exportPlan{key: r.key, title: r.title, small: len(rows) <= domain.ExportSyncRows,
		meta: domain.ExportMeta{Title: r.title, Columns: ecols, Scope: exportScope(cols, req.Q, sort, len(req.IDs), partial)},
		rows: func(_ context.Context, emit func([]any) error) error {
			for _, row := range rows {
				if err := emitRow(row, emit); err != nil {
					return err
				}
			}
			return nil
		}}, nil
}

// preorderOrders exports a pre-order window's orders with the table's search, sort, columns and selection (needs the
// pre-orders export permission).
func (s *staffExportsImpl) preorderOrders(ctx context.Context, c *domain.Caller, req ExportRequest, errs map[string]string) (domain.ExportJob, bool, error) {
	if p := domain.ValidPreorderID(req.Parent); p != nil {
		errs["parent"] = "Choose a pre-order window"
	}
	if len(errs) > 0 {
		return domain.ExportJob{}, false, domain.ValidationProblem(errs)
	}
	sort, p := domain.ParseSort(req.Sort, preorderOrderColumn)
	if p != nil {
		return domain.ExportJob{}, false, p
	}
	cols, err := pickColumns(PreorderOrderColumns, req.Columns)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	sc, _, err := (&staffPreordersImpl{r: s.r}).need(ctx, c, poExport)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	if err := s.e.ready(); err != nil {
		return domain.ExportJob{}, false, err
	}
	_, rows, partial, err := scanPreorderOrders(ctx, sc, req.Parent, req.Status, "", domain.ExportScanMaxRows)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	rows = domain.FilterRows(rows, PreorderOrderColumns, domain.SearchTerms(req.Q), req.IDs)
	domain.SortRows(rows, PreorderOrderColumns, sort)
	plan := exportPlan{key: "preorder-orders", title: "Pre-order orders", small: len(rows) <= domain.ExportSyncRows,
		meta: domain.ExportMeta{Title: "Pre-order orders", Columns: exportColumns(cols), Scope: exportScope(cols, req.Q, sort, len(req.IDs), partial)},
		rows: func(_ context.Context, emit func([]any) error) error {
			for _, r := range rows {
				if err := emit(tableCells(r, cols)); err != nil {
					return err
				}
			}
			return nil
		}}
	return s.e.start(ctx, sc.me.ID, "", sc.bearer, req.Format, plan)
}

func (r *staffResource) hasColumn(key string) bool {
	return slices.ContainsFunc(r.columns, func(c domain.Column) bool { return c.Key == key })
}

func (s *staffExportsImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.ExportJob, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return domain.ExportJob{}, err
	}
	job, err := s.e.owned(ctx, sc.me.ID, "", id)
	if err != nil {
		return domain.ExportJob{}, err
	}
	return s.e.view(job), nil
}

func (s *staffExportsImpl) List(ctx context.Context, c *domain.Caller) ([]domain.ExportJob, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	return s.e.list(ctx, sc.me.ID, "")
}

func (s *staffExportsImpl) Download(ctx context.Context, c *domain.Caller, id string) (string, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return "", err
	}
	job, err := s.e.owned(ctx, sc.me.ID, "", id)
	if err != nil {
		return "", err
	}
	return s.e.download(ctx, sc.bearer, job)
}

package app

import (
	"context"
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ExportDeps are the export stores shared by the staff console and Seller Center.
type ExportDeps struct {
	Jobs     port.ExportJobStore
	Blobs    port.ExportBlobStore
	Renderer port.ExportRenderer
	// BasePath prefixes download links (the app's same-origin BFF proxy, e.g. /api/bff).
	BasePath string
	// Background runs big exports (defaults to a goroutine); tests run them inline.
	Background func(func())
}

// SellerExportsService exports Seller Center tables (CSV, XLSX with native charts, PDF), always scoped to the active
// organisation: orders and listings are read by the BFF with the member's token (search, sort, visible columns,
// selected rows); client-side tables (inventory, team, payouts, invoices, fees, earnings, services) send their rows.
type SellerExportsService interface {
	Create(ctx context.Context, c *domain.Caller, req ExportRequest) (job domain.ExportJob, ready bool, err error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.ExportJob, error)
	List(ctx context.Context, c *domain.Caller) ([]domain.ExportJob, error)
	Download(ctx context.Context, c *domain.Caller, id string) (string, error)
}

type sellerExportsImpl struct {
	r        *orgResolver
	orders   *sellerOrdersImpl
	listings *sellerCatalogImpl
	e        *exportEngine
}

var _ SellerExportsService = (*sellerExportsImpl)(nil)

// NewSellerExportsService returns the Seller Center exports use case.
func NewSellerExportsService(d SellerDeps, x ExportDeps) SellerExportsService {
	r := newResolver(d)
	log := d.Log
	if log == nil {
		log = slog.Default()
	}
	bg := x.Background
	if bg == nil {
		bg = func(f func()) { go f() }
	}
	return &sellerExportsImpl{r: r, orders: &sellerOrdersImpl{r: r}, listings: &sellerCatalogImpl{r: r},
		e: &exportEngine{jobs: x.Jobs, blobs: x.Blobs, render: x.Renderer, clock: d.clock(), log: log, background: bg, basePath: x.BasePath, portal: "seller"}}
}

// sellerTable is a server-read Seller Center table.
type sellerTable struct {
	module, title string
	columns       []domain.Column
}

var sellerTables = map[string]sellerTable{
	"orders":   {module: "orders", title: "Orders", columns: SellerOrderColumns},
	"listings": {module: "listings", title: "Listings", columns: SellerListingColumns},
}

func (s *sellerExportsImpl) Create(ctx context.Context, c *domain.Caller, req ExportRequest) (domain.ExportJob, bool, error) {
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
		return s.e.start(ctx, sc.member.ID, sc.org.ID, c.BearerToken, req.Format, plan)
	}
	t, ok := sellerTables[req.Table]
	if !ok {
		errs["table"] = "Choose a table to export"
	}
	if len(errs) > 0 {
		return domain.ExportJob{}, false, domain.ValidationProblem(errs)
	}
	q := SellerTableQuery{Q: req.Q, Status: req.Status, Sort: req.Sort}
	sort, err := q.sort(t.columns)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	cols, err := pickColumns(t.columns, req.Columns)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, t.module, domain.LevelView)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	if err := s.e.ready(); err != nil {
		return domain.ExportJob{}, false, err
	}
	var rows []domain.Row
	var partial bool
	switch req.Table {
	case "orders":
		items, more, err := s.orders.scan(ctx, c.BearerToken, req.Status, domain.ExportScanMaxRows)
		if err != nil {
			return domain.ExportJob{}, false, err
		}
		for _, it := range pickRows(items, t.columns, sellerOrderRowCells, req.Q, req.IDs, sort) {
			rows = append(rows, sellerOrderRowCells(it))
		}
		partial = more
	default:
		items, more, err := s.listings.scanListings(ctx, c.BearerToken, req.Status, domain.ExportScanMaxRows)
		if err != nil {
			return domain.ExportJob{}, false, err
		}
		for _, it := range pickRows(items, t.columns, sellerListingRowCells, req.Q, req.IDs, sort) {
			rows = append(rows, sellerListingRowCells(it))
		}
		partial = more
	}
	plan := exportPlan{key: req.Table, title: t.title, small: len(rows) <= domain.ExportSyncRows,
		meta: domain.ExportMeta{Title: t.title + " · " + sc.org.Name, Columns: exportColumns(cols), Scope: exportScope(cols, req.Q, sort, len(req.IDs), partial)},
		rows: func(_ context.Context, emit func([]any) error) error {
			for _, r := range rows {
				if err := emit(tableCells(r, cols)); err != nil {
					return err
				}
			}
			return nil
		}}
	return s.e.start(ctx, sc.member.ID, sc.org.ID, c.BearerToken, req.Format, plan)
}

func (s *sellerExportsImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.ExportJob, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return domain.ExportJob{}, err
	}
	job, err := s.e.owned(ctx, sc.member.ID, sc.org.ID, id)
	if err != nil {
		return domain.ExportJob{}, err
	}
	return s.e.view(job), nil
}

func (s *sellerExportsImpl) List(ctx context.Context, c *domain.Caller) ([]domain.ExportJob, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	return s.e.list(ctx, sc.member.ID, sc.org.ID)
}

func (s *sellerExportsImpl) Download(ctx context.Context, c *domain.Caller, id string) (string, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return "", err
	}
	job, err := s.e.owned(ctx, sc.member.ID, sc.org.ID, id)
	if err != nil {
		return "", err
	}
	return s.e.download(ctx, c.BearerToken, job)
}

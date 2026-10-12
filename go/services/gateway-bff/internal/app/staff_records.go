package app

import (
	"context"
	"net/http"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type staffUp = port.StaffUpstream

func upIdentity(sc staffCtx) staffUp   { return sc.up.Identity }
func upSeller(sc staffCtx) staffUp     { return sc.up.Seller }
func upOrders(sc staffCtx) staffUp     { return sc.up.Orders }
func upCatalog(sc staffCtx) staffUp    { return sc.up.Catalog }
func upTrustOps(sc staffCtx) staffUp   { return sc.up.TrustOps }
func upTaxReturns(sc staffCtx) staffUp { return sc.up.TaxReturns }
func upPayments(sc staffCtx) staffUp   { return sc.up.Payments }
func upInventory(sc staffCtx) staffUp  { return sc.up.Inventory }
func upRetail(sc staffCtx) staffUp     { return sc.up.Retail }
func upFulfilment(sc staffCtx) staffUp { return sc.up.Fulfilment }
func upContent(sc staffCtx) staffUp    { return sc.up.Content }

// StaffRecordsService serves the server-driven tables and detail sheets (/v1/staff/resources/...).
type StaffRecordsService interface {
	List(ctx context.Context, c *domain.Caller, resource string, q staffQueryInput) (domain.ResourcePage, error)
	Get(ctx context.Context, c *domain.Caller, resource, id string) (domain.RecordDetail, error)
	Create(ctx context.Context, c *domain.Caller, resource string, values map[string]any, idem string) (domain.RecordDetail, error)
	Update(ctx context.Context, c *domain.Caller, resource, id string, values map[string]any, version string) (domain.RecordDetail, error)
	Act(ctx context.Context, c *domain.Caller, resource, id, action string, values map[string]any, idem string) (domain.RecordDetail, error)
	AddNote(ctx context.Context, c *domain.Caller, resource, id, text string) (domain.RecordDetail, error)
}

// staffQueryInput is the table query as the HTTP adapter parsed it.
type staffQueryInput = StaffQuery

// StaffQuery is a table request: one search over every column, the sort (`<column>,asc|desc`, repeated), the status
// facet (kept for links; the console no longer shows facet chips), page or cursor, size.
type StaffQuery struct {
	Q, Status, Cursor string
	Sort              []string
	Page, Size        int
}

type staffRecordsImpl struct {
	r     *staffResolver
	res   map[string]*staffResource
	cache *scanCache[domain.Row]
}

var _ StaffRecordsService = (*staffRecordsImpl)(nil)

// NewStaffRecordsService returns the tables and sheets use case.
func NewStaffRecordsService(d StaffDeps) StaffRecordsService {
	r := newStaffResolver(d)
	return &staffRecordsImpl{r: r, res: staffResources(), cache: newScanCache[domain.Row](r.d.Clock)}
}

func notFoundResource() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such table.")
}

func (s *staffRecordsImpl) resource(name string) (*staffResource, error) {
	r, ok := s.res[name]
	if !ok {
		return nil, notFoundResource()
	}
	return r, nil
}

func (s *staffRecordsImpl) open(ctx context.Context, c *domain.Caller, name, level string) (*staffResource, staffCtx, error) {
	r, err := s.resource(name)
	if err != nil {
		return nil, staffCtx{}, err
	}
	sc, err := s.r.require(ctx, c, r.module, level)
	return r, sc, err
}

func (s *staffRecordsImpl) List(ctx context.Context, c *domain.Caller, name string, in staffQueryInput) (domain.ResourcePage, error) {
	r, err := s.resource(name)
	if err != nil {
		return domain.ResourcePage{}, err
	}
	sort, bad := domain.ParseSort(in.Sort, r.hasColumn)
	if bad != nil {
		return domain.ResourcePage{}, bad
	}
	r, sc, err := s.open(ctx, c, name, domain.LevelView)
	if err != nil {
		return domain.ResourcePage{}, err
	}
	q := staffQuery{Q: strings.TrimSpace(in.Q), Status: in.Status, Cursor: in.Cursor, Page: in.Page, Size: in.Size}
	if q.Size <= 0 || q.Size > 100 {
		q.Size = 25
	}
	if q.Status != "" && len(r.facets) > 0 && !slices.ContainsFunc(r.facets, func(f domain.Facet) bool { return f.Value == q.Status }) {
		return domain.ResourcePage{}, domain.ValidationProblem(map[string]string{"status": "Choose one of the filters"})
	}
	if q.Q == "" && len(sort) == 0 {
		// Upstream order and paging (page numbers or cursors).
		list, err := r.list(ctx, sc, staffQuery{Status: q.Status, Cursor: q.Cursor, Page: q.Page, Size: q.Size})
		if err != nil {
			return domain.ResourcePage{}, err
		}
		return s.page(r, sc, q, list, nil, false), nil
	}
	// Search over every column and sort by any column: the BFF reads the table and pages it (page numbers).
	rows, partial, err := s.cache.load(sc.me.ID, r.key, q.Status, func() ([]domain.Row, bool, error) {
		return scanResource(ctx, sc, r, q.Status, domain.TableScanMaxRows)
	})
	if err != nil {
		return domain.ResourcePage{}, err
	}
	rows = domain.FilterRows(rows, r.columns, domain.SearchTerms(q.Q), nil)
	domain.SortRows(rows, r.columns, sort)
	total := int64(len(rows))
	pageRows, _ := domain.PageRows(rows, q.Page, q.Size)
	p := s.page(r, sc, q, staffList{total: &total}, sort, partial)
	p.Rows = pageRows
	return p, nil
}

func (s *staffRecordsImpl) page(r *staffResource, sc staffCtx, q staffQuery, list staffList, sort []domain.SortKey, partial bool) domain.ResourcePage {
	cols := make([]domain.Column, len(r.columns))
	for i, c := range r.columns {
		c.Sortable = true
		cols[i] = c
	}
	// One search box matches every column the table shows.
	placeholder := "Search " + strings.ToLower(r.title)
	p := domain.ResourcePage{Resource: r.key, Title: r.title, Columns: cols, Rows: make([]domain.Row, 0, len(list.items)),
		Page: q.Page, Size: q.Size, Total: list.total, NextCursor: list.next, Facets: r.facets, Searchable: true,
		SearchPlaceholder: placeholder, Exportable: r.exportable, Sort: domain.SortStrings(sort), Partial: partial}
	if p.Facets == nil {
		p.Facets = []domain.Facet{}
	}
	for _, it := range list.items {
		p.Rows = append(p.Rows, rowOf(r, it))
	}
	switch {
	case list.next != nil:
		p.HasNext = true
	case list.total != nil:
		p.HasNext = int64((q.Page+1)*q.Size) < *list.total
	}
	if r.create != nil && sc.access.Can(r.create.permission) && sc.access.Allows(r.module, domain.LevelEdit) {
		p.CanCreate = true
		f := r.create.form(context.Background(), sc)
		p.CreateForm = &f
	}
	return p
}

func rowOf(r *staffResource, o jobj) domain.Row {
	var cells map[string]any
	if r.cells != nil {
		cells = r.cells(o)
	} else {
		cells = map[string]any{}
	}
	for _, c := range r.columns {
		if _, done := cells[c.Key]; !done {
			cells[c.Key] = cellValue(at(o, c.Key))
		}
	}
	return domain.Row{ID: r.id(o), Cells: cells, Tone: domain.StatusTone(r.statusOf(o))}
}

func (s *staffRecordsImpl) Get(ctx context.Context, c *domain.Caller, name, id string) (domain.RecordDetail, error) {
	r, sc, err := s.open(ctx, c, name, domain.LevelView)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	return s.sheet(ctx, r, sc, id)
}

func (s *staffRecordsImpl) sheet(ctx context.Context, r *staffResource, sc staffCtx, id string) (domain.RecordDetail, error) {
	o, err := r.get(ctx, sc, id)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	return s.detail(ctx, r, sc, o), nil
}

func (s *staffRecordsImpl) detail(ctx context.Context, r *staffResource, sc staffCtx, o jobj) domain.RecordDetail {
	status := r.statusOf(o)
	d := domain.RecordDetail{Resource: r.key, ID: r.id(o), Title: r.titleOf(o), Tone: domain.StatusTone(status),
		Tables: []domain.DetailTable{}, Activity: []domain.ActivityEntry{}, Notes: []domain.Note{}, Actions: []domain.RecordAction{},
		Links: []domain.RecordLink{}}
	if r.subtitleOf != nil {
		d.Subtitle = r.subtitleOf(o)
	}
	if status != "" {
		d.Status = &status
	}
	if r.sections != nil {
		d.Sections = r.sections(o)
	} else {
		d.Sections = autoSections(o, r.statusKey)
	}
	if r.tables != nil {
		d.Tables = r.tables(o)
	}
	if r.activity != nil {
		if a := r.activity(ctx, sc, o); a != nil {
			d.Activity = a
		}
	}
	if r.links != nil {
		if l := r.links(o); l != nil {
			d.Links = l
		}
	}
	if r.version != nil {
		if v := r.version(o); v != "" {
			d.Version = &v
		}
	}
	for _, a := range r.actions {
		d.Actions = append(d.Actions, actionView(ctx, r, sc, a, o))
	}
	if r.edit != nil && sc.access.Can(r.edit.permission) && sc.access.Allows(r.module, domain.LevelEdit) {
		f := r.edit.form(o)
		d.EditForm = &f
	}
	return d
}

// actionReason says why the staff member may not run the action now ("" when they may).
func actionReason(r *staffResource, sc staffCtx, a staffAction, o jobj) (reason string, permissionIssue bool) {
	level := a.level
	if level == "" {
		level = domain.LevelEdit
	}
	if !sc.access.Can(a.permission) && !sc.access.CanAny(a.also...) {
		return "Needs the " + a.permission + " permission.", true
	}
	if !sc.access.Allows(r.module, level) {
		return "Needs " + strings.ToLower(level) + " access to this module.", true
	}
	if a.when != nil {
		return a.when(sc, o), false
	}
	return "", false
}

func actionView(ctx context.Context, r *staffResource, sc staffCtx, a staffAction, o jobj) domain.RecordAction {
	reason, _ := actionReason(r, sc, a, o)
	v := domain.RecordAction{Key: a.key, Label: a.label, Tone: a.tone, Allowed: reason == "", Permission: a.permission}
	if reason != "" {
		v.Reason = &reason
	}
	if a.confirm != "" {
		v.Confirm = ptr(a.confirm)
	}
	if a.form != nil {
		v.Form = a.form(ctx, sc, o)
	}
	return v
}

func (s *staffRecordsImpl) Act(ctx context.Context, c *domain.Caller, name, id, action string, values map[string]any, idem string) (domain.RecordDetail, error) {
	defer s.cache.drop(name)
	r, sc, err := s.open(ctx, c, name, domain.LevelView)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	sc.idem = idem
	idx := slices.IndexFunc(r.actions, func(a staffAction) bool { return a.key == action })
	if idx < 0 {
		return domain.RecordDetail{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such action.")
	}
	a := r.actions[idx]
	o, err := r.get(ctx, sc, id)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	if reason, perm := actionReason(r, sc, a, o); reason != "" {
		if perm {
			return domain.RecordDetail{}, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", reason)
		}
		if a.settled == nil || !a.settled(o) {
			return domain.RecordDetail{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Not possible now", reason)
		}
	}
	if values == nil {
		values = map[string]any{}
	}
	if a.form != nil {
		if errs := domain.ValidateForm(*a.form(ctx, sc, o), values); len(errs) > 0 {
			return domain.RecordDetail{}, domain.ValidationProblem(errs)
		}
	}
	if err := a.run(ctx, sc, o, values); err != nil {
		return domain.RecordDetail{}, err
	}
	return s.sheet(ctx, r, sc, id)
}

func (s *staffRecordsImpl) Create(ctx context.Context, c *domain.Caller, name string, values map[string]any, idem string) (domain.RecordDetail, error) {
	defer s.cache.drop(name)
	r, sc, err := s.open(ctx, c, name, domain.LevelEdit)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	sc.idem = idem
	if r.create == nil {
		return domain.RecordDetail{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "Records of this table can't be created here.")
	}
	if !sc.access.Can(r.create.permission) {
		return domain.RecordDetail{}, needsPermission(r.create.permission)
	}
	if values == nil {
		values = map[string]any{}
	}
	if errs := domain.ValidateForm(r.create.form(ctx, sc), values); len(errs) > 0 {
		return domain.RecordDetail{}, domain.ValidationProblem(errs)
	}
	id, err := r.create.create(ctx, sc, values)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	return s.sheet(ctx, r, sc, id)
}

func (s *staffRecordsImpl) Update(ctx context.Context, c *domain.Caller, name, id string, values map[string]any, version string) (domain.RecordDetail, error) {
	defer s.cache.drop(name)
	r, sc, err := s.open(ctx, c, name, domain.LevelEdit)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	if r.edit == nil {
		return domain.RecordDetail{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Not editable", "This record can't be edited.")
	}
	if !sc.access.Can(r.edit.permission) {
		return domain.RecordDetail{}, needsPermission(r.edit.permission)
	}
	o, err := r.get(ctx, sc, id)
	if err != nil {
		return domain.RecordDetail{}, err
	}
	if version != "" && r.version != nil && r.version(o) != "" && r.version(o) != version {
		return domain.RecordDetail{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Changed meanwhile",
			"Someone else changed this record. Reload and try again.")
	}
	if values == nil {
		values = map[string]any{}
	}
	if errs := domain.ValidateForm(r.edit.form(o), values); len(errs) > 0 {
		return domain.RecordDetail{}, domain.ValidationProblem(errs)
	}
	if version == "" && r.version != nil {
		version = r.version(o)
	}
	if err := r.edit.save(ctx, sc, o, values, version); err != nil {
		return domain.RecordDetail{}, err
	}
	return s.sheet(ctx, r, sc, id)
}

// AddNote: no upstream stores internal notes on records yet (listed as a gap), so this is 503 after the access checks.
func (s *staffRecordsImpl) AddNote(ctx context.Context, c *domain.Caller, name, _, text string) (domain.RecordDetail, error) {
	if _, _, err := s.open(ctx, c, name, domain.LevelEdit); err != nil {
		return domain.RecordDetail{}, err
	}
	if t := strings.TrimSpace(text); t == "" || len([]rune(t)) > 2000 {
		return domain.RecordDetail{}, domain.ValidationProblem(map[string]string{"text": "Write a note of up to 2,000 characters"})
	}
	return domain.RecordDetail{}, domain.UpstreamUnavailable("notes")
}

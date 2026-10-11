package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"slices"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The export engine is shared by the staff console and Seller Center: it builds a file from an exportPlan (a table's
// columns and a row stream), stores it in the export blob store, keeps the job in the job store and hands out signed
// links that expire after 24 hours. Small exports finish within the request; bigger ones run in the background.

// ExportRequest is POST /v1/staff/exports and POST /v1/seller/exports: a server table (resource or table) with its
// search, sort and visible columns, optionally narrowed to selected rows; or a client-side table's rows (dataset).
type ExportRequest struct {
	// Resource is a staff table (staff exports).
	Resource string `json:"resource"`
	// Table is a Seller Center table (seller exports) or a staff table outside the resources (preorder-orders).
	Table string `json:"table"`
	// Parent scopes a table to a record (the pre-order window of preorder-orders).
	Parent  string         `json:"parent"`
	Format  string         `json:"format"`
	Q       string         `json:"q"`
	Status  string         `json:"status"`
	Sort    []string       `json:"sort"`
	IDs     []string       `json:"ids"`
	Columns []string       `json:"columns"`
	Dataset *ExportDataset `json:"dataset"`
}

// ExportDataset is a client-side table: its columns and rows (money in currency units, dates as ISO strings).
type ExportDataset struct {
	Table   string                `json:"table"`
	Title   string                `json:"title"`
	Columns []domain.ExportColumn `json:"columns"`
	Rows    [][]any               `json:"rows"`
	Scope   string                `json:"scope"`
}

// exportPlan is what to write.
type exportPlan struct {
	// key names the table (file name and job).
	key   string
	title string
	meta  domain.ExportMeta
	// small exports are built while the request waits.
	small bool
	rows  func(ctx context.Context, emit func(cells []any) error) error
}

type exportEngine struct {
	jobs       port.ExportJobStore
	blobs      port.ExportBlobStore
	render     port.ExportRenderer
	clock      Clock
	log        *slog.Logger
	background func(func())
	basePath   string
	portal     string
}

// asyncExportTimeout bounds a background export.
const asyncExportTimeout = 15 * time.Minute

// exportPageSize is how many rows each upstream page carries while exporting or scanning.
const exportPageSize = 100

func newExportID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return "exp_" + hex.EncodeToString(b)
}

func exportProblem(status int, code, title, detail string) *domain.Problem {
	return domain.NewProblem(status, code, title, detail)
}

func (e *exportEngine) ready() error {
	if e.jobs == nil || e.blobs == nil || e.render == nil {
		return domain.UpstreamUnavailable("exports")
	}
	return nil
}

// validateExport checks the parts every export shares.
func validateExport(req ExportRequest, errs map[string]string) {
	if !domain.IsExportFormat(req.Format) {
		errs["format"] = "Choose CSV, Excel or PDF"
	}
	if len(req.Q) > domain.MaxSearchLength {
		errs["q"] = "The search text is too long"
	}
	if len(req.IDs) > domain.MaxSelectedRows {
		errs["ids"] = fmt.Sprintf("Select at most %d rows to export", domain.MaxSelectedRows)
	}
	if len(req.Columns) > domain.ExportMaxColumns {
		errs["columns"] = "Too many columns"
	}
}

// pickColumns keeps the visible columns in the order the table shows them (all columns when none are named).
func pickColumns(all []domain.Column, keys []string) ([]domain.Column, error) {
	if len(keys) == 0 {
		return all, nil
	}
	out := make([]domain.Column, 0, len(keys))
	for _, k := range keys {
		i := slices.IndexFunc(all, func(c domain.Column) bool { return c.Key == k })
		if i < 0 {
			return nil, domain.ValidationProblem(map[string]string{"columns": fmt.Sprintf("Unknown column %q", k)})
		}
		if !slices.ContainsFunc(out, func(c domain.Column) bool { return c.Key == k }) {
			out = append(out, all[i])
		}
	}
	return out, nil
}

func exportColumns(cols []domain.Column) []domain.ExportColumn {
	out := make([]domain.ExportColumn, len(cols))
	for i, c := range cols {
		out[i] = domain.ExportColumn{Key: c.Key, Label: c.Label, Kind: c.Kind}
	}
	return out
}

// exportCell writes money in currency units (a number spreadsheets can sum) and everything else as shown.
func exportCell(kind string, v any) any {
	if n, ok := v.(float64); ok && kind == "money" {
		return n / 100
	}
	return v
}

// exportScope describes the rows in the file ("Search “ada” · sorted by Total desc · 3 selected rows").
func exportScope(cols []domain.Column, q string, sort []domain.SortKey, ids int, partial bool) string {
	parts := []string{}
	if ids > 0 {
		parts = append(parts, fmt.Sprintf("%d selected rows", ids))
	}
	if q != "" {
		parts = append(parts, "Search “"+q+"”")
	}
	for _, k := range sort {
		label := k.Key
		if i := slices.IndexFunc(cols, func(c domain.Column) bool { return c.Key == k.Key }); i >= 0 {
			label = cols[i].Label
		}
		dir := "ascending"
		if k.Desc {
			dir = "descending"
		}
		parts = append(parts, "sorted by "+label+" ("+dir+")")
	}
	if partial {
		parts = append(parts, fmt.Sprintf("search and sort cover the first %s rows", groupInt(domain.ExportScanMaxRows)))
	}
	if len(parts) == 0 {
		return "All rows"
	}
	return strings.Join(parts, " · ")
}

func groupInt(n int) string {
	s := fmt.Sprint(n)
	var b strings.Builder
	for i, r := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(r)
	}
	return b.String()
}

// datasetPlan validates a client-side table and plans its export.
func datasetPlan(ds *ExportDataset, keyPrefix string) (exportPlan, error) {
	errs := map[string]string{}
	switch {
	case len(ds.Columns) == 0 || len(ds.Columns) > domain.ExportMaxColumns:
		errs["dataset.columns"] = fmt.Sprintf("Send between 1 and %d columns", domain.ExportMaxColumns)
	case len(ds.Rows) > domain.ExportInlineMaxRows:
		errs["dataset.rows"] = fmt.Sprintf("At most %s rows; narrow the search", groupInt(domain.ExportInlineMaxRows))
	case !safeKey(ds.Table):
		errs["dataset.table"] = "Name the table (letters, digits and dashes)"
	case len(ds.Title) > 120 || len(ds.Scope) > 200:
		errs["dataset.title"] = "Too long"
	}
	for i, c := range ds.Columns {
		if !slices.Contains(columnKinds, c.Kind) || c.Label == "" || len(c.Label) > 80 {
			errs[fmt.Sprintf("dataset.columns[%d]", i)] = "Each column needs a label and a known kind"
			break
		}
	}
	for i, r := range ds.Rows {
		if len(r) != len(ds.Columns) {
			errs[fmt.Sprintf("dataset.rows[%d]", i)] = "Each row needs one cell per column"
			break
		}
		if bad := slices.IndexFunc(r, func(v any) bool { return !scalarCell(v) }); bad >= 0 {
			errs[fmt.Sprintf("dataset.rows[%d]", i)] = "Cells are text, numbers, booleans or null"
			break
		}
	}
	if len(errs) > 0 {
		return exportPlan{}, domain.ValidationProblem(errs)
	}
	title := ds.Title
	if title == "" {
		title = domain.Humanize(ds.Table)
	}
	scope := ds.Scope
	if scope == "" {
		scope = "All rows"
	}
	rows := ds.Rows
	return exportPlan{key: keyPrefix + ds.Table, title: title, small: true,
		meta: domain.ExportMeta{Title: title, Columns: ds.Columns, Scope: scope},
		rows: func(_ context.Context, emit func([]any) error) error {
			for _, r := range rows {
				if err := emit(r); err != nil {
					return err
				}
			}
			return nil
		}}, nil
}

var columnKinds = []string{"text", "strong", "mono", "money", "number", "percent", "date", "datetime", "status"}

func safeKey(s string) bool {
	if s == "" || len(s) > 60 {
		return false
	}
	for _, r := range s {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' {
			return false
		}
	}
	return true
}

func scalarCell(v any) bool {
	switch x := v.(type) {
	case nil, float64, bool:
		return true
	case string:
		return len(x) <= 2000
	default:
		return false
	}
}

// start builds a small export now (ready) or queues a big one.
func (e *exportEngine) start(ctx context.Context, owner, org, bearer, format string, plan exportPlan) (domain.ExportJob, bool, error) {
	if err := e.ready(); err != nil {
		return domain.ExportJob{}, false, err
	}
	now := e.clock().UTC()
	plan.meta.GeneratedAt = now
	if plan.meta.Title == "" {
		plan.meta.Title = plan.title
	}
	job := domain.StoredExportJob{ExportJob: domain.ExportJob{ID: newExportID(), Resource: plan.key, Title: plan.title, Format: format, Status: domain.ExportQueued,
		FileName: fmt.Sprintf("%s-%s.%s", plan.key, now.Format("2006-01-02-1504"), format), CreatedAt: now},
		Owner: owner, Portal: e.portal, OrgID: org, Scope: plan.meta.Scope}
	if plan.small {
		job.Status = domain.ExportRunning
		e.build(ctx, bearer, &job, format, plan)
		if err := e.save(ctx, job); err != nil {
			return domain.ExportJob{}, false, err
		}
		if job.Status == domain.ExportFailed {
			return domain.ExportJob{}, false, domain.UpstreamUnavailable("exports")
		}
		return e.view(job), true, nil
	}
	job.Async = true
	if err := e.save(ctx, job); err != nil {
		return domain.ExportJob{}, false, err
	}
	queued := e.view(job)
	// The job outlives the request: detach from its cancellation, keep its values (trace, token relay).
	bg, bgJob := context.WithoutCancel(ctx), job
	e.background(func() {
		bctx, cancel := context.WithTimeout(bg, asyncExportTimeout)
		defer cancel()
		bgJob.Status = domain.ExportRunning
		_ = e.save(bctx, bgJob)
		e.build(bctx, bearer, &bgJob, format, plan)
		if err := e.save(bctx, bgJob); err != nil {
			e.log.WarnContext(bctx, "export job not saved", "job", bgJob.ID, "error", err.Error())
		}
	})
	return queued, false, nil
}

// build writes the file into a temporary file, stores it and marks the job READY (or FAILED).
func (e *exportEngine) build(ctx context.Context, bearer string, job *domain.StoredExportJob, format string, plan exportPlan) {
	fail := func(err error) {
		e.log.WarnContext(ctx, "export failed", "job", job.ID, "table", job.Resource, "error", err.Error())
		job.Status = domain.ExportFailed
		msg := "The export could not be built. Please try again."
		var p *domain.Problem
		if errors.As(err, &p) && p.Status == http.StatusServiceUnavailable {
			msg = "A service behind this table is unavailable. Please try again shortly."
		}
		job.Error = &msg
		t := e.clock().UTC()
		job.FinishedAt = &t
	}
	tmp, err := os.CreateTemp("", "table-export-*")
	if err != nil {
		fail(fmt.Errorf("temp file: %w", err))
		return
	}
	defer func() { _ = os.Remove(tmp.Name()) }()
	defer func() { _ = tmp.Close() }()
	rows, err := e.write(ctx, format, plan, tmp)
	if err != nil {
		fail(err)
		return
	}
	size, err := tmp.Seek(0, io.SeekCurrent)
	if err != nil {
		fail(fmt.Errorf("size: %w", err))
		return
	}
	if _, err := tmp.Seek(0, io.SeekStart); err != nil {
		fail(fmt.Errorf("rewind: %w", err))
		return
	}
	ref, err := e.blobs.Put(ctx, bearer, port.ExportFile{JobID: job.ID, FileName: job.FileName, ContentType: domain.ExportContentType(format), Size: size, Body: tmp})
	if err != nil {
		fail(fmt.Errorf("store: %w", err))
		return
	}
	t := e.clock().UTC()
	exp := t.Add(domain.ExportLinkTTL)
	job.Status, job.Rows, job.Ref, job.FinishedAt, job.ExpiresAt = domain.ExportReady, rows, ref, &t, &exp
}

// errExportFull stops a row stream at domain.ExportMaxRows.
var errExportFull = errors.New("export row cap reached")

func (e *exportEngine) write(ctx context.Context, format string, plan exportPlan, out io.Writer) (int, error) {
	w, err := e.render.Start(format, plan.meta, out)
	if err != nil {
		return 0, fmt.Errorf("export start: %w", err)
	}
	rows := 0
	err = plan.rows(ctx, func(cells []any) error {
		if err := w.Row(cells); err != nil {
			return fmt.Errorf("export row: %w", err)
		}
		rows++
		if rows >= domain.ExportMaxRows {
			return errExportFull
		}
		if rows%exportPageSize == 0 {
			if err := ctx.Err(); err != nil {
				return fmt.Errorf("export cancelled: %w", err)
			}
		}
		return nil
	})
	if err != nil && !errors.Is(err, errExportFull) {
		return rows, err
	}
	if err := w.Close(); err != nil {
		return rows, fmt.Errorf("export close: %w", err)
	}
	return rows, nil
}

func (e *exportEngine) save(ctx context.Context, job domain.StoredExportJob) error {
	ttl := domain.ExportLinkTTL + time.Hour
	if err := e.jobs.Save(ctx, job, ttl); err != nil {
		return domain.UpstreamUnavailable("exports").WithCause(err)
	}
	return nil
}

// view is the job as its owner sees it (expired links are reported as EXPIRED).
func (e *exportEngine) view(job domain.StoredExportJob) domain.ExportJob {
	v := job.ExportJob
	if v.Title == "" {
		v.Title = domain.Humanize(v.Resource)
	}
	if v.Status == domain.ExportReady && v.ExpiresAt != nil && e.clock().After(*v.ExpiresAt) {
		v.Status = domain.ExportExpired
	}
	if v.Status == domain.ExportReady {
		u := strings.TrimSuffix(e.basePath, "/") + "/v1/" + e.portal + "/exports/" + v.ID + "/download"
		v.DownloadURL = &u
	} else {
		v.DownloadURL = nil
	}
	return v
}

// owned reads a job of this portal that belongs to the owner (and, for Seller Center, to the active organisation).
func (e *exportEngine) owned(ctx context.Context, owner, org, id string) (domain.StoredExportJob, error) {
	if e.jobs == nil {
		return domain.StoredExportJob{}, domain.UpstreamUnavailable("exports")
	}
	job, err := e.jobs.Get(ctx, id)
	portal := job.Portal
	if portal == "" {
		portal = "staff"
	}
	if err != nil || job.Owner != owner || portal != e.portal || job.OrgID != org {
		return domain.StoredExportJob{}, exportProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such export.")
	}
	return job, nil
}

func (e *exportEngine) list(ctx context.Context, owner, org string) ([]domain.ExportJob, error) {
	out := []domain.ExportJob{}
	if e.jobs == nil {
		return out, nil
	}
	jobs, err := e.jobs.List(ctx, owner)
	if err != nil {
		return nil, domain.UpstreamUnavailable("exports").WithCause(err)
	}
	for _, j := range jobs {
		portal := j.Portal
		if portal == "" {
			portal = "staff"
		}
		if portal == e.portal && j.OrgID == org {
			out = append(out, e.view(j))
		}
	}
	return out, nil
}

func (e *exportEngine) download(ctx context.Context, bearer string, job domain.StoredExportJob) (string, error) {
	v := e.view(job)
	switch v.Status {
	case domain.ExportExpired:
		return "", exportProblem(http.StatusGone, domain.CodeExportExpired, "Link expired", "Download links last 24 hours. Export the table again.")
	case domain.ExportReady:
	default:
		return "", exportProblem(http.StatusConflict, domain.CodeExportNotReady, "Not ready", "The export is still being built.")
	}
	u, err := e.blobs.SignedURL(ctx, bearer, job.Ref, job.FileName, *job.ExpiresAt)
	if err != nil {
		return "", mapErr("exports", err)
	}
	return u, nil
}

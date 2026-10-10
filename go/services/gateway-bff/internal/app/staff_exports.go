package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffExportsService builds large exports on the server: it streams every page of a table's upstream list into a
// CSV or XLSX file, stores it (BFF file store or media) and hands out signed links that expire after 24 hours.
type StaffExportsService interface {
	// Create starts an export; created is true when the file was built within the request (201) and false when the job
	// was queued (202).
	Create(ctx context.Context, c *domain.Caller, req ExportRequest, idem string) (job domain.ExportJob, ready bool, err error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.ExportJob, error)
	List(ctx context.Context, c *domain.Caller) ([]domain.ExportJob, error)
	// Download returns the signed URL of a ready export.
	Download(ctx context.Context, c *domain.Caller, id string) (string, error)
}

// ExportRequest is POST /v1/staff/exports.
type ExportRequest struct {
	Resource string `json:"resource"`
	Format   string `json:"format"`
	Q        string `json:"q"`
	Status   string `json:"status"`
}

type staffExportsImpl struct {
	r   *staffResolver
	res map[string]*staffResource
}

var _ StaffExportsService = (*staffExportsImpl)(nil)

// NewStaffExportsService returns the exports use case.
func NewStaffExportsService(d StaffDeps) StaffExportsService {
	return &staffExportsImpl{r: newStaffResolver(d), res: staffResources()}
}

// exportPageSize is how many rows each upstream page carries while exporting.
const exportPageSize = 100

// asyncExportTimeout bounds a background export.
const asyncExportTimeout = 15 * time.Minute

func newExportID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return "exp_" + hex.EncodeToString(b)
}

func exportProblem(status int, code, title, detail string) *domain.Problem {
	return domain.NewProblem(status, code, title, detail)
}

func (s *staffExportsImpl) Create(ctx context.Context, c *domain.Caller, req ExportRequest, idem string) (domain.ExportJob, bool, error) {
	errs := map[string]string{}
	r, ok := s.res[req.Resource]
	if !ok {
		errs["resource"] = "Choose a table to export"
	} else if !r.exportable {
		errs["resource"] = "This table can't be exported"
	}
	if req.Format != "csv" && req.Format != "xlsx" {
		errs["format"] = "Choose CSV or Excel"
	}
	if len(req.Q) > 120 {
		errs["q"] = "The search text is too long"
	}
	if len(errs) > 0 {
		return domain.ExportJob{}, false, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, r.module, domain.LevelView)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	if s.r.d.Jobs == nil || s.r.d.Blobs == nil {
		return domain.ExportJob{}, false, domain.UpstreamUnavailable("exports")
	}
	q := staffQuery{Q: req.Q, Status: req.Status, Size: exportPageSize}
	first, err := r.list(ctx, sc, q)
	if err != nil {
		return domain.ExportJob{}, false, err
	}
	now := sc.now.UTC()
	job := domain.StoredExportJob{ExportJob: domain.ExportJob{ID: newExportID(), Resource: r.key, Format: req.Format, Status: domain.ExportQueued,
		FileName: fmt.Sprintf("%s-%s.%s", r.key, now.Format("2006-01-02-1504"), req.Format), CreatedAt: now},
		Owner: sc.me.ID, Query: req.Q, StatusQ: req.Status}
	small := (first.total != nil && *first.total <= domain.ExportSyncRows) || (first.total == nil && first.next == nil)
	if small {
		job.Status = domain.ExportRunning
		s.build(ctx, sc, r, &job, q, first)
		if err := s.save(ctx, job); err != nil {
			return domain.ExportJob{}, false, err
		}
		if job.Status == domain.ExportFailed {
			return domain.ExportJob{}, false, domain.UpstreamUnavailable("exports")
		}
		return s.view(job), true, nil
	}
	job.Async = true
	if err := s.save(ctx, job); err != nil {
		return domain.ExportJob{}, false, err
	}
	queued := s.view(job)
	// The job outlives the request: detach from its cancellation, keep its values (trace, token relay).
	bg, bgJob := context.WithoutCancel(ctx), job
	s.r.d.Background(func() {
		bctx, cancel := context.WithTimeout(bg, asyncExportTimeout)
		defer cancel()
		bgJob.Status = domain.ExportRunning
		_ = s.save(bctx, bgJob)
		s.build(bctx, sc, r, &bgJob, q, first)
		if err := s.save(bctx, bgJob); err != nil {
			s.r.d.Log.WarnContext(bctx, "export job not saved", "job", bgJob.ID, "error", err.Error())
		}
	})
	return queued, false, nil
}

// build streams every page into a temporary file, stores it and marks the job READY (or FAILED).
func (s *staffExportsImpl) build(ctx context.Context, sc staffCtx, r *staffResource, job *domain.StoredExportJob, q staffQuery, first staffList) {
	fail := func(err error) {
		s.r.d.Log.WarnContext(ctx, "export failed", "job", job.ID, "resource", job.Resource, "error", err.Error())
		job.Status = domain.ExportFailed
		msg := "The export could not be built. Please try again."
		var p *domain.Problem
		if errors.As(err, &p) && p.Status == http.StatusServiceUnavailable {
			msg = "A service behind this table is unavailable. Please try again shortly."
		}
		job.Error = &msg
		t := s.r.d.Clock().UTC()
		job.FinishedAt = &t
	}
	tmp, err := os.CreateTemp("", "staff-export-*")
	if err != nil {
		fail(fmt.Errorf("temp file: %w", err))
		return
	}
	defer func() { _ = os.Remove(tmp.Name()) }()
	defer func() { _ = tmp.Close() }()
	rows, err := s.write(ctx, sc, r, job.Format, tmp, q, first)
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
	ct := "text/csv; charset=utf-8"
	if job.Format == "xlsx" {
		ct = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
	}
	ref, err := s.r.d.Blobs.Put(ctx, sc.bearer, port.ExportFile{JobID: job.ID, FileName: job.FileName, ContentType: ct, Size: size, Body: tmp})
	if err != nil {
		fail(fmt.Errorf("store: %w", err))
		return
	}
	t := s.r.d.Clock().UTC()
	exp := t.Add(domain.ExportLinkTTL)
	job.Status, job.Rows, job.Ref, job.FinishedAt, job.ExpiresAt = domain.ExportReady, rows, ref, &t, &exp
}

// write streams the pages of a table into the writer and returns the number of rows.
func (s *staffExportsImpl) write(ctx context.Context, sc staffCtx, r *staffResource, format string, out io.Writer, q staffQuery, page staffList) (int, error) {
	w, err := newExportWriter(format, out)
	if err != nil {
		return 0, err
	}
	header := make([]string, len(r.columns))
	for i, c := range r.columns {
		header[i] = c.Label
	}
	if err := w.Header(header); err != nil {
		return 0, fmt.Errorf("export header: %w", err)
	}
	rows := 0
	for {
		for _, o := range page.items {
			row := rowOf(r, o)
			cells := make([]any, len(r.columns))
			for i, c := range r.columns {
				cells[i] = exportCell(c, row.Cells[c.Key])
			}
			if err := w.Row(cells); err != nil {
				return rows, fmt.Errorf("export row: %w", err)
			}
			rows++
			if rows >= domain.ExportMaxRows {
				return rows, closeWriter(w)
			}
		}
		switch {
		case page.next != nil:
			q.Cursor = *page.next
		case q.Cursor == "" && page.total != nil && int64((q.Page+1)*q.Size) < *page.total && len(page.items) > 0:
			q.Page++
		default:
			return rows, closeWriter(w)
		}
		if err := ctx.Err(); err != nil {
			return rows, fmt.Errorf("export cancelled: %w", err)
		}
		if page, err = r.list(ctx, sc, q); err != nil {
			return rows, err
		}
	}
}

func closeWriter(w exportWriter) error {
	if err := w.Close(); err != nil {
		return fmt.Errorf("export close: %w", err)
	}
	return nil
}

// exportCell writes money in currency units (a number spreadsheets can sum) and everything else as shown.
func exportCell(c domain.Column, v any) any {
	if n, ok := v.(float64); ok && c.Kind == "money" {
		return n / 100
	}
	return v
}

func (s *staffExportsImpl) save(ctx context.Context, job domain.StoredExportJob) error {
	ttl := domain.ExportLinkTTL + time.Hour
	if err := s.r.d.Jobs.Save(ctx, job, ttl); err != nil {
		return domain.UpstreamUnavailable("exports").WithCause(err)
	}
	return nil
}

// view is the job as its owner sees it (expired links are reported as EXPIRED).
func (s *staffExportsImpl) view(job domain.StoredExportJob) domain.ExportJob {
	v := job.ExportJob
	if v.Status == domain.ExportReady && v.ExpiresAt != nil && s.r.d.Clock().After(*v.ExpiresAt) {
		v.Status = domain.ExportExpired
	}
	if v.Status == domain.ExportReady {
		u := strings.TrimSuffix(s.r.d.ExportBasePath, "/") + "/v1/staff/exports/" + v.ID + "/download"
		v.DownloadURL = &u
	} else {
		v.DownloadURL = nil
	}
	return v
}

func (s *staffExportsImpl) owned(ctx context.Context, c *domain.Caller, id string) (domain.StoredExportJob, staffCtx, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return domain.StoredExportJob{}, sc, err
	}
	if s.r.d.Jobs == nil {
		return domain.StoredExportJob{}, sc, domain.UpstreamUnavailable("exports")
	}
	job, err := s.r.d.Jobs.Get(ctx, id)
	if err != nil || job.Owner != sc.me.ID {
		return domain.StoredExportJob{}, sc, exportProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such export.")
	}
	return job, sc, nil
}

func (s *staffExportsImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.ExportJob, error) {
	job, _, err := s.owned(ctx, c, id)
	if err != nil {
		return domain.ExportJob{}, err
	}
	return s.view(job), nil
}

func (s *staffExportsImpl) List(ctx context.Context, c *domain.Caller) ([]domain.ExportJob, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	out := []domain.ExportJob{}
	if s.r.d.Jobs == nil {
		return out, nil
	}
	jobs, err := s.r.d.Jobs.List(ctx, sc.me.ID)
	if err != nil {
		return nil, domain.UpstreamUnavailable("exports").WithCause(err)
	}
	for _, j := range jobs {
		out = append(out, s.view(j))
	}
	return out, nil
}

func (s *staffExportsImpl) Download(ctx context.Context, c *domain.Caller, id string) (string, error) {
	job, sc, err := s.owned(ctx, c, id)
	if err != nil {
		return "", err
	}
	v := s.view(job)
	switch v.Status {
	case domain.ExportExpired:
		return "", exportProblem(http.StatusGone, domain.CodeExportExpired, "Link expired", "Download links last 24 hours. Export the table again.")
	case domain.ExportReady:
	default:
		return "", exportProblem(http.StatusConflict, domain.CodeExportNotReady, "Not ready", "The export is still being built.")
	}
	u, err := s.r.d.Blobs.SignedURL(ctx, sc.bearer, job.Ref, job.FileName, *job.ExpiresAt)
	if err != nil {
		return "", mapErr("exports", err)
	}
	return u, nil
}

// Package exportfiles builds and stores server-side table exports (staff console and Seller Center): the renderer
// (CSV, XLSX with a Summary sheet of native Excel charts, PDF with a summary page of charts), a file store on the BFF's
// disk (default) that serves files behind HMAC-signed, expiring links, and an in-memory job store for tests and
// single-replica runs.
package exportfiles

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Store is a blob store that also serves its files (port.LocalExportFiles).
type Store interface {
	port.ExportBlobStore
	port.LocalExportFiles
}

// ErrBadSignature and ErrExpired are returned by Open for invalid or expired links.
var (
	ErrBadSignature = errors.New("export link signature is invalid")
	ErrExpired      = errors.New("export link expired")
)

type localImpl struct {
	dir       string
	key       []byte
	basePath  string
	filesPath string
	now       func() time.Time
	mu        sync.Mutex
}

var _ Store = (*localImpl)(nil)

// Options configure the local store.
type Options struct {
	// Dir holds the files (created when missing).
	Dir string
	// SigningKey signs download links (secret; the BFF session signing key is fine).
	SigningKey string
	// BasePath prefixes signed links as the browser sees them (the app's /api/bff proxy).
	BasePath string
	// FilesPath is the BFF route that serves the files (default /v1/staff/export-files/; Seller Center uses
	// /v1/seller/export-files/). Stores of both portals may share Dir and SigningKey.
	FilesPath string
	Now       func() time.Time
}

// NewLocal returns the disk-backed export store.
func NewLocal(o Options) (Store, error) {
	if o.Dir == "" {
		o.Dir = filepath.Join(os.TempDir(), "afrotransact-staff-exports")
	}
	if err := os.MkdirAll(o.Dir, 0o700); err != nil {
		return nil, fmt.Errorf("export dir: %w", err)
	}
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.FilesPath == "" {
		o.FilesPath = "/v1/staff/export-files/"
	}
	key := sha256.Sum256([]byte("gateway-bff/staff-exports/v1:" + o.SigningKey))
	return &localImpl{dir: o.Dir, key: key[:], basePath: strings.TrimSuffix(o.BasePath, "/"), filesPath: "/" + strings.Trim(o.FilesPath, "/") + "/", now: o.Now}, nil
}

var safeID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

type meta struct {
	FileName    string `json:"fileName"`
	ContentType string `json:"contentType"`
}

func (l *localImpl) paths(id string) (string, string, error) {
	if !safeID.MatchString(id) {
		return "", "", port.ErrNotFound
	}
	return filepath.Join(l.dir, id+".bin"), filepath.Join(l.dir, id+".json"), nil
}

func (l *localImpl) Put(_ context.Context, _ string, f port.ExportFile) (string, error) {
	data, metaPath, err := l.paths(f.JobID)
	if err != nil {
		return "", fmt.Errorf("export id: %w", err)
	}
	l.sweep()
	out, err := os.OpenFile(data, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600) //nolint:gosec // path built from a validated id inside the store directory
	if err != nil {
		return "", fmt.Errorf("create export file: %w", err)
	}
	if _, err := io.Copy(out, f.Body); err != nil {
		_ = out.Close()
		return "", fmt.Errorf("write export file: %w", err)
	}
	if err := out.Close(); err != nil {
		return "", fmt.Errorf("close export file: %w", err)
	}
	m, err := json.Marshal(meta{FileName: f.FileName, ContentType: f.ContentType})
	if err != nil {
		return "", fmt.Errorf("encode export meta: %w", err)
	}
	if err := os.WriteFile(metaPath, m, 0o600); err != nil {
		return "", fmt.Errorf("write export meta: %w", err)
	}
	return f.JobID, nil
}

// sweep deletes files older than the link lifetime plus an hour (best effort).
func (l *localImpl) sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	entries, err := os.ReadDir(l.dir)
	if err != nil {
		return
	}
	cutoff := l.now().Add(-domain.ExportLinkTTL - time.Hour)
	for _, e := range entries {
		if info, err := e.Info(); err == nil && info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(l.dir, e.Name()))
		}
	}
}

func (l *localImpl) sign(id string, expires int64) string {
	m := hmac.New(sha256.New, l.key)
	_, _ = m.Write([]byte(id + "\n" + strconv.FormatInt(expires, 10)))
	return hex.EncodeToString(m.Sum(nil))
}

func (l *localImpl) SignedURL(_ context.Context, _, ref, _ string, expires time.Time) (string, error) {
	exp := expires.Unix()
	q := url.Values{"expires": {strconv.FormatInt(exp, 10)}, "sig": {l.sign(ref, exp)}}
	return l.basePath + l.filesPath + url.PathEscape(ref) + "?" + q.Encode(), nil
}

func (l *localImpl) Open(_ context.Context, id string, expires int64, sig string) (io.ReadCloser, string, error) {
	want := l.sign(id, expires)
	if !hmac.Equal([]byte(want), []byte(sig)) {
		return nil, "", ErrBadSignature
	}
	if l.now().Unix() > expires {
		return nil, "", ErrExpired
	}
	data, metaPath, err := l.paths(id)
	if err != nil {
		return nil, "", err
	}
	var m meta
	b, err := os.ReadFile(metaPath) //nolint:gosec // the path is built from a validated id inside the store's directory
	if err != nil {
		return nil, "", port.ErrNotFound
	}
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, "", fmt.Errorf("decode export meta: %w", err)
	}
	f, err := os.Open(data) //nolint:gosec // see above
	if err != nil {
		return nil, "", port.ErrNotFound
	}
	return &namedFile{File: f, name: m.FileName}, m.ContentType, nil
}

// namedFile carries the download file name.
type namedFile struct {
	*os.File
	name string
}

// FileName is the name the browser saves the export as.
func (n *namedFile) FileName() string { return n.name }

// ---------------------------------------------------------------- in-memory job store

type memJobs struct {
	mu   sync.Mutex
	jobs map[string]domain.StoredExportJob
}

var _ port.ExportJobStore = (*memJobs)(nil)

// NewMemoryJobs returns an in-process job store (tests and single-replica runs).
func NewMemoryJobs() port.ExportJobStore { return &memJobs{jobs: map[string]domain.StoredExportJob{}} }

func (m *memJobs) Save(_ context.Context, job domain.StoredExportJob, _ time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.jobs[job.ID] = job
	return nil
}

func (m *memJobs) Get(_ context.Context, id string) (domain.StoredExportJob, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	j, ok := m.jobs[id]
	if !ok {
		return domain.StoredExportJob{}, port.ErrNotFound
	}
	return j, nil
}

func (m *memJobs) List(_ context.Context, owner string) ([]domain.StoredExportJob, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []domain.StoredExportJob{}
	for _, j := range m.jobs {
		if j.Owner == owner {
			out = append(out, j)
		}
	}
	slices.SortFunc(out, func(a, b domain.StoredExportJob) int { return b.CreatedAt.Compare(a.CreatedAt) })
	return out, nil
}

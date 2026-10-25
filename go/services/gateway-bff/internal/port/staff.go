package port

import (
	"context"
	"io"
	"net/url"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Ports for the staff console routes (contracts/openapi/gateway-bff-staff.yaml). Every call relays the staff member's
// access token; each upstream checks its own permission again (the BFF checks first to answer 403 early and to grey
// out actions on the sheets).

// StaffCall is one JSON call to an upstream on behalf of a staff member.
type StaffCall struct {
	Method string
	Path   string
	Query  url.Values
	// Body is marshalled as JSON when not nil.
	Body any
	// IdempotencyKey is relayed as Idempotency-Key.
	IdempotencyKey string
	// IfMatch is relayed as If-Match (optimistic locking, e.g. retail purchase orders).
	IfMatch string
	// Accept is the media type a streamed document is asked for (default text/csv).
	Accept string
	// StepUp is relayed as X-Step-Up: the proof identity issued after the staff member re-entered their password
	// (identity admin console, S291). Never logged.
	StepUp string
	// Slow uses the upstream's slowTimeout (calls that wait for a downstream chain, e.g. late pre-order adds).
	Slow bool
}

// StaffUpstream is one domain service as the staff routes see it: authenticated JSON calls decoded into out
// (a struct, a map or a slice; nil to discard). Errors follow the httpclient rules: domain.ErrUpstreamUnavailable for
// unreachable services or routes they do not serve yet, *UpstreamProblem for other 4xx answers.
type StaffUpstream interface {
	Name() string
	Call(ctx context.Context, bearer string, call StaffCall, out any) error
}

// StaffUpstreams are the services behind the staff console.
type StaffUpstreams struct {
	Identity      StaffUpstream
	Seller        StaffUpstream
	Orders        StaffUpstream
	Catalog       StaffUpstream
	TrustOps      StaffUpstream
	TaxReturns    StaffUpstream
	Payments      StaffUpstream
	Inventory     StaffUpstream
	Retail        StaffUpstream
	Fulfilment    StaffUpstream
	Content       StaffUpstream
	Notifications StaffUpstream
	Bookings      StaffUpstream
	// DeliveryRuns owns pre-order windows, coverage areas and late adds (S270–S275).
	DeliveryRuns StaffUpstream
	// Media signs creative uploads; Geo verifies late-add addresses (Pre-orders module).
	Media StaffUpstream
	Geo   StaffUpstream
	// Offers and Search are read by the Inventory hub's trace (offer inventory SKU, buy box, search index; S281).
	Offers StaffUpstream
	Search StaffUpstream
	// Carrier is carrier-gateway: customer pickup points (Delivery dispatch › Pickup points).
	Carrier StaffUpstream
	// Proof signs the links to a delivery's proof photo and signature on an order's sheet (optional: none without it).
	Proof ProofLinks
}

// ExportJobStore keeps export jobs (shared by every BFF replica; jobs live 24 hours after they finish).
type ExportJobStore interface {
	Save(ctx context.Context, job domain.StoredExportJob, ttl time.Duration) error
	Get(ctx context.Context, id string) (domain.StoredExportJob, error)
	// List returns the owner's jobs, newest first.
	List(ctx context.Context, owner string) ([]domain.StoredExportJob, error)
}

// ExportFile is a finished export to store.
type ExportFile struct {
	JobID       string
	FileName    string
	ContentType string
	Size        int64
	Body        io.Reader
}

// ExportBlobStore stores finished export files and signs time-limited download URLs.
type ExportBlobStore interface {
	// Put stores the file and returns its reference.
	Put(ctx context.Context, bearer string, f ExportFile) (string, error)
	// SignedURL returns a URL that serves the file until expires.
	SignedURL(ctx context.Context, bearer, ref, fileName string, expires time.Time) (string, error)
}

// ExportRenderer writes export files: CSV (data only), XLSX (a Data sheet plus a Summary sheet of native Excel charts)
// and PDF (a summary page of charts and key totals, then the table in landscape with repeated headers).
type ExportRenderer interface {
	// Start begins a file of the format on out; add rows with Row and finish with Close.
	Start(format string, meta domain.ExportMeta, out io.Writer) (ExportFileWriter, error)
}

// ExportFileWriter receives the rows of one export (cells in column order; money in currency units, dates as ISO
// strings) and writes the rest of the file on Close.
type ExportFileWriter interface {
	Row(cells []any) error
	Close() error
}

// LocalExportFiles is implemented by blob stores that serve files themselves (behind GET /v1/staff/export-files/{id}).
type LocalExportFiles interface {
	// Open checks the signature and expiry of a signed link and opens the file.
	Open(ctx context.Context, id string, expires int64, sig string) (io.ReadCloser, string, error)
}

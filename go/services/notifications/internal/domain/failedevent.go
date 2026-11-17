package domain

import (
	"fmt"
	"net/http"
	"strings"
	"time"
)

// UpstreamError is a non-success answer from a service this module calls (identity, delivery-runs). It carries the
// status so the event consumer can tell a transient failure (5xx, 408, 429: try again) from a permanent one (other
// 4xx: the same call fails the same way every time).
type UpstreamError struct {
	Service string // "identity"
	Path    string
	Status  int
	// Code is the RFC 9457 problem's "code" (for example identity.service-lookup-only), empty when the body had none.
	Code string
	// Detail is a short, trimmed excerpt of the response body.
	Detail string
}

func (e *UpstreamError) Error() string {
	return fmt.Sprintf("%s %s: status %d: %s", e.Service, e.Path, e.Status, strings.TrimSpace(e.Detail))
}

// Transient reports whether trying again can succeed.
func (e *UpstreamError) Transient() bool {
	return e.Status >= http.StatusInternalServerError || e.Status == http.StatusRequestTimeout ||
		e.Status == http.StatusTooEarly || e.Status == http.StatusTooManyRequests
}

// FailedEvent is a consumed event that could not be turned into notifications and was set aside so the consumer can
// commit its offset and carry on. It holds no recipient data apart from the original payload, kept for replay.
type FailedEvent struct {
	Consumer    string
	EventID     string
	EventType   string
	AggregateID string
	Reason      string // metric label: upstream_client_error, rejected, retries_exhausted
	ErrorClass  string // permanent or transient
	Status      int    // upstream HTTP status, 0 when none
	Code        string // upstream problem code, empty when none
	Attempts    int
	Payload     []byte
	FailedAt    time.Time
}

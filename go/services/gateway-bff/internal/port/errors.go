// Package port declares the BFF's outbound interfaces: one per upstream service (HTTP adapters in
// internal/adapter/upstreams) plus session, selection, rate-limit and cache stores (internal/adapter/valkey,
// internal/adapter/memcache).
package port

import (
	"errors"
	"fmt"
)

// ErrNotFound is returned when an upstream says the resource does not exist (404 with a problem code).
var ErrNotFound = errors.New("not found")

// UpstreamProblem is a 4xx answer from an upstream, decoded from its RFC 9457 body when present.
type UpstreamProblem struct {
	Upstream   string
	Status     int
	Code       string
	Detail     string
	Fields     map[string]string
	RetryAfter int
}

func (p *UpstreamProblem) Error() string {
	return fmt.Sprintf("%s: %d %s %s", p.Upstream, p.Status, p.Code, p.Detail)
}

// Is lets errors.Is(err, ErrNotFound) match 404 problems.
func (p *UpstreamProblem) Is(target error) bool {
	return target == ErrNotFound && p.Status == 404
}

// AsUpstreamProblem extracts an *UpstreamProblem from err.
func AsUpstreamProblem(err error) (*UpstreamProblem, bool) {
	var p *UpstreamProblem
	if errors.As(err, &p) {
		return p, true
	}
	return nil, false
}

package domain

import (
	"errors"
	"fmt"
)

// ProviderError is a provider failure classified as retryable (timeouts, 5xx, throttling: fail over and retry
// later) or permanent (invalid address, rejected content, opted-out number: do not retry).
type ProviderError struct {
	Provider  string
	Retryable bool
	Status    int
	Code      string
	Err       error
}

func (e *ProviderError) Error() string {
	kind := "permanent"
	if e.Retryable {
		kind = "retryable"
	}
	s := fmt.Sprintf("%s: %s failure", e.Provider, kind)
	if e.Status != 0 {
		s += fmt.Sprintf(" (status %d)", e.Status)
	}
	if e.Code != "" {
		s += " code=" + e.Code
	}
	if e.Err != nil {
		s += ": " + e.Err.Error()
	}
	return s
}

func (e *ProviderError) Unwrap() error { return e.Err }

// Failure classifies a provider failure; Wrap attaches the underlying cause and yields a *ProviderError.
type Failure struct {
	Provider  string
	Retryable bool
	Status    int
	Code      string
}

// Wrap returns a *ProviderError carrying f's classification and cause err (which may be nil).
func (f Failure) Wrap(err error) error {
	return &ProviderError{Provider: f.Provider, Retryable: f.Retryable, Status: f.Status, Code: f.Code, Err: err}
}

// Retry classifies a retryable failure (fail over now, re-queue later).
func Retry(provider string, status int, code string) Failure {
	return Failure{Provider: provider, Retryable: true, Status: status, Code: code}
}

// Permanent classifies a failure no provider can fix (bad address, rejected content).
func Permanent(provider string, status int, code string) Failure {
	return Failure{Provider: provider, Status: status, Code: code}
}

// Transport classifies a transport-level failure (dial, TLS, timeout): always retryable.
func Transport(provider string) Failure { return Retry(provider, 0, "transport") }

// RetryableError builds a retryable ProviderError.
func RetryableError(provider string, status int, code string, err error) error {
	return Retry(provider, status, code).Wrap(err)
}

// PermanentError builds a permanent ProviderError.
func PermanentError(provider string, status int, code string, err error) error {
	return Permanent(provider, status, code).Wrap(err)
}

// ErrNoProvider is returned when every provider for a route is unavailable (breakers open or none configured).
var ErrNoProvider = errors.New("no provider available")

// IsRetryable reports whether err (possibly an errors.Join tree of provider failures) is worth retrying:
// true unless every leaf is a permanent ProviderError.
func IsRetryable(err error) bool {
	if err == nil {
		return false
	}
	leaves := flatten(err)
	for _, l := range leaves {
		var pe *ProviderError
		if !errors.As(l, &pe) {
			return true // timeouts, network errors, ErrNoProvider
		}
		if pe.Retryable {
			return true
		}
	}
	return false
}

// HTTPRetryable classifies an HTTP status: 408, 425, 429 and 5xx are retryable.
func HTTPRetryable(status int) bool {
	return status == 408 || status == 425 || status == 429 || status >= 500
}

func flatten(err error) []error {
	type multi interface{ Unwrap() []error }
	var m multi
	if errors.As(err, &m) {
		var out []error
		for _, e := range m.Unwrap() {
			out = append(out, flatten(e)...)
		}
		return out
	}
	return []error{err}
}

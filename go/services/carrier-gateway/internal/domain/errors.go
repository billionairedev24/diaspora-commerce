package domain

import (
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
)

// Error taxonomy. Adapters wrap every carrier failure in a *CarrierError whose Unwrap returns one of these, so
// callers can tell retryable outages from permanent rejections with errors.Is.
var (
	// ErrInvalid is a request we refuse before calling any carrier.
	ErrInvalid = errors.New("invalid request")
	// ErrCarrierUnavailable is retryable: timeouts, 429 and 5xx after the adapter's own retries.
	ErrCarrierUnavailable = errors.New("carrier temporarily unavailable")
	// ErrCarrierRejected is permanent: the carrier refused the request (bad address, unknown service…).
	ErrCarrierRejected = errors.New("carrier rejected the request")
	// ErrCarrierAuth is permanent until credentials are fixed: 401/403 after a token refresh.
	ErrCarrierAuth = errors.New("carrier authentication failed")
	// ErrNotFound is returned for unknown labels, tracking numbers or locations.
	ErrNotFound = errors.New("not found")
	// ErrConflict is returned for state conflicts (void after acceptance, idempotency key reuse).
	ErrConflict = errors.New("conflict")
	// ErrUnsupported is returned when a carrier does not offer an operation.
	ErrUnsupported = errors.New("operation not supported by carrier")
	// ErrSignature is returned when a webhook fails verification.
	ErrSignature = errors.New("webhook verification failed")
	// ErrCarrierDisabled is returned when a carrier is not enabled or has no account for the origin.
	ErrCarrierDisabled = errors.New("carrier not enabled")
)

// CarrierError is a mapped carrier failure.
type CarrierError struct {
	Carrier CarrierCode
	Status  int    // HTTP status, 0 for transport errors
	Code    string // carrier error code, when given
	Message string
	Kind    error // one of the sentinel errors above
}

func (e *CarrierError) Error() string {
	var b strings.Builder
	b.WriteString(string(e.Carrier))
	if e.Status != 0 {
		b.WriteString(" status " + strconv.Itoa(e.Status))
	}
	if e.Code != "" {
		b.WriteString(" [" + e.Code + "]")
	}
	if e.Message != "" {
		b.WriteString(": " + e.Message)
	}
	return b.String()
}

// Unwrap exposes the sentinel kind.
func (e *CarrierError) Unwrap() error { return e.Kind }

// Retryable reports whether err is worth retrying later.
func Retryable(err error) bool { return errors.Is(err, ErrCarrierUnavailable) }

// ParseMinor converts a decimal money string ("12.34") to minor units.
func ParseMinor(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, nil
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, fmt.Errorf("parse amount %q: %w", s, err)
	}
	return RoundMinor(f * 100), nil
}

// MinorFromFloat converts a decimal amount to minor units.
func MinorFromFloat(f float64) int64 { return RoundMinor(f * 100) }

// RoundMinor rounds half away from zero.
func RoundMinor(f float64) int64 { return int64(math.Round(f)) }

// FormatMajor renders minor units as a decimal string ("12.34").
func FormatMajor(minor int64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return fmt.Sprintf("%s%d.%02d", sign, minor/100, minor%100)
}

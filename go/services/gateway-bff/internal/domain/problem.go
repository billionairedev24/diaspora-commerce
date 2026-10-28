// Package domain holds the BFF's screen shapes (contracts/openapi/gateway-bff.yaml) and the rules that build them:
// stock caps, delivery estimates, problem codes and the session model. It has no infrastructure imports.
package domain

import (
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
)

// Stable problem codes (contract: components.schemas.Problem.code).
const (
	CodeValidationFailed    = "VALIDATION_FAILED"
	CodeMalformedJSON       = "MALFORMED_JSON"
	CodeUnauthenticated     = "UNAUTHENTICATED"
	CodeForbidden           = "FORBIDDEN"
	CodeNotFound            = "NOT_FOUND"
	CodeProductNotFound     = "PRODUCT_NOT_FOUND"
	CodeOfferNotFound       = "OFFER_NOT_FOUND"
	CodeLineNotFound        = "LINE_NOT_FOUND"
	CodeSavedNotFound       = "SAVED_NOT_FOUND"
	CodeJobNotFound         = "JOB_NOT_FOUND"
	CodeAddressNotFound     = "ADDRESS_NOT_FOUND"
	CodePaymentNotFound     = "PAYMENT_METHOD_NOT_FOUND"
	CodeOrderNotFound       = "ORDER_NOT_FOUND"
	CodeStockLimit          = "STOCK_LIMIT"
	CodeOutOfStock          = "OUT_OF_STOCK"
	CodeInvalidCredentials  = "INVALID_CREDENTIALS" //nolint:gosec // a problem code, not a credential
	CodeInvalidCode         = "INVALID_CODE"
	CodeChallengeExpired    = "CHALLENGE_EXPIRED"
	CodeEmailTaken          = "EMAIL_TAKEN"
	CodeWeakPassword        = "WEAK_PASSWORD"
	CodeProviderDisabled    = "PROVIDER_DISABLED"
	CodeZipNotServiceable   = "ZIP_NOT_SERVICEABLE"
	CodePaymentDeclined     = "PAYMENT_DECLINED"
	CodeIdempotencyConflict = "IDEMPOTENCY_CONFLICT"
	// CodeCheckoutInProgress: the customer's previous checkout is still being closed; retry with the same key.
	CodeCheckoutInProgress = "CHECKOUT_IN_PROGRESS"
	// CodePreviousCheckoutPaid: the customer's previous open checkout was paid; it is being placed.
	CodePreviousCheckoutPaid = "PREVIOUS_CHECKOUT_PAID"
	CodeStockChanged         = "STOCK_CHANGED"
	CodeConflict             = "CONFLICT"
	// CodeMaintenance is the 503 sent to customers and sellers while the platform is in maintenance.
	CodeMaintenance = "MAINTENANCE"
	// CodeAlreadyDone (409): what the action would do already happened (e.g. an application already approved); the
	// detail says by whom and when. Clients show it as information and refresh the record.
	CodeAlreadyDone = "ALREADY_DONE"
	// CodeVersionConflict: an If-Match edit named an older version (412); reload and retry.
	CodeVersionConflict     = "VERSION_CONFLICT"
	CodeRateLimited         = "RATE_LIMITED"
	CodeInvalidQuery        = "INVALID_QUERY"
	CodePayloadTooLarge     = "PAYLOAD_TOO_LARGE"
	CodeUpstreamUnavailable = "UPSTREAM_UNAVAILABLE"
	CodeUpstreamError       = "UPSTREAM_ERROR"
	CodeInternal            = "INTERNAL"
	CodePhoneMissing        = "PHONE_MISSING"
	// AfroTransact Delivery from the seller's store (S255): checkout (shopper) and pickup locations (seller).
	CodeDeliveryOutOfRange        = "DELIVERY_OUT_OF_RANGE"
	CodeDeliveryUnavailable       = "DELIVERY_UNAVAILABLE"
	CodeDeliveryPickupUnavailable = "DELIVERY_PICKUP_UNAVAILABLE"
	CodeDeliveryAddressNotLocated = "DELIVERY_ADDRESS_NOT_LOCATED"
	CodePickupOrgNotApproved      = "PICKUP_ORG_NOT_APPROVED"
	CodePickupInvalidHours        = "PICKUP_INVALID_HOURS"
	CodePickupNotGeocoded         = "PICKUP_NOT_GEOCODED"
	CodePickupOutsideDeliveryArea = "PICKUP_OUTSIDE_DELIVERY_AREA"
)

// Problem is an RFC 9457 problem the HTTP adapter renders as application/problem+json.
type Problem struct {
	Status int
	Code   string
	Title  string
	Detail string
	// Errors maps request properties to messages (validation problems).
	Errors map[string]string
	// Extra holds extension members, e.g. available/inCart/requested/maxQuantity on stock problems.
	Extra map[string]any
	// RetryAfter (seconds) is sent as the Retry-After header when > 0.
	RetryAfter int
	// Upstream names the service that failed (logs/metrics only; never rendered).
	Upstream string
	cause    error
}

func (p *Problem) Error() string {
	if p.cause != nil {
		return p.Code + ": " + p.Detail + ": " + p.cause.Error()
	}
	return p.Code + ": " + p.Detail
}

// Unwrap exposes the underlying cause.
func (p *Problem) Unwrap() error { return p.cause }

// WithCause records the underlying error (for logs) without changing what clients see.
func (p *Problem) WithCause(err error) *Problem {
	c := *p
	c.cause = err
	return &c
}

// NewProblem builds a problem with a title derived from the status when empty.
func NewProblem(status int, code, title, detail string) *Problem {
	if title == "" {
		title = http.StatusText(status)
	}
	return &Problem{Status: status, Code: code, Title: title, Detail: detail}
}

// AsProblem extracts a *Problem from err.
func AsProblem(err error) (*Problem, bool) {
	var p *Problem
	if errors.As(err, &p) {
		return p, true
	}
	return nil, false
}

// ValidationProblem is a 422 with per-field messages.
func ValidationProblem(fields map[string]string) *Problem {
	p := NewProblem(http.StatusUnprocessableEntity, CodeValidationFailed, "Validation failed", "One or more fields are invalid.")
	p.Errors = fields
	return p
}

// FieldProblem is a 422 for a single field with a specific code (ZIP_NOT_SERVICEABLE, INVALID_CODE…).
func FieldProblem(code, title, field, message string) *Problem {
	p := NewProblem(http.StatusUnprocessableEntity, code, title, message)
	p.Errors = map[string]string{field: message}
	return p
}

// ErrUpstreamUnavailable marks an upstream that is unreachable, timing out, failing (5xx), behind an open circuit
// breaker, or that does not serve the route yet (404/405 without a problem code — a service still being built).
var ErrUpstreamUnavailable = errors.New("upstream unavailable")

// UpstreamUnavailable is the 503 returned when a dependency cannot answer.
func UpstreamUnavailable(upstream string) *Problem {
	p := NewProblem(http.StatusServiceUnavailable, CodeUpstreamUnavailable, "Service unavailable",
		"This part of AfroTransact is temporarily unavailable. Please try again shortly.")
	p.Upstream = upstream
	p.RetryAfter = 5
	return p
}

// Unauthenticated is the 401 for account endpoints without a signed-in customer.
func Unauthenticated() *Problem {
	return NewProblem(http.StatusUnauthorized, CodeUnauthenticated, "Sign in required", "Sign in to continue.")
}

// ProblemTypeURI is the RFC 9457 type for a code under the platform's single problem-type base (go/pkg/problem),
// e.g. https://developers.afrotransact.com/problems/bff.upstream-unavailable.
func ProblemTypeURI(code string) string {
	return problem.TypeURI("bff." + strings.ReplaceAll(strings.ToLower(code), "_", "-"))
}

// UpstreamError wraps an unexpected upstream answer (4xx we cannot map) as a 502.
func UpstreamError(upstream string, status int) *Problem {
	p := NewProblem(http.StatusBadGateway, CodeUpstreamError, "Bad gateway",
		fmt.Sprintf("Unexpected answer from %s (%d).", upstream, status))
	p.Upstream = upstream
	return p
}

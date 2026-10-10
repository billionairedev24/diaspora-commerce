package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// writeProblem renders err as RFC 9457 application/problem+json. Unknown errors become 500 without internals;
// 5xx causes are logged (never tokens: upstream errors carry method and path only).
func writeProblem(w http.ResponseWriter, r *http.Request, log *slog.Logger, err error) {
	p, ok := domain.AsProblem(err)
	if !ok {
		if errors.Is(err, context.Canceled) {
			p = domain.NewProblem(499, domain.CodeInternal, "Client closed request", "The request was cancelled.")
		} else {
			p = domain.NewProblem(http.StatusInternalServerError, domain.CodeInternal, "Internal error", "Something went wrong. Please try again.")
		}
	}
	if p.Status >= 500 {
		log.WarnContext(r.Context(), "request failed", "code", p.Code, "upstream", p.Upstream, "error", err.Error(), "path", r.URL.Path)
	}
	doc := map[string]any{
		"type":     domain.ProblemTypeURI(p.Code),
		"title":    p.Title,
		"status":   p.Status,
		"detail":   p.Detail,
		"instance": r.URL.Path,
		"code":     p.Code,
	}
	for k, v := range p.Extra {
		doc[k] = v
	}
	if len(p.Errors) > 0 {
		doc["errors"] = p.Errors
	}
	if p.RetryAfter > 0 {
		w.Header().Set("Retry-After", strconv.Itoa(p.RetryAfter))
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(p.Status)
	_ = json.NewEncoder(w).Encode(doc)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	if w.Header().Get("Cache-Control") == "" {
		w.Header().Set("Cache-Control", "no-store")
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// decode reads a JSON body (size-limited by the body-limit middleware). Empty bodies decode as {} when allowEmpty.
func decode(r *http.Request, v any, allowEmpty bool) error {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return domain.NewProblem(http.StatusRequestEntityTooLarge, domain.CodePayloadTooLarge, "Payload too large", "The request body is too large.")
		}
		return domain.NewProblem(http.StatusBadRequest, domain.CodeMalformedJSON, "Malformed request", "The request body could not be read.")
	}
	if len(strings.TrimSpace(string(body))) == 0 {
		if allowEmpty {
			return nil
		}
		return domain.ValidationProblem(map[string]string{"_": "A JSON body is required."})
	}
	if err := json.Unmarshal(body, v); err != nil {
		var ute *json.UnmarshalTypeError
		if errors.As(err, &ute) && ute.Field != "" {
			return domain.ValidationProblem(map[string]string{ute.Field: "Invalid value"})
		}
		return domain.NewProblem(http.StatusBadRequest, domain.CodeMalformedJSON, "Malformed request", "The request body is not valid JSON.")
	}
	return nil
}

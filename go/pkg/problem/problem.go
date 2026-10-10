// Package problem writes RFC 9457 problem+json responses.
package problem

import (
	"encoding/json"
	"errors"
	"net/http"
)

const typeBase = "https://developers.afrotransact.com/problems/"

// Details is an RFC 9457 problem document.
type Details struct {
	Type     string            `json:"type"`
	Title    string            `json:"title"`
	Status   int               `json:"status"`
	Detail   string            `json:"detail,omitempty"`
	Instance string            `json:"instance,omitempty"`
	Code     string            `json:"code,omitempty"`
	Errors   map[string]string `json:"errors,omitempty"`
}

// Error is a typed error carrying an HTTP status and stable code; adapters map domain errors to it.
type Error struct {
	Status int
	Code   string
	Detail string
	Fields map[string]string
}

func (e *Error) Error() string { return e.Code + ": " + e.Detail }

// New creates a problem Error.
func New(status int, code, detail string) *Error {
	return &Error{Status: status, Code: code, Detail: detail}
}

// Write renders err as problem+json. Non-problem errors become 500 without leaking internals.
func Write(w http.ResponseWriter, r *http.Request, err error) {
	var pe *Error
	if !errors.As(err, &pe) {
		pe = &Error{Status: http.StatusInternalServerError, Code: "internal", Detail: "Unexpected error"}
	}
	d := Details{
		Type:     typeBase + pe.Code,
		Title:    http.StatusText(pe.Status),
		Status:   pe.Status,
		Detail:   pe.Detail,
		Instance: r.URL.Path,
		Code:     pe.Code,
		Errors:   pe.Fields,
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(pe.Status)
	_ = json.NewEncoder(w).Encode(d)
}

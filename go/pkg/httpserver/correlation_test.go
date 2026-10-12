package httpserver

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCorrelationMiddlewareContextAndResponse(t *testing.T) {
	var got string
	h := correlation(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = CorrelationFromContext(r.Context())
	}))
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set(CorrelationHeader, "abc-123")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if got != "abc-123" {
		t.Fatalf("context id = %q, want abc-123", got)
	}
	if rec.Header().Get(CorrelationHeader) != "abc-123" {
		t.Fatalf("response header = %q", rec.Header().Get(CorrelationHeader))
	}
}

func TestPropagateCorrelation(t *testing.T) {
	h := http.Header{}
	PropagateCorrelation(context.Background(), h)
	if h.Get(CorrelationHeader) != "" {
		t.Fatal("expected no header without ctx id")
	}
	ctx := WithCorrelation(context.Background(), "trace-me")
	PropagateCorrelation(ctx, h)
	if h.Get(CorrelationHeader) != "trace-me" {
		t.Fatalf("got %q", h.Get(CorrelationHeader))
	}
	h.Set(CorrelationHeader, "existing")
	PropagateCorrelation(ctx, h)
	if h.Get(CorrelationHeader) != "existing" {
		t.Fatal("must not overwrite existing header")
	}
}

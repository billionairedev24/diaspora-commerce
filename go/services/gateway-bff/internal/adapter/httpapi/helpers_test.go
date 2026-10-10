package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// browser is a cookie-keeping client that sends the storefront Origin on unsafe methods (like the /api/bff proxy).
type browser struct {
	t       *testing.T
	h       http.Handler
	cookie  string
	origin  string
	headers map[string]string
	// onResponse lets the conformance test validate every exchange.
	onResponse func(req *http.Request, rec *httptest.ResponseRecorder)
}

func newBrowser(t *testing.T, h http.Handler) *browser {
	return &browser{t: t, h: h, origin: testkit.WebOrigin, headers: map[string]string{}}
}

type resp struct {
	status int
	header http.Header
	body   []byte
}

func (r resp) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(r.body, &m); err != nil {
		t.Fatalf("decode %q: %v", r.body, err)
	}
	return m
}

func (b *browser) do(method, path string, body any) resp {
	b.t.Helper()
	var rd io.Reader
	if body != nil {
		switch v := body.(type) {
		case string:
			rd = strings.NewReader(v)
		default:
			raw, _ := json.Marshal(v)
			rd = bytes.NewReader(raw)
		}
	}
	req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if b.cookie != "" {
		req.Header.Set("Cookie", "at_session="+b.cookie)
	}
	if method != http.MethodGet && b.origin != "" {
		req.Header.Set("Origin", b.origin)
	}
	for k, v := range b.headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	b.h.ServeHTTP(rec, req)
	if b.onResponse != nil {
		b.onResponse(req, rec)
	}
	for _, c := range rec.Result().Cookies() {
		if c.Name == "at_session" {
			if c.MaxAge < 0 {
				b.cookie = ""
			} else {
				b.cookie = c.Value
			}
		}
	}
	return resp{status: rec.Code, header: rec.Header(), body: rec.Body.Bytes()}
}

func mustStatus(t *testing.T, r resp, want int) {
	t.Helper()
	if r.status != want {
		t.Fatalf("status = %d, want %d: %s", r.status, want, r.body)
	}
}

func problemCode(t *testing.T, r resp) string {
	t.Helper()
	if ct := r.header.Get("Content-Type"); ct != "application/problem+json" {
		t.Fatalf("content-type = %q, want problem+json (%s)", ct, r.body)
	}
	c, _ := r.json(t)["code"].(string)
	return c
}

func num(m map[string]any, k string) int {
	f, _ := m[k].(float64)
	return int(f)
}

func lines(t *testing.T, cart map[string]any) []map[string]any {
	t.Helper()
	var out []map[string]any
	for _, p := range cart["packages"].([]any) {
		for _, l := range p.(map[string]any)["lines"].([]any) {
			out = append(out, l.(map[string]any))
		}
	}
	return out
}

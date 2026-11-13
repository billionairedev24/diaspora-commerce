package tenanttest

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Request is one HTTP request of a case.
type Request struct {
	Method string
	Path   string
	Body   string
	Header map[string]string
}

// Get is a GET request.
func Get(path string) Request { return Request{Method: http.MethodGet, Path: path} }

// Send is a request with a JSON body.
func Send(method, path, body string) Request {
	return Request{Method: method, Path: path, Body: body, Header: map[string]string{"Content-Type": "application/json"}}
}

// WithHeader returns the request with one more header.
func (r Request) WithHeader(k, v string) Request {
	h := map[string]string{}
	for hk, hv := range r.Header {
		h[hk] = hv
	}
	h[k] = v
	r.Header = h
	return r
}

// Result is what the handler answered.
type Result struct {
	Status int
	Body   string
	Header http.Header
}

// Do runs one request as the principal against the handler.
func Do(h http.Handler, as Principal, r Request) Result {
	var body io.Reader
	if r.Body != "" {
		body = strings.NewReader(r.Body)
	}
	req := httptest.NewRequestWithContext(context.Background(), r.Method, r.Path, body)
	for k, v := range r.Header {
		req.Header.Set(k, v)
	}
	if a := as.Authorization(); a != "" {
		req.Header.Set("Authorization", a)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	res := rec.Result()
	defer func() { _ = res.Body.Close() }()
	b, _ := io.ReadAll(res.Body)
	return Result{Status: res.StatusCode, Body: string(b), Header: res.Header}
}

// Expect asserts on a result.
type Expect func(t testing.TB, res Result)

// Case is one request by one principal and what must come back.
type Case struct {
	Name   string
	As     Principal
	Req    Request
	Expect Expect
}

// Run fires the cases in order as subtests (order matters when a case creates what a later one attacks).
func Run(t *testing.T, h http.Handler, cases []Case) {
	t.Helper()
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			t.Helper()
			res := Do(h, c.As, c.Req)
			if c.Expect != nil {
				c.Expect(t, res)
			}
		})
	}
}

// Status expects one of the status codes.
func Status(codes ...int) Expect {
	return func(t testing.TB, res Result) {
		t.Helper()
		for _, c := range codes {
			if res.Status == c {
				return
			}
		}
		t.Fatalf("status %d, want one of %v; body %s", res.Status, codes, truncate(res.Body))
	}
}

// Denied expects the request to be refused: 401, 403 or 404 (a cross-tenant read must not succeed, and answering 404
// instead of 403 is fine because it does not confirm the resource exists).
func Denied() Expect {
	return Status(http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound)
}

// Allowed expects a 2xx answer.
func Allowed() Expect {
	return func(t testing.TB, res Result) {
		t.Helper()
		if res.Status < 200 || res.Status > 299 {
			t.Fatalf("status %d, want 2xx; body %s", res.Status, truncate(res.Body))
		}
	}
}

// Contains expects every marker in the body.
func Contains(markers ...string) Expect {
	return func(t testing.TB, res Result) {
		t.Helper()
		for _, m := range markers {
			if !strings.Contains(res.Body, m) {
				t.Fatalf("body lacks %q: %s", m, truncate(res.Body))
			}
		}
	}
}

// Leaks expects none of the markers (another tenant's ids, names, secrets) anywhere in the body.
func Leaks(markers ...string) Expect {
	return func(t testing.TB, res Result) {
		t.Helper()
		for _, m := range markers {
			if m != "" && strings.Contains(res.Body, m) {
				t.Fatalf("body leaks %q (status %d): %s", m, res.Status, truncate(res.Body))
			}
		}
	}
}

// All combines expectations.
func All(es ...Expect) Expect {
	return func(t testing.TB, res Result) {
		t.Helper()
		for _, e := range es {
			e(t, res)
		}
	}
}

// CrossTenant fires every request as every attacker and requires a refusal that leaks none of the markers. It is the
// "tenant A's token against tenant B's resource" table: the requests name the victim's resources.
func CrossTenant(t *testing.T, h http.Handler, attackers []Principal, reqs []Request, markers ...string) {
	t.Helper()
	for _, a := range attackers {
		for _, r := range reqs {
			t.Run(a.String()+" "+r.Method+" "+r.Path, func(t *testing.T) {
				t.Helper()
				res := Do(h, a, r)
				All(Denied(), Leaks(markers...))(t, res)
			})
		}
	}
}

// NoLeak fires every request as the principal and requires that no body leaks any marker (list and search endpoints
// answer 200 with only the caller's own rows).
func NoLeak(t *testing.T, h http.Handler, as Principal, reqs []Request, markers ...string) {
	t.Helper()
	for _, r := range reqs {
		t.Run(as.String()+" "+r.Method+" "+r.Path, func(t *testing.T) {
			t.Helper()
			Leaks(markers...)(t, Do(h, as, r))
		})
	}
}

func truncate(s string) string {
	if len(s) > 300 {
		return s[:300] + "..."
	}
	return s
}

package mediafetch_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/mediafetch"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// reply is a scripted response.
type reply struct {
	status        int
	contentType   string
	body          []byte
	declareLength bool
	location      string
}

// fakeTransport answers requests from memory (no network). Each URL maps to a reply.
type fakeTransport struct {
	responses map[string]reply
	requests  []*http.Request
}

func (f *fakeTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	f.requests = append(f.requests, r)
	rp, ok := f.responses[r.URL.String()]
	if !ok {
		return nil, errors.New("unexpected request to " + r.URL.String())
	}
	h := http.Header{}
	if rp.contentType != "" {
		h.Set("Content-Type", rp.contentType)
	}
	if rp.location != "" {
		h.Set("Location", rp.location)
	}
	length := int64(-1)
	if rp.declareLength {
		length = int64(len(rp.body))
		h.Set("Content-Length", strconv.Itoa(len(rp.body)))
	}
	return &http.Response{StatusCode: rp.status, Header: h, Body: io.NopCloser(bytes.NewReader(rp.body)),
		ContentLength: length, Request: r}, nil
}

func body(status int, contentType string, b []byte, declareLength bool) reply {
	return reply{status: status, contentType: contentType, body: b, declareLength: declareLength}
}

func redirect(to string) reply { return reply{status: http.StatusFound, location: to} }

func newFetcher(t *testing.T, ft *fakeTransport) interface {
	Fetch(context.Context, domain.AttachmentRef) (domain.Attachment, error)
} {
	t.Helper()
	f, err := mediafetch.New(mediafetch.Config{MediaBaseURL: "http://media:8080", AllowedHosts: []string{"files.example",
		"*.s3.example.com", " "}, MaxBytes: 64}, &http.Client{Transport: ft})
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func TestFetchAllowedHosts(t *testing.T) {
	pdf := []byte("%PDF-1.7 small")
	ft := &fakeTransport{responses: map[string]reply{
		"https://files.example/po/1.pdf?sig=abc":         body(200, "application/octet-stream", pdf, true),
		"https://bucket.s3.example.com/po/2.pdf?X-Sig=1": body(200, "application/pdf; charset=binary", pdf, false),
		"http://media:8080/v1/assets/3/content":          body(200, "", pdf, true),
	}}
	f := newFetcher(t, ft)
	ctx := context.Background()
	a, err := f.Fetch(ctx, domain.AttachmentRef{FileName: "PO-1.pdf", ContentType: "application/pdf", URL: "https://files.example/po/1.pdf?sig=abc"})
	if err != nil || !bytes.Equal(a.Content, pdf) || a.ContentType != "application/pdf" || a.FileName != "PO-1.pdf" {
		t.Fatalf("%+v %v", a, err)
	}
	if ft.requests[0].Header.Get("Authorization") != "" {
		t.Error("signed links are fetched without credentials")
	}
	// Wildcard subdomain, served type when none is declared.
	if a, err := f.Fetch(ctx, domain.AttachmentRef{FileName: "x/../PO-2.pdf", URL: "https://bucket.s3.example.com/po/2.pdf?X-Sig=1"}); err != nil ||
		a.ContentType != "application/pdf" || a.FileName != "PO-2.pdf" {
		t.Fatalf("%+v %v", a, err)
	}
	// The media base URL's host:port is always allowed; no type at all → octet-stream.
	if a, err := f.Fetch(ctx, domain.AttachmentRef{FileName: "f", URL: "http://media:8080/v1/assets/3/content"}); err != nil ||
		a.ContentType != "application/octet-stream" {
		t.Fatalf("%+v %v", a, err)
	}
}

func TestFetchRejects(t *testing.T) {
	big := bytes.Repeat([]byte("x"), 65)
	ft := &fakeTransport{responses: map[string]reply{
		"https://files.example/big-declared":   body(200, "application/pdf", big, true),
		"https://files.example/big-streamed":   body(200, "application/pdf", big, false),
		"https://files.example/expired":        body(403, "application/xml", []byte("<Error>expired</Error>"), true),
		"https://files.example/redirect-out":   redirect("https://attacker.example/steal"),
		"https://files.example/redirect-in":    redirect("https://files.example/ok"),
		"https://files.example/ok":             body(200, "application/pdf", []byte("%PDF-"), true),
		"https://files.example/redirect-meta":  redirect("http://169.254.169.254/latest/meta-data"),
		"https://media:9999/other-port":        body(200, "application/pdf", []byte("%PDF-"), true),
		"https://evil.s3.example.com.attacker": body(200, "application/pdf", []byte("%PDF-"), true),
	}}
	f := newFetcher(t, ft)
	ctx := context.Background()
	for url, rejected := range map[string]bool{
		"https://attacker.example/po.pdf":      true,
		"https://files.example/big-declared":   true,
		"https://files.example/big-streamed":   true,
		"https://files.example/redirect-out":   true,
		"https://files.example/redirect-meta":  true,
		"https://media:9999/other-port":        true, // the media entry is host:port specific
		"https://evil.s3.example.com.attacker": true,
		"https://s3.example.com/po.pdf":        true, // "*." needs a subdomain
		"ftp://files.example/po.pdf":           true,
		"https://user:pw@files.example/po.pdf": true,
		"/relative/po.pdf":                     true,
		"https://files.example/expired":        false, // not allowed to send, but a fetch failure, not a rejection
	} {
		_, err := f.Fetch(ctx, domain.AttachmentRef{FileName: "po.pdf", URL: url})
		if err == nil {
			t.Errorf("%s: fetched", url)
			continue
		}
		if errors.Is(err, domain.ErrAttachmentRejected) != rejected {
			t.Errorf("%s: rejected=%v: %v", url, !rejected, err)
		}
	}
	if _, err := f.Fetch(ctx, domain.AttachmentRef{FileName: "po.pdf", URL: "https://files.example/redirect-in"}); err != nil {
		t.Errorf("redirect within the allow-list: %v", err)
	}
	for _, r := range ft.requests {
		if r.URL.Host == "attacker.example" || r.URL.Host == "169.254.169.254" || strings.HasSuffix(r.URL.Host, ".attacker") {
			t.Errorf("request reached %s", r.URL)
		}
	}
}

func TestNeedsAnAllowList(t *testing.T) {
	if _, err := mediafetch.New(mediafetch.Config{}, http.DefaultClient); err == nil {
		t.Fatal("no hosts must fail")
	}
	if _, err := mediafetch.New(mediafetch.Config{MediaBaseURL: "media"}, http.DefaultClient); err == nil {
		t.Fatal("relative media base URL must fail")
	}
}

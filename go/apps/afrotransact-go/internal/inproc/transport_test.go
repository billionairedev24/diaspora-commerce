package inproc_test

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/inproc"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
)

func TestServesThroughTheHandlerWithMethodHeadersBodyAndQuery(t *testing.T) {
	tr := inproc.New(nil)
	tr.Register("cart", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		w.Header().Set("X-Seen", r.Header.Get("Authorization")+"|"+r.URL.RawQuery+"|"+r.Host+"|"+module.NameFrom(r.Context()))
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(r.Method + ":" + string(b)))
	}))
	req, _ := http.NewRequest(http.MethodPost, inproc.URL("cart")+"/v1/x?a=1", strings.NewReader("body"))
	req.Header.Set("Authorization", "Bearer t")
	resp, err := (&http.Client{Transport: tr}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 201 || string(b) != "POST:body" || resp.Header.Get("X-Seen") != "Bearer t|a=1|cart.inproc|cart" {
		t.Errorf("%d %q %q", resp.StatusCode, b, resp.Header.Get("X-Seen"))
	}
	if resp.ContentLength != int64(len(b)) {
		t.Errorf("ContentLength = %d", resp.ContentLength)
	}
}

func TestOtherHostsGoToTheFallback(t *testing.T) {
	real := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "network") }))
	defer real.Close()
	tr := inproc.New(nil)
	resp, err := (&http.Client{Transport: tr}).Get(real.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if b, _ := io.ReadAll(resp.Body); string(b) != "network" {
		t.Errorf("fallback body = %q", b)
	}
}

func TestMissingModuleAndCancelledContext(t *testing.T) {
	tr := inproc.New(nil)
	if _, err := (&http.Client{Transport: tr}).Get(inproc.URL("geo") + "/x"); !errors.Is(err, inproc.ErrModuleDown) {
		t.Errorf("err = %v", err)
	}
	tr.Register("geo", http.NotFoundHandler())
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, inproc.URL("geo")+"/x", nil)
	if _, err := (&http.Client{Transport: tr}).Do(req); !errors.Is(err, context.Canceled) {
		t.Errorf("err = %v", err)
	}
}

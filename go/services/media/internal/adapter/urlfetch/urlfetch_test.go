package urlfetch_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/urlfetch"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

func server(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/a.jpg", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "image/jpeg; charset=binary")
		_, _ = w.Write([]byte{0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3})
	})
	mux.HandleFunc("/big.jpg", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write(make([]byte, 2048))
	})
	mux.HandleFunc("/loop", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/loop", http.StatusFound)
	})
	mux.HandleFunc("/gone", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNotFound) })
	s := httptest.NewServer(mux)
	t.Cleanup(s.Close)
	return s
}

func TestPrivateAddressesAndPlainHTTPAreRefused(t *testing.T) {
	s := server(t)
	f := urlfetch.New(urlfetch.Options{})
	if _, err := f.Fetch(context.Background(), s.URL+"/a.jpg", 1024); !errors.Is(err, domain.ErrRejected) ||
		!strings.Contains(err.Error(), "https") {
		t.Fatalf("http is refused: %v", err)
	}
	f = urlfetch.New(urlfetch.Options{AllowHTTP: true})
	if _, err := f.Fetch(context.Background(), s.URL+"/a.jpg", 1024); !errors.Is(err, domain.ErrRejected) ||
		!strings.Contains(err.Error(), "private or reserved") {
		t.Fatalf("loopback is refused by the SSRF guard: %v", err)
	}
	if _, err := f.Fetch(context.Background(), "https://user:pw@example.com/a.jpg", 1024); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("credentials in links are refused: %v", err)
	}
	for _, ip := range []string{"10.0.0.1", "127.0.0.1", "169.254.169.254", "100.64.1.1", "::1", "fd00::1", "192.168.1.1"} {
		if urlfetch.PublicAddress(netip.MustParseAddr(ip)) {
			t.Errorf("%s must not be public", ip)
		}
	}
	if !urlfetch.PublicAddress(netip.MustParseAddr("151.101.1.140")) {
		t.Error("a CDN address is public")
	}
}

func TestFetchesWithinLimits(t *testing.T) {
	s := server(t)
	f := urlfetch.New(urlfetch.Options{AllowHTTP: true, AllowAddress: func(netip.Addr) bool { return true }})
	got, err := f.Fetch(context.Background(), s.URL+"/a.jpg", 1024)
	if err != nil || len(got.Data) != 7 || got.ContentType != "image/jpeg" || got.FileName != "a.jpg" {
		t.Fatalf("fetch = %+v %v", got, err)
	}
	if _, err := f.Fetch(context.Background(), s.URL+"/big.jpg", 1024); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("too large: %v", err)
	}
	if _, err := f.Fetch(context.Background(), s.URL+"/gone", 1024); !errors.Is(err, domain.ErrRejected) ||
		!strings.Contains(err.Error(), "404") {
		t.Fatalf("404: %v", err)
	}
	if _, err := f.Fetch(context.Background(), s.URL+"/loop", 1024); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("redirect loop: %v", err)
	}
}

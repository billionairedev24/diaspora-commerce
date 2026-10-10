package s3store_test

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/objectstore/s3store"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// fakeS3 speaks enough of the S3 REST API (path-style PutObject/GetObject) to exercise the SDK end to end and
// asserts every request is SigV4-signed.
func fakeS3(t *testing.T) *httptest.Server {
	var mu sync.Mutex
	objects := map[string][]byte{}
	types := map[string]string{}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Header.Get("Authorization"), "AWS4-HMAC-SHA256 Credential=AKIATEST/") {
			t.Errorf("unsigned request %s %s", r.Method, r.URL.Path)
		}
		mu.Lock()
		defer mu.Unlock()
		switch r.Method {
		case http.MethodPut:
			b, _ := io.ReadAll(r.Body)
			objects[r.URL.Path], types[r.URL.Path] = b, r.Header.Get("Content-Type")
			w.Header().Set("ETag", `"etag"`)
		case http.MethodGet:
			b, ok := objects[r.URL.Path]
			if !ok {
				w.Header().Set("Content-Type", "application/xml")
				w.WriteHeader(http.StatusNotFound)
				_, _ = w.Write([]byte(`<?xml version="1.0" encoding="UTF-8"?><Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>`))
				return
			}
			w.Header().Set("Content-Type", types[r.URL.Path])
			_, _ = w.Write(b)
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	}))
}

func TestS3Store(t *testing.T) {
	srv := fakeS3(t)
	defer srv.Close()
	s := s3store.New(s3store.Options{Endpoint: srv.URL, Region: "us-east-1", Bucket: "labels", AccessKey: "AKIATEST", SecretKey: "secret", PathStyle: true})
	ctx := context.Background()
	if err := s.Put(ctx, "labels/fedex/2026/10/10/l1.pdf", "application/pdf", []byte("%PDF-1.4")); err != nil {
		t.Fatal(err)
	}
	b, err := s.Get(ctx, "labels/fedex/2026/10/10/l1.pdf")
	if err != nil || string(b) != "%PDF-1.4" {
		t.Fatalf("%q %v", b, err)
	}
	if _, err := s.Get(ctx, "labels/missing.pdf"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("missing key: %v", err)
	}
	u, err := s.SignedURL(ctx, "labels/fedex/2026/10/10/l1.pdf", 5*time.Minute)
	if err != nil || !strings.Contains(u, "/labels/labels/fedex/2026/10/10/l1.pdf") || !strings.Contains(u, "X-Amz-Expires=300") {
		t.Fatalf("presigned %s %v", u, err)
	}
}

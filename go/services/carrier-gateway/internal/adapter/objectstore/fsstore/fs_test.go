package fsstore_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/objectstore/fsstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func TestFilesystemStore(t *testing.T) {
	s := fsstore.New(t.TempDir())
	ctx := context.Background()
	if err := s.Put(ctx, "labels/ups/2026/10/10/a.zpl", "application/x-zpl", []byte("^XA^XZ")); err != nil {
		t.Fatal(err)
	}
	b, err := s.Get(ctx, "labels/ups/2026/10/10/a.zpl")
	if err != nil || string(b) != "^XA^XZ" {
		t.Fatalf("%q %v", b, err)
	}
	if _, err := s.Get(ctx, "labels/missing.pdf"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("missing: %v", err)
	}
	if err := s.Put(ctx, "../escape.pdf", "application/pdf", []byte("x")); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("path traversal must be rejected: %v", err)
	}
	if u, err := s.SignedURL(ctx, "labels/ups/2026/10/10/a.zpl", 0); err != nil || u != "" {
		t.Fatal("filesystem cannot sign URLs")
	}
}

package domain_test

import (
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

func TestCheckDeclared(t *testing.T) {
	ok := []struct {
		p    domain.Purpose
		ct   string
		size int64
	}{
		{domain.PurposeProductImage, "image/jpeg", 2 << 20},
		{domain.PurposeKYCDocument, "application/pdf", 10 << 20},
	}
	for _, c := range ok {
		if err := domain.CheckDeclared(c.p, "f.jpg", c.ct, c.size); err != nil {
			t.Fatalf("%v should pass: %v", c, err)
		}
	}
	bad := []struct {
		p    domain.Purpose
		ct   string
		size int64
	}{
		{domain.PurposeProductImage, "application/pdf", 1000},
		{domain.PurposeProductImage, "image/png", 9 << 20},
		{"selfie", "image/png", 1000},
	}
	for _, c := range bad {
		if err := domain.CheckDeclared(c.p, "f", c.ct, c.size); !errors.Is(err, domain.ErrRejected) {
			t.Fatalf("%v should be rejected, got %v", c, err)
		}
	}
}

func TestJudge(t *testing.T) {
	st, flags := domain.Judge(domain.PurposeProductImage, domain.Inspection{ContentType: "image/jpeg", Bytes: 1 << 20,
		Width: 1600, Height: 1600, BorderBrightness: 250})
	if st != domain.StatusReady || len(flags) != 1 || flags[0] != "eligible as main image" {
		t.Fatalf("white 1600px: %s %v", st, flags)
	}
	st, _ = domain.Judge(domain.PurposeProductImage, domain.Inspection{ContentType: "image/jpeg", Bytes: 1 << 20,
		Width: 1600, Height: 1600, BorderBrightness: 40})
	if st != domain.StatusFlagged {
		t.Fatalf("dark background should be flagged, got %s", st)
	}
	st, _ = domain.Judge(domain.PurposeProductImage, domain.Inspection{ContentType: "text/html", Bytes: 10})
	if st != domain.StatusRejected {
		t.Fatalf("html must be rejected, got %s", st)
	}
	st, _ = domain.Judge(domain.PurposeKYCDocument, domain.Inspection{ContentType: "application/pdf", Bytes: 1 << 20})
	if st != domain.StatusReady {
		t.Fatalf("pdf kyc should be ready, got %s", st)
	}
}

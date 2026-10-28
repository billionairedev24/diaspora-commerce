package domain_test

import (
	"errors"
	"slices"
	"testing"
	"time"

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

func TestDocumentPurposeRules(t *testing.T) {
	ok := []struct {
		p    domain.Purpose
		ct   string
		size int64
	}{
		{domain.PurposePurchaseOrder, domain.ContentTypePDF, 10 << 20},
		{domain.PurposeShippingDocument, domain.ContentTypePDF, 15 << 20},
		{domain.PurposeShippingDocument, "image/jpeg", 1 << 20},
		{domain.PurposeCareerResume, domain.ContentTypePDF, 5 << 20},
		{domain.PurposeExport, domain.ContentTypeCSV, 50 << 20},
		{domain.PurposeExport, domain.ContentTypeXLSX, 1 << 20},
	}
	for _, c := range ok {
		if err := domain.CheckDeclared(c.p, "f", c.ct, c.size); err != nil {
			t.Fatalf("%v should pass: %v", c, err)
		}
	}
	bad := []struct {
		p    domain.Purpose
		ct   string
		size int64
	}{
		{domain.PurposePurchaseOrder, "image/png", 1000},
		{domain.PurposePurchaseOrder, domain.ContentTypePDF, 10<<20 + 1},
		{domain.PurposeCareerResume, domain.ContentTypePDF, 5<<20 + 1},
		{domain.PurposeCareerResume, "application/msword", 1000},
		{domain.PurposeExport, domain.ContentTypePDF, 1000},
		{domain.PurposeExport, domain.ContentTypeCSV, 50<<20 + 1},
	}
	for _, c := range bad {
		if err := domain.CheckDeclared(c.p, "f", c.ct, c.size); !errors.Is(err, domain.ErrRejected) {
			t.Fatalf("%v should be rejected, got %v", c, err)
		}
	}
	for _, p := range []domain.Purpose{domain.PurposePurchaseOrder, domain.PurposeShippingDocument,
		domain.PurposeCareerResume, domain.PurposeExport} {
		r, _ := domain.RulesFor(p)
		if !r.LongLinks || r.Public || !r.AllowsServiceClient("retail-service") || !r.AllowsServiceClient("gateway-bff") ||
			r.AllowsServiceClient("catalog-service") {
			t.Fatalf("%s rules %+v", p, r)
		}
	}
	for _, p := range []domain.Purpose{domain.PurposeProductImage, domain.PurposeStorefront, domain.PurposeProofPhoto,
		domain.PurposeReturnPhoto, domain.PurposeKYCDocument} {
		if r, _ := domain.RulesFor(p); r.LongLinks || r.Restricted() {
			t.Fatalf("%s must keep short links and its access rules: %+v", p, r)
		}
	}
	if got := domain.ServiceClients(); len(got) != 2 || got[0] != "retail-service" || got[1] != "gateway-bff" {
		t.Fatalf("service clients %v", got)
	}
}

func TestLinkPolicy(t *testing.T) {
	lp := domain.LinkPolicy{Default: 5 * time.Minute, MaxLong: 14 * 24 * time.Hour}
	long, _ := domain.RulesFor(domain.PurposePurchaseOrder)
	short, _ := domain.RulesFor(domain.PurposeKYCDocument)
	day := 24 * time.Hour
	cases := []struct {
		name               string
		rules              domain.Rules
		requested, storeMx time.Duration
		want               time.Duration
	}{
		{"default when nothing asked", long, 0, 0, 5 * time.Minute},
		{"14 days for a long-link purpose", long, 14 * day, 0, 14 * day},
		{"capped at the maximum", long, 30 * day, 0, 14 * day},
		{"store limit wins", long, 14 * day, 7 * day, 7 * day},
		{"shorter than the default is fine", long, time.Minute, 0, time.Minute},
		{"ordinary purposes stay at the default", short, 14 * day, 0, 5 * time.Minute},
		{"ordinary purposes may ask for less", short, time.Minute, 0, time.Minute},
	}
	for _, c := range cases {
		if got := lp.TTL(c.rules, c.requested, c.storeMx); got != c.want {
			t.Fatalf("%s: got %s, want %s", c.name, got, c.want)
		}
	}
}

func TestReturnPhotoAcceptsHEICButProofPhotoDoesNot(t *testing.T) {
	ret, err := domain.RulesFor(domain.PurposeReturnPhoto)
	if err != nil {
		t.Fatal(err)
	}
	proof, err := domain.RulesFor(domain.PurposeProofPhoto)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(ret.ContentTypes, "image/heic") || slices.Contains(proof.ContentTypes, "image/heic") {
		t.Fatalf("return-photo %v, proof-photo %v", ret.ContentTypes, proof.ContentTypes)
	}
	if ret.MaxBytes != 10<<20 {
		t.Fatalf("max %d", ret.MaxBytes)
	}
}

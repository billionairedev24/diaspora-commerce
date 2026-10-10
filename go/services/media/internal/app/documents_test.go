package app_test

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

var (
	retailService = auth.Actor{Subject: "retail-service", Type: "SERVICE", Permissions: []string{"media:write"}}
	bffService    = auth.Actor{Subject: "gateway-bff", Type: "SERVICE"}
	otherService  = auth.Actor{Subject: "catalog-service", Type: "SERVICE"}
	staff         = auth.Actor{Subject: "staff-1", Type: "STAFF"}
	sellerMember  = auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_lagos"}
	customer      = auth.Actor{Subject: "c1", Type: "CUSTOMER"}
)

func pdf() []byte { return append([]byte("%PDF-1.7\n"), make([]byte, 2000)...) }

func xlsx(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, name := range []string{"[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/worksheets/sheet1.xml"} {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte("<x/>")); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

type documentsFixture struct {
	svc   app.MediaService
	store *memStore
}

func newDocuments(maxStoreTTL time.Duration, links domain.LinkPolicy) documentsFixture {
	st := &memStore{objects: map[string][]byte{}, maxTTL: maxStoreTTL}
	stores := map[domain.Purpose]port.ObjectStore{}
	for _, p := range domain.Purposes() {
		stores[p] = st
	}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	return documentsFixture{svc: app.NewMediaService(stores, repo, inspect.New(40_000_000), links), store: st}
}

func (f documentsFixture) upload(t *testing.T, actor auth.Actor, p domain.Purpose, name, ct string, body []byte) domain.Asset {
	t.Helper()
	ctx := context.Background()
	a, up, err := f.svc.RequestUpload(ctx, actor, p, name, ct, int64(len(body)))
	if err != nil {
		t.Fatalf("upload %s as %s: %v", p, actor.Subject, err)
	}
	f.store.objects[up.Key] = body
	done, err := f.svc.Complete(ctx, actor, a.ID)
	if err != nil {
		t.Fatalf("complete %s: %v", p, err)
	}
	return done
}

func TestRetailServiceStoresAPurchaseOrderAndGetsAFourteenDayLink(t *testing.T) {
	f := newDocuments(0, domain.LinkPolicy{})
	ctx := context.Background()
	po := f.upload(t, retailService, domain.PurposePurchaseOrder, "PO-1001.pdf", domain.ContentTypePDF, pdf())
	if po.Status != domain.StatusReady || po.OwnerOrg != "user:retail-service" {
		t.Fatalf("purchase order %+v", po)
	}

	before := time.Now().UTC()
	got, err := f.svc.Get(ctx, retailService, po.ID, 14*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if got.URL == "" || got.URLExpiresAt == nil {
		t.Fatalf("expected a signed URL with its expiry: %+v", got)
	}
	if d := got.URLExpiresAt.Sub(before); d < 14*24*time.Hour-time.Minute || d > 14*24*time.Hour+time.Minute {
		t.Fatalf("link should last 14 days, expires in %s", d)
	}
	if last := f.store.signed[len(f.store.signed)-1]; last != 14*24*time.Hour {
		t.Fatalf("store signed for %s", last)
	}

	// Asking for more than the configured maximum is capped, not refused.
	if _, err := f.svc.Get(ctx, retailService, po.ID, 30*24*time.Hour); err != nil {
		t.Fatal(err)
	}
	if last := f.store.signed[len(f.store.signed)-1]; last != domain.DefaultMaxLongLinkTTL {
		t.Fatalf("30 days should be capped at 14, signed for %s", last)
	}
	// Without urlTtlSeconds the link keeps the default lifetime.
	if _, err := f.svc.Get(ctx, staff, po.ID, 0); err != nil {
		t.Fatal(err)
	}
	if last := f.store.signed[len(f.store.signed)-1]; last != domain.DefaultLinkTTL {
		t.Fatalf("default link should last 5 minutes, signed for %s", last)
	}
}

func TestLongLinksAreOnlyForTheDocumentPurposesAndRespectConfigAndStore(t *testing.T) {
	f := newDocuments(0, domain.LinkPolicy{})
	ctx := context.Background()
	kyc := f.upload(t, sellerMember, domain.PurposeKYCDocument, "ein.pdf", domain.ContentTypePDF, pdf())
	got, err := f.svc.Get(ctx, sellerMember, kyc.ID, 14*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if last := f.store.signed[len(f.store.signed)-1]; last != domain.DefaultLinkTTL {
		t.Fatalf("KYC links must stay at 5 minutes, signed for %s", last)
	}
	if got.URLExpiresAt == nil || got.URLExpiresAt.After(time.Now().Add(6*time.Minute)) {
		t.Fatalf("KYC link expiry %v", got.URLExpiresAt)
	}

	// The maximum is configuration…
	f = newDocuments(0, domain.LinkPolicy{MaxLong: 72 * time.Hour})
	exp := f.upload(t, staff, domain.PurposeExport, "orders.csv", domain.ContentTypeCSV, []byte("id,total\n1,10.00\n"))
	if _, err := f.svc.Get(ctx, staff, exp.ID, 14*24*time.Hour); err != nil {
		t.Fatal(err)
	}
	if last := f.store.signed[len(f.store.signed)-1]; last != 72*time.Hour {
		t.Fatalf("configured maximum is 72h, signed for %s", last)
	}

	// …and the store's own limit wins (S3 SigV4 signs for at most 7 days).
	f = newDocuments(7*24*time.Hour, domain.LinkPolicy{})
	po := f.upload(t, retailService, domain.PurposePurchaseOrder, "PO-1002.pdf", domain.ContentTypePDF, pdf())
	got, err = f.svc.Get(ctx, retailService, po.ID, 14*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if last := f.store.signed[len(f.store.signed)-1]; last != 7*24*time.Hour {
		t.Fatalf("store caps at 7 days, signed for %s", last)
	}
	if got.URLExpiresAt.After(time.Now().Add(7*24*time.Hour + time.Minute)) {
		t.Fatalf("expiry must report the capped lifetime, got %v", got.URLExpiresAt)
	}
}

func TestDocumentPurposesAcceptOnlyTheNamedServiceClients(t *testing.T) {
	f := newDocuments(0, domain.LinkPolicy{})
	ctx := context.Background()

	for _, c := range []struct {
		actor   auth.Actor
		purpose domain.Purpose
		name    string
		ct      string
	}{
		{retailService, domain.PurposeShippingDocument, "bol.pdf", domain.ContentTypePDF},
		{bffService, domain.PurposeExport, "orders.csv", domain.ContentTypeCSV},
		{bffService, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF},
		{customer, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF},
		{staff, domain.PurposePurchaseOrder, "po.pdf", domain.ContentTypePDF},
	} {
		if _, _, err := f.svc.RequestUpload(ctx, c.actor, c.purpose, c.name, c.ct, 1000); err != nil {
			t.Fatalf("%s should upload %s: %v", c.actor.Subject, c.purpose, err)
		}
	}
	for _, c := range []struct {
		actor   auth.Actor
		purpose domain.Purpose
	}{
		{otherService, domain.PurposePurchaseOrder},    // not a named client
		{sellerMember, domain.PurposePurchaseOrder},    // staff-only purpose
		{customer, domain.PurposeExport},               // staff-only purpose
		{retailService, domain.PurposeKYCDocument},     // named clients stay on the purposes that name them
		{bffService, domain.PurposeProductImage},       // …
		{otherService, domain.PurposeCareerResume},     // not a named client
		{sellerMember, domain.PurposeShippingDocument}, // staff-only purpose
	} {
		rules, _ := domain.RulesFor(c.purpose)
		if _, _, err := f.svc.RequestUpload(ctx, c.actor, c.purpose, "f", rules.ContentTypes[0], 1000); !errors.Is(err, app.ErrForbidden) {
			t.Fatalf("%s must not upload %s, got %v", c.actor.Subject, c.purpose, err)
		}
	}

	// Reads: the named clients read each other's documents (retail reads shipment papers staff uploaded through the
	// BFF); other services, other users and named clients on other purposes do not.
	doc := f.upload(t, staff, domain.PurposeShippingDocument, "asn.pdf", domain.ContentTypePDF, pdf())
	if _, err := f.svc.Get(ctx, retailService, doc.ID, 0); err != nil {
		t.Fatalf("retail-service should read shipping documents: %v", err)
	}
	if _, err := f.svc.Get(ctx, otherService, doc.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("other services must not read shipping documents, got %v", err)
	}
	if _, err := f.svc.Get(ctx, sellerMember, doc.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("sellers must not read shipping documents, got %v", err)
	}
	if _, err := f.svc.Complete(ctx, otherService, doc.ID); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("other services must not complete shipping documents, got %v", err)
	}
	kyc := f.upload(t, sellerMember, domain.PurposeKYCDocument, "ein.pdf", domain.ContentTypePDF, pdf())
	if _, err := f.svc.Get(ctx, retailService, kyc.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("retail-service must not read KYC documents, got %v", err)
	}
	if _, err := f.svc.Get(ctx, otherService, kyc.ID, 0); err != nil {
		t.Fatalf("existing service access to KYC documents is unchanged: %v", err)
	}
	cv := f.upload(t, customer, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF, pdf())
	if _, err := f.svc.Get(ctx, auth.Actor{Subject: "c2", Type: "CUSTOMER"}, cv.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("another customer must not read a résumé, got %v", err)
	}
}

func TestExportsAndResumesCheckTheRealFileType(t *testing.T) {
	f := newDocuments(0, domain.LinkPolicy{})
	if got := f.upload(t, staff, domain.PurposeExport, "orders.xlsx", domain.ContentTypeXLSX, xlsx(t)); got.Status != domain.StatusReady || got.ContentType != domain.ContentTypeXLSX {
		t.Fatalf("xlsx export %+v", got)
	}
	if got := f.upload(t, bffService, domain.PurposeExport, "orders.csv", domain.ContentTypeCSV, []byte("sku,qty\nGARI-2LB,12\n")); got.Status != domain.StatusReady || got.ContentType != domain.ContentTypeCSV {
		t.Fatalf("csv export %+v", got)
	}
	if got := f.upload(t, staff, domain.PurposeExport, "orders.csv", domain.ContentTypeCSV, []byte("<html><script>x()</script></html>")); got.Status != domain.StatusRejected {
		t.Fatalf("HTML declared as CSV must be rejected: %+v", got)
	}
	if got := f.upload(t, customer, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF, []byte("sku,qty\n1,2\n")); got.Status != domain.StatusRejected {
		t.Fatalf("text declared as PDF must be rejected: %+v", got)
	}
	if _, _, err := f.svc.RequestUpload(context.Background(), customer, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF, 6<<20); !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("résumés are limited to 5 MB, got %v", err)
	}
}

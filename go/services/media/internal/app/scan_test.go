package app_test

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

const eicarString = `X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*`

// fakeScanner flags the EICAR test string; down makes it return "unavailable".
type fakeScanner struct {
	down  bool
	calls int
}

func (f *fakeScanner) Scan(_ context.Context, r io.Reader, _ int64) (domain.ScanResult, error) {
	f.calls++
	if f.down {
		return domain.ScanResult{}, fmt.Errorf("%w: down", domain.ErrScannerUnavailable)
	}
	b, _ := io.ReadAll(r)
	if bytes.Contains(b, []byte(eicarString)) {
		return domain.ScanResult{Infected: true, Signature: "Eicar-Test-Signature"}, nil
	}
	return domain.ScanResult{}, nil
}

func scanFixture(sc port.Scanner, policy app.ScanPolicy) documentsFixture {
	st := &memStore{objects: map[string][]byte{}}
	stores := map[domain.Purpose]port.ObjectStore{}
	for _, p := range domain.Purposes() {
		stores[p] = st
	}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	return documentsFixture{svc: app.NewMediaService(stores, repo, inspect.New(40_000_000), domain.LinkPolicy{},
		app.WithScanner(sc, policy)), store: st}
}

func pdfWith(extra string) []byte { return append(pdf(), []byte(extra)...) }

func TestCleanDocumentIsReleased(t *testing.T) {
	f := scanFixture(&fakeScanner{}, app.ScanPolicy{})
	a := f.upload(t, bffGuest, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF, pdf())
	if a.Status != domain.StatusReady || a.Notice != "" {
		t.Fatalf("want READY without notice, got %s %q", a.Status, a.Notice)
	}
}

func TestInfectedDocumentIsRejectedAndDeleted(t *testing.T) {
	f := scanFixture(&fakeScanner{}, app.ScanPolicy{})
	a := f.upload(t, bffGuest, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF, pdfWith(eicarString))
	if a.Status != domain.StatusRejected || len(a.Flags) != 1 || a.Flags[0] != domain.FlagMalware {
		t.Fatalf("want REJECTED malware, got %s %v", a.Status, a.Flags)
	}
	if len(f.store.objects) != 0 {
		t.Fatalf("infected object must be deleted, store has %d", len(f.store.objects))
	}
	got, err := f.svc.Get(context.Background(), staff, a.ID, 0)
	if err != nil || got.URL != "" {
		t.Fatalf("infected asset must never be served: url=%q err=%v", got.URL, err)
	}
}

func TestDocumentStaysQuarantinedWhileScannerIsDown(t *testing.T) {
	sc := &fakeScanner{down: true}
	f := scanFixture(sc, app.ScanPolicy{Documents: app.ScanRequired})
	a := f.upload(t, sellerMember, domain.PurposeKYCDocument, "id.pdf", domain.ContentTypePDF, pdf())
	if a.Status != domain.StatusPending || a.Notice != domain.NoticeChecking {
		t.Fatalf("want PENDING with the checking notice, got %s %q", a.Status, a.Notice)
	}
	got, err := f.svc.Get(context.Background(), kycStaff, a.ID, 0)
	if err != nil || got.URL != "" || got.Notice != domain.NoticeChecking {
		t.Fatalf("pending file must not be served: %+v %v", got, err)
	}
	// The scanner comes back: asking again releases the file.
	sc.down = false
	done, err := f.svc.Complete(context.Background(), sellerMember, a.ID)
	if err != nil || done.Status != domain.StatusReady {
		t.Fatalf("retry: %s %v", done.Status, err)
	}
}

func TestExportsAreNotScanned(t *testing.T) {
	sc := &fakeScanner{down: true}
	f := scanFixture(sc, app.ScanPolicy{})
	a := f.upload(t, retailService, domain.PurposeExport, "x.csv", domain.ContentTypeCSV, []byte("a,b\n1,2\n"))
	if a.Status != domain.StatusReady || sc.calls != 0 {
		t.Fatalf("export: %s, scanner calls %d", a.Status, sc.calls)
	}
}

func TestBestEffortReleasesWhenScannerIsDown(t *testing.T) {
	f := scanFixture(&fakeScanner{down: true}, app.ScanPolicy{Documents: app.ScanBestEffort})
	a := f.upload(t, bffGuest, domain.PurposeCareerResume, "cv.pdf", domain.ContentTypePDF, pdf())
	if a.Status != domain.StatusReady {
		t.Fatalf("best-effort must release, got %s", a.Status)
	}
}

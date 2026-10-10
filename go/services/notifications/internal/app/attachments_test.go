package app_test

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// attachingEmail is a captureEmail that can attach files.
type attachingEmail struct {
	captureEmail
	name string
	fail error
}

func (a *attachingEmail) Name() string              { return a.name }
func (a *attachingEmail) SupportsAttachments() bool { return true }
func (a *attachingEmail) Send(ctx context.Context, m domain.EmailMessage) (domain.Receipt, error) {
	if a.fail != nil {
		a.got = append(a.got, m)
		return domain.Receipt{}, a.fail
	}
	return a.captureEmail.Send(ctx, m)
}

// fakeFetcher serves attachments from memory; URLs not in files fail like an expired link.
type fakeFetcher struct {
	files map[string][]byte
	calls []string
}

func (f *fakeFetcher) Fetch(_ context.Context, ref domain.AttachmentRef) (domain.Attachment, error) {
	f.calls = append(f.calls, ref.URL)
	b, ok := f.files[ref.URL]
	if !ok {
		return domain.Attachment{}, errors.New("status 403")
	}
	return domain.Attachment{FileName: ref.FileName, ContentType: ref.ContentType, Content: b}, nil
}

var _ port.AttachmentFetcher = (*fakeFetcher)(nil)

// retailPO is notification.requested as retail-service writes it for a supplier PO email (PoDispatchServiceImpl).
func retailPO() map[string]any {
	return map[string]any{"templateKey": "retail.purchase_order.sent", "channels": []string{"email"},
		"recipient": map[string]any{"email": "efua@accrapack.example", "name": "Efua Mensah"}, "locale": "en",
		"data": map[string]any{"poId": "6f1c", "poNumber": "PO-1P-323", "supplierName": "Accra Pack Ltd", "supplierCode": "SUP-ACC",
			"contactName": "Efua Mensah", "orderDate": "10 Oct 2026", "deliverBy": "", "incoterm": "FOB", "paymentTerms": nil,
			"shipTo": "HOU-FC", "lineCount": 1, "units": 480, "totalMinor": 1665600, "currency": "GHS", "total": "GHS 16656.00",
			"documentUrl": "https://files.example/po/a1.pdf?sig=abc", "documentExpiresAt": "2026-10-24T10:00:00Z",
			"documentAssetId": "a1", "attachments": []any{map[string]any{"assetId": "a1", "fileName": "PO-1P-323.pdf",
				"contentType": "application/pdf", "url": "https://files.example/po/a1.pdf?sig=abc", "expiresAt": "2026-10-24T10:00:00Z"}}}}
}

func TestRetailPurchaseOrderRequestRendersToSupplierWithoutIdentity(t *testing.T) {
	store := newFakeStore()
	// Identity is down: a supplier contact is never looked up, so the event still goes out.
	res := &fakeResolver{err: errors.New("identity down")}
	ing := newIngest(store, res, "contactName", "poNumber", "supplierName", "orderDate", "deliverBy", "incoterm",
		"paymentTerms", "total", "documentUrl", "documentExpiresOn")
	payload := retailPO()
	payload["recipient"].(map[string]any)["id"] = "sup-1" // even with an id
	handle(t, ing, app.GenericEventType, payload)
	if res.calls != 0 {
		t.Fatalf("supplier looked up in identity %d times", res.calls)
	}
	if got := summary(store.enqueued); len(got) != 1 || got[0] != "retail.purchase_order.sent|email|efua@accrapack.example" {
		t.Fatalf("%v", got)
	}
	row := store.enqueued[0]
	for _, part := range []string{"contactName=Efua Mensah", "poNumber=PO-1P-323", "paymentTerms=;", "deliverBy=;",
		"total=GHS 16656.00", "documentUrl=https://files.example/po/a1.pdf?sig=abc", "documentExpiresOn=Oct 24, 2026"} {
		if !strings.Contains(row.BodyText, part) {
			t.Errorf("body %q lacks %q", row.BodyText, part)
		}
	}
	if len(row.Attachments) != 1 || row.Attachments[0].AssetID != "a1" || row.Attachments[0].FileName != "PO-1P-323.pdf" ||
		row.Attachments[0].URL != "https://files.example/po/a1.pdf?sig=abc" {
		t.Fatalf("attachments: %+v", row.Attachments)
	}
}

func TestPurchaseOrderDefaultsForMissingFields(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, nil, "contactName", "supplierName", "total", "documentExpiresOn")
	payload := retailPO()
	d := payload["data"].(map[string]any)
	d["contactName"], d["supplierName"], d["total"], d["documentExpiresAt"] = nil, nil, nil, "not a date"
	handle(t, ing, app.GenericEventType, payload)
	body := store.enqueued[0].BodyText
	for _, part := range []string{"contactName=there", "supplierName=your company", "total=GH₵16,656.00", "documentExpiresOn=;"} {
		if !strings.Contains(body, part) {
			t.Errorf("body %q lacks %q", body, part)
		}
	}
}

func TestNotifierKeepsAttachmentsOnEmailRowsOnly(t *testing.T) {
	store := newFakeStore()
	n := app.NewNotifier(echoTemplates{}, app.NewRenderer(), store, nil)
	refs := []domain.AttachmentRef{{FileName: "a/../b.pdf", URL: " https://files.example/b.pdf "}, {FileName: "no-url.pdf"}}
	_, err := n.Submit(context.Background(), domain.Request{TemplateKey: "k", IdempotencyKey: "i", Attachments: refs,
		Channels:  []domain.Channel{domain.ChannelEmail, domain.ChannelSMS},
		Recipient: domain.Recipient{Email: "a@example.com", Phone: "+15550001111"}})
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range store.enqueued {
		switch r.Channel {
		case domain.ChannelEmail:
			if len(r.Attachments) != 1 || r.Attachments[0].FileName != "b.pdf" || r.Attachments[0].URL != "https://files.example/b.pdf" {
				t.Errorf("email attachments: %+v", r.Attachments)
			}
		default:
			if len(r.Attachments) != 0 {
				t.Errorf("%s row has attachments", r.Channel)
			}
		}
	}
}

func emailRow(refs ...domain.AttachmentRef) domain.Notification {
	return domain.Notification{ID: "n-po", Channel: domain.ChannelEmail, Address: "efua@accrapack.example", Market: "us",
		Subject: "PO", BodyText: "download: https://files.example/po/a1.pdf", Attachments: refs}
}

func dispatch(t *testing.T, cfg app.DispatcherConfig, row domain.Notification, senders ...port.EmailSender) *fakeStore {
	t.Helper()
	store := newFakeStore()
	store.claim = []domain.Notification{row}
	router := app.NewRouter(app.Routes{Email: map[string][]port.EmailSender{"default": senders}},
		domain.NewBreakers(domain.BreakerSettings{FailureThreshold: 100}), time.Second, quietLog())
	cfg.Concurrency = 1
	if _, err := app.NewDispatcher(cfg, store, fakeChecker{}, router, quietLog()).RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	return store
}

func TestDispatcherAttachesFetchedFiles(t *testing.T) {
	pdf := []byte("%PDF-1.7")
	fetcher := &fakeFetcher{files: map[string][]byte{"https://files.example/po/a1.pdf": pdf}}
	email := &attachingEmail{name: "resend"}
	store := dispatch(t, app.DispatcherConfig{Attachments: fetcher}, emailRow(
		domain.AttachmentRef{FileName: "PO-1P-323.pdf", ContentType: "application/pdf", URL: "https://files.example/po/a1.pdf"},
		domain.AttachmentRef{FileName: "expired.pdf", URL: "https://files.example/expired.pdf"}), email)
	if _, ok := store.sent["n-po"]; !ok || len(email.got) != 1 {
		t.Fatalf("not sent: %+v", store.final)
	}
	got := email.got[0].Attachments
	if len(got) != 1 || got[0].FileName != "PO-1P-323.pdf" || string(got[0].Content) != "%PDF-1.7" {
		t.Fatalf("an unfetchable file is left out, the other attached: %+v", got)
	}
	if !strings.Contains(email.got[0].Text, "https://files.example/po/a1.pdf") {
		t.Fatal("the link stays in the body")
	}
}

func TestDispatcherKeepsTotalUnderTheLimit(t *testing.T) {
	fetcher := &fakeFetcher{files: map[string][]byte{"https://f/1": make([]byte, 6), "https://f/2": make([]byte, 6),
		"https://f/3": make([]byte, 4)}}
	email := &attachingEmail{name: "resend"}
	dispatch(t, app.DispatcherConfig{Attachments: fetcher, MaxAttachmentBytes: 10}, emailRow(
		domain.AttachmentRef{FileName: "1", URL: "https://f/1"}, domain.AttachmentRef{FileName: "2", URL: "https://f/2"},
		domain.AttachmentRef{FileName: "3", URL: "https://f/3"}), email)
	var names []string
	for _, a := range email.got[0].Attachments {
		names = append(names, a.FileName)
	}
	if strings.Join(names, ",") != "1,3" {
		t.Fatalf("attached %v, want 1 and 3 (2 would exceed 10 bytes)", names)
	}
}

func TestProvidersThatCannotAttachGetTheLinkOnly(t *testing.T) {
	fetcher := &fakeFetcher{files: map[string][]byte{"https://files.example/po/a1.pdf": []byte("%PDF-")}}
	// The attaching provider is down; the fallback (log-like, no attachment support) still sends the email.
	primary := &attachingEmail{name: "resend", fail: domain.Retry("resend", 503, "").Wrap(nil)}
	fallback := &captureEmail{}
	store := dispatch(t, app.DispatcherConfig{Attachments: fetcher}, emailRow(
		domain.AttachmentRef{FileName: "PO.pdf", URL: "https://files.example/po/a1.pdf"}), primary, fallback)
	if store.sent["n-po"].Provider != "capture" {
		t.Fatalf("%+v", store.sent)
	}
	if len(primary.got) != 1 || len(primary.got[0].Attachments) != 1 {
		t.Fatalf("the attaching provider gets the file: %+v", primary.got)
	}
	if len(fallback.got) != 1 || len(fallback.got[0].Attachments) != 0 || !strings.Contains(fallback.got[0].Text, "https://files.example/po/a1.pdf") {
		t.Fatalf("the non-attaching provider gets the link only: %+v", fallback.got)
	}
	if app.CanAttach(fallback) || !app.CanAttach(primary) {
		t.Fatal("CanAttach")
	}
}

func TestNoFetcherSendsLinksOnly(t *testing.T) {
	email := &attachingEmail{name: "resend"}
	store := dispatch(t, app.DispatcherConfig{}, emailRow(domain.AttachmentRef{FileName: "PO.pdf", URL: "https://files.example/x"}), email)
	if _, ok := store.sent["n-po"]; !ok || len(email.got[0].Attachments) != 0 {
		t.Fatalf("%+v", email.got)
	}
}

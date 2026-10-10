package domain_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestSafeFileName(t *testing.T) {
	for in, want := range map[string]string{"PO-1P-323.pdf": "PO-1P-323.pdf", "../../etc/passwd": "passwd",
		`C:\docs\po.pdf`: "po.pdf", "a\"b\r\n.pdf": "ab.pdf", "": "attachment", "/": "attachment", "\t.": "attachment"} {
		if got := domain.SafeFileName(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestAttachmentRefsFrom(t *testing.T) {
	data := map[string]any{"attachments": []any{
		map[string]any{"assetId": "a1", "fileName": "PO-1.pdf", "contentType": "application/pdf", "url": "https://f/1", "expiresAt": "2026-10-24T10:00:00Z"},
		map[string]any{"fileName": "no-url.pdf", "url": nil},
		"not an object",
	}}
	refs := domain.AttachmentRefsFrom(data)
	if len(refs) != 1 || refs[0] != (domain.AttachmentRef{AssetID: "a1", FileName: "PO-1.pdf", ContentType: "application/pdf",
		URL: "https://f/1", ExpiresAt: "2026-10-24T10:00:00Z"}) {
		t.Fatalf("%+v", refs)
	}
	if domain.AttachmentRefsFrom(map[string]any{}) != nil || domain.AttachmentRefsFrom(nil) != nil {
		t.Fatal("no attachments → nil")
	}
	many := make([]any, 0, 20)
	for range 20 {
		many = append(many, map[string]any{"url": "https://f/x"})
	}
	if got := domain.AttachmentRefsFrom(map[string]any{"attachments": many}); len(got) != domain.MaxAttachments {
		t.Fatalf("capped at %d: %d", domain.MaxAttachments, len(got))
	}
}

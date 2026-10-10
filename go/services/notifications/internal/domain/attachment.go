package domain

import (
	"errors"
	"path"
	"strings"
)

// MaxAttachments caps the attachment references kept per notification.
const MaxAttachments = 10

// AttachmentRef is a file an email refers to by URL (a signed media link). It is stored with the notification and
// fetched by the dispatcher just before sending; when it cannot be fetched or the provider cannot attach files, the
// email still carries the link its template renders.
type AttachmentRef struct {
	AssetID     string `json:"assetId,omitempty"`
	FileName    string `json:"fileName"`
	ContentType string `json:"contentType,omitempty"`
	URL         string `json:"url"`
	ExpiresAt   string `json:"expiresAt,omitempty"`
}

// Attachment is a fetched file ready to attach.
type Attachment struct {
	FileName    string
	ContentType string
	Content     []byte
}

// ErrAttachmentRejected is returned when an attachment is not allowed (host not allow-listed, too large, bad URL).
var ErrAttachmentRejected = errors.New("attachment rejected")

// SafeFileName strips directories, quotes and control characters from a file name ("attachment" when nothing is
// left), so it can go into a Content-Disposition header.
func SafeFileName(name string) string {
	name = path.Base(strings.ReplaceAll(name, `\`, "/"))
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || r == '"' {
			return -1
		}
		return r
	}, name)
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == "/" {
		return "attachment"
	}
	return name
}

// AttachmentRefsFrom reads producer attachment references from template data (data.attachments:
// [{assetId, fileName, contentType, url, expiresAt}]), cleaned with CleanAttachmentRefs.
func AttachmentRefsFrom(data map[string]any) []AttachmentRef {
	raw, _ := data["attachments"].([]any)
	refs := make([]AttachmentRef, 0, len(raw))
	for _, x := range raw {
		m, ok := x.(map[string]any)
		if !ok {
			continue
		}
		s := func(k string) string { v, _ := m[k].(string); return v }
		refs = append(refs, AttachmentRef{AssetID: s("assetId"), FileName: s("fileName"), ContentType: s("contentType"),
			URL: s("url"), ExpiresAt: s("expiresAt")})
	}
	return CleanAttachmentRefs(refs)
}

// CleanAttachmentRefs drops references without a URL, makes file names safe and keeps at most MaxAttachments.
func CleanAttachmentRefs(refs []AttachmentRef) []AttachmentRef {
	var out []AttachmentRef
	for _, r := range refs {
		r.URL = strings.TrimSpace(r.URL)
		if r.URL == "" {
			continue
		}
		r.AssetID, r.ContentType = strings.TrimSpace(r.AssetID), strings.TrimSpace(r.ContentType)
		r.FileName = SafeFileName(r.FileName)
		out = append(out, r)
		if len(out) == MaxAttachments {
			break
		}
	}
	return out
}

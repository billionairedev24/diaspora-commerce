package searchindex

import (
	"net/url"
	"strings"
)

// placeholderMarks are path fragments of the stand-in images the platform and its sellers use when there is no photo.
var placeholderMarks = []string{"placeholder", "no-image", "noimage", "no_image", "default-image", "coming-soon", "image-not-available", "/icons/", "/icon-"}

// PhotoOK reports whether imageURL points at an actual photograph: an http(s) or site-relative address that is not an
// inline (data:) or vector (svg) image, not an icon (ico) and not one of the known placeholders. It is the ONE rule for
// "a real photo": the indexer stores its result as photo_ok, and the gateway's home eligibility (eligibleCard) calls it, so
// counts in nav, footer, sitemap and the home equal what a shopper can be shown (S415).
func PhotoOK(imageURL string) bool {
	raw := strings.TrimSpace(imageURL)
	if raw == "" {
		return false
	}
	low := strings.ToLower(raw)
	if !strings.HasPrefix(low, "https://") && !strings.HasPrefix(low, "http://") && !strings.HasPrefix(low, "/") {
		return false
	}
	parsed, err := url.Parse(low)
	if err != nil {
		return false
	}
	if strings.HasSuffix(parsed.Path, ".svg") || strings.HasSuffix(parsed.Path, ".ico") {
		return false
	}
	for _, m := range placeholderMarks {
		if strings.Contains(parsed.Path, m) {
			return false
		}
	}
	return true
}

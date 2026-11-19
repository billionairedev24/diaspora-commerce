package picturerules

import (
	"net/url"
	"strings"
)

// placeholderMarks are path fragments of the stand-in images the platform and its sellers use when there is no photo.
var placeholderMarks = []string{"placeholder", "no-image", "noimage", "no_image", "default-image", "coming-soon", "image-not-available", "/icons/", "/icon-"}

// PhotoURL classifies an image address.
type PhotoURL int

// Photo address classes.
const (
	// PhotoNone: empty, inline (data:), vector (svg) or icon (ico): never a photo under any profile.
	PhotoNone PhotoURL = iota
	// PhotoPlaceholder: a known stand-in image.
	PhotoPlaceholder
	// PhotoReal: an http(s) or site-relative address of an actual photograph.
	PhotoReal
)

// Classify says what an address points at. Vector and inline images are never photos, whatever the profile.
func Classify(imageURL string) PhotoURL {
	raw := strings.TrimSpace(imageURL)
	if raw == "" {
		return PhotoNone
	}
	low := strings.ToLower(raw)
	if !strings.HasPrefix(low, "https://") && !strings.HasPrefix(low, "http://") && !strings.HasPrefix(low, "/") {
		return PhotoNone
	}
	parsed, err := url.Parse(low)
	if err != nil {
		return PhotoNone
	}
	if strings.HasSuffix(parsed.Path, ".svg") || strings.HasSuffix(parsed.Path, ".ico") {
		return PhotoNone
	}
	for _, m := range placeholderMarks {
		if strings.Contains(parsed.Path, m) {
			return PhotoPlaceholder
		}
	}
	return PhotoReal
}

// IsRealPhoto is Classify == PhotoReal: the strict test, used where the profile cannot matter (the search index stores it
// as photo_ok; the storefront decides from the profile whether to filter on it).
func IsRealPhoto(imageURL string) bool { return Classify(imageURL) == PhotoReal }

// PhotoOK reports whether an address counts as a photo under these rules: a real photo always, a placeholder only when the
// profile allows placeholders. SVG, ico and inline images never count.
func (r Rules) PhotoOK(imageURL string) bool {
	switch Classify(imageURL) {
	case PhotoReal:
		return true
	case PhotoPlaceholder:
		return r.Knobs.AllowPlaceholders
	default:
		return false
	}
}

// ShowOnStorefront is the one storefront rule for home, rails, nav counts and search: with the real-photo requirement on,
// only a product whose photo counts is shown; with it off every product is shown (staff are told on the console, and the
// card is flagged "no photo").
func (r Rules) ShowOnStorefront(imageURL string) bool {
	if !r.Knobs.RequireRealPhotoForStorefront {
		return true
	}
	return r.PhotoOK(imageURL)
}

// MatchableWithPhoto is the candidate-matching rule: whether a page with this first picture may be offered to sellers.
func (r Rules) MatchableWithPhoto(imageURL string) bool {
	if !r.Knobs.RequirePhotoForMatching {
		return true
	}
	return r.PhotoOK(imageURL)
}

// StorefrontPhotoFilter reports whether queries must keep only photo_ok documents. The index stores IsRealPhoto; under
// AllowPlaceholders a placeholder would count but is not indexed as ok, so the filter is dropped in that case too.
func (r Rules) StorefrontPhotoFilter() bool {
	return r.Knobs.RequireRealPhotoForStorefront && !r.Knobs.AllowPlaceholders
}

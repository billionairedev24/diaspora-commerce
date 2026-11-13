package app

import (
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The bulk preview shows an image problem with the same words as the product form's image cards (S391). Those words
// live in one place: Seller Center translates the check code under sc.img.check.<CODE> (media's codes, see
// go/services/media/internal/domain/imagecheck.go). So the preview carries a media code, never a sentence of its own.
// Catalog may send the check itself (code, level, params); when it only sends the row image's state, the code is
// derived here from the state.

// Image check codes the bulk preview derives from a row image's state (the same codes media and the form use).
const (
	checkHeldReview   = "HELD_REVIEW"
	checkNoMainImage  = "NO_MAIN_IMAGE"
	checkNotVerified  = "NOT_VERIFIED"
	checkFetchFailed  = "FETCH_FAILED"
	checkFetchPrivate = "FETCH_PRIVATE"
)

// importImageCheck is the finding behind a preview row image, or nil for an image that is fine (FOUND, LINK).
func importImageCheck(i domain.ImportRowImage) *domain.ImageCheck {
	msg := derefString(i.Message)
	switch i.State {
	case "HELD":
		return &domain.ImageCheck{Code: checkHeldReview, Level: "HOLD", Params: map[string]any{}, Message: msg}
	case "MISSING":
		code := checkNotVerified
		if i.Slot == "MAIN" {
			code = checkNoMainImage
		}
		return &domain.ImageCheck{Code: code, Level: "REJECT", Params: map[string]any{}, Message: msg}
	case "BAD_LINK":
		code := checkFetchFailed
		if strings.Contains(strings.ToLower(msg), "private") {
			code = checkFetchPrivate
		}
		return &domain.ImageCheck{Code: code, Level: "REJECT", Params: map[string]any{}, Message: msg}
	}
	return nil
}

func derefString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func orEmptyParams(p map[string]any) map[string]any {
	if p == nil {
		return map[string]any{}
	}
	return p
}

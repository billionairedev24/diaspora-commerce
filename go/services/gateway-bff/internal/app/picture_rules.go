package app

import (
	"context"
	"sync/atomic"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The platform picture rules (profile strict, startup or testing plus knobs; owned by catalog, edited in the staff console under
// Settings > Picture rules). The storefront reads them through one shared source with a 30 second cache, wired once at start;
// without a source every check is the strict standard, today's behaviour.
var pictureRulesSource atomic.Pointer[picturerules.Source]

// UsePictureRules wires the source the storefront eligibility rule, the seller messages and the staff page read.
func UsePictureRules(src picturerules.Source) { pictureRulesSource.Store(&src) }

// CurrentPictureRules is the rules in force now (strict when no source was wired).
func CurrentPictureRules(ctx context.Context) picturerules.Rules {
	if p := pictureRulesSource.Load(); p != nil {
		return (*p).Rules(ctx)
	}
	return picturerules.Default(picturerules.Strict)
}

// invalidatePictureRules drops the cached rules after staff saved them, so this instance shows the change at once.
func invalidatePictureRules() {
	if p := pictureRulesSource.Load(); p != nil {
		(*p).Invalidate()
	}
}

// photoShown is the storefront rule for a product card's photo: with the real-photo requirement on (strict, startup) only a card
// with a real photo shows; under testing every in-stock card shows, with or without one.
func photoShown(imageURL *string) bool {
	u := ""
	if imageURL != nil {
		u = *imageURL
	}
	return CurrentPictureRules(context.Background()).ShowOnStorefront(u)
}

// PictureRulesView is the public document: the rule in force in plain words and as numbers, for Seller Center's messages and the
// mobile app's picker. Nothing here is secret.
func PictureRulesView(r picturerules.Rules) map[string]any {
	return map[string]any{"profile": string(r.Profile), "knobs": r.Knobs, "overridden": r.Overridden, "formats": r.Formats,
		"version": r.Version, "summary": r.Summary(), "relaxed": r.Relaxed()}
}

// SellerPictureRules is GET /v1/seller/picture-rules: the rule in force, so the form says "at least 1 photo, at least 500 px"
// rather than a fixed rule.
func SellerPictureRules(ctx context.Context, _ *domain.Caller) map[string]any {
	return PictureRulesView(CurrentPictureRules(ctx))
}

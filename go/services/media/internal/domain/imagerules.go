package domain

import (
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
)

// ImageRulesOverride is what staff change at run time (PUT /v1/image-rules); a nil field keeps the configured value.
// Each environment has its own database, so the "warn only" switch is per environment.
type ImageRulesOverride struct {
	WarnOnly               *bool    `json:"warnOnly,omitempty"`
	MaxBytes               *int64   `json:"maxBytes,omitempty"`
	MinLongestSide         *int     `json:"minLongestSide,omitempty"`
	RecommendedLongestSide *int     `json:"recommendedLongestSide,omitempty"`
	MinShortestSide        *int     `json:"minShortestSide,omitempty"`
	FillReject             *float64 `json:"fillReject,omitempty"`
	FillWarn               *float64 `json:"fillWarn,omitempty"`
	AllowOnModelApparel    *bool    `json:"allowOnModelApparel,omitempty"`
	OtherWarnLongestSide   *int     `json:"otherWarnLongestSide,omitempty"`
	OtherMinShortestSide   *int     `json:"otherMinShortestSide,omitempty"`
	MismatchHold           *float64 `json:"mismatchHold,omitempty"`
}

// Apply returns the base rules with the override on top.
func (o ImageRulesOverride) Apply(base ImageRules) ImageRules {
	if o.WarnOnly != nil {
		base.WarnOnly = *o.WarnOnly
	}
	if o.MaxBytes != nil {
		base.MaxBytes = *o.MaxBytes
	}
	if o.MinLongestSide != nil {
		base.Main.MinLongestSide = *o.MinLongestSide
	}
	if o.RecommendedLongestSide != nil {
		base.Main.RecommendedLongestSide = *o.RecommendedLongestSide
	}
	if o.MinShortestSide != nil {
		base.Main.MinShortestSide = *o.MinShortestSide
	}
	if o.FillReject != nil {
		base.Main.FillReject = *o.FillReject
	}
	if o.FillWarn != nil {
		base.Main.FillWarn = *o.FillWarn
	}
	if o.AllowOnModelApparel != nil {
		base.Main.AllowOnModelApparel = *o.AllowOnModelApparel
	}
	if o.OtherWarnLongestSide != nil {
		base.Other.WarnLongestSide = *o.OtherWarnLongestSide
	}
	if o.OtherMinShortestSide != nil {
		base.Other.MinShortestSide = *o.OtherMinShortestSide
	}
	if o.MismatchHold != nil {
		base.Main.MismatchHold = *o.MismatchHold
	}
	return base
}

// StoredImageRules is the override as stored, with who changed it last.
type StoredImageRules struct {
	Override  ImageRulesOverride `json:"override"`
	UpdatedBy string             `json:"updatedBy,omitempty"`
	UpdatedAt *time.Time         `json:"updatedAt,omitempty"`
}

// ApplyPictureRules puts the platform picture rules on top of the configured thresholds. Under the strict profile with no
// override nothing changes (today's behaviour, including media's own configuration and staff override); under startup and
// testing every knob applies; under strict with overrides only the overridden knobs apply. The result is checked with
// Problem and the base is used when the combination is not valid.
func ApplyPictureRules(base ImageRules, pr picturerules.Rules) ImageRules {
	r := base
	k := pr.Knobs
	if pr.Applies("maxMegabytes") {
		r.MaxBytes = pr.MaxBytes()
	}
	if pr.Applies("minLongSidePx") {
		r.Main.MinLongestSide = k.MinLongSidePx
		r.Main.RecommendedLongestSide = max(r.Main.RecommendedLongestSide, k.MinLongSidePx)
		r.Other.WarnLongestSide = k.MinLongSidePx
	}
	if pr.Applies("minShortSidePx") {
		r.Main.MinShortestSide = k.MinShortSidePx
		r.Other.MinShortestSide = k.MinShortSidePx
	}
	r.Main.MinShortestSide = min(r.Main.MinShortestSide, r.Main.MinLongestSide)
	r.Other.WarnLongestSide = max(r.Other.WarnLongestSide, r.Other.MinShortestSide)
	if pr.Applies("checkBackground") && !k.CheckBackground {
		r.SkipAppearance = true
	}
	if pr.Applies("checkAspect") && !k.CheckAspect {
		r.SkipAspect = true
	}
	if pr.Applies("allowPlaceholders") && k.AllowPlaceholders {
		r.AllowPlaceholders = true
	}
	if pr.Applies("checkTextOverlay") && !k.CheckTextOverlay {
		r.SkipTextHold = true
	}
	if pr.Profile == picturerules.Testing {
		r.SkipQualityHolds = true
	}
	if pr.Profile != picturerules.Strict {
		r.PassUninspectable = true
	}
	if r.Problem() != "" {
		return base
	}
	return r
}

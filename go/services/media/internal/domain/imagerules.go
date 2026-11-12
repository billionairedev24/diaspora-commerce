package domain

import "time"

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

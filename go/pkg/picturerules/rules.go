// Package picturerules is the ONE definition of the product picture rules the platform enforces: the named profiles
// (strict, startup, testing), the knobs staff can override one by one, the "is this a real photo" test every Go service
// shares (search index, storefront rails, media checks, seller messages) and the image format list.
//
// The setting itself is owned by catalog-service (table picture_rules_setting, audited, staff-controlled); Go services
// read the effective rules from catalog over HTTP through Client (short cache, explicit invalidation) and fall back to
// the environment's default profile when catalog cannot be reached. Java reads the same document in process. The golden
// vectors in contracts/picture-rules/vectors.json are run by both languages, so they cannot drift.
//
// Security is not a knob: the format list (JPEG, PNG, WebP, AVIF), content sniffing by magic bytes, the active-content
// scan, the size ceiling and the malware scan apply under every profile.
package picturerules

import (
	"fmt"
	"slices"
	"strings"
)

// Profile names a bundle of default knob values.
type Profile string

// The profiles. Strict is the documented standard and today's behaviour; Startup is the relaxed launch profile; Testing
// makes every picture requirement optional and is meant for local and dev.
const (
	Strict  Profile = "strict"
	Startup Profile = "startup"
	Testing Profile = "testing"
)

// Profiles lists the profiles from strictest to loosest.
func Profiles() []Profile { return []Profile{Strict, Startup, Testing} }

// ParseProfile reads a profile name (any case).
func ParseProfile(s string) (Profile, bool) {
	p := Profile(strings.ToLower(strings.TrimSpace(s)))
	return p, slices.Contains(Profiles(), p)
}

// Knobs are the individual rules. Every field has a plain-language explanation in the staff console.
type Knobs struct {
	// MinImages is how many pictures a listing needs before it can be published (0 = none needed).
	MinImages int `json:"minImages"`
	// MinLongSidePx: the longest side of the main picture must be at least this (the zoom rule).
	MinLongSidePx int `json:"minLongSidePx"`
	// MinShortSidePx: the shortest side of every picture must be at least this.
	MinShortSidePx int `json:"minShortSidePx"`
	// MaxMegabytes caps one file (the platform ceiling of 20 always applies).
	MaxMegabytes int `json:"maxMegabytes"`
	// CheckBackground asks for a plain white background and a product that fills the frame (main picture).
	CheckBackground bool `json:"checkBackground"`
	// CheckAspect warns about very wide or very tall pictures.
	CheckAspect bool `json:"checkAspect"`
	// CheckTextOverlay holds pictures that carry text, logos or watermarks for review.
	CheckTextOverlay bool `json:"checkTextOverlay"`
	// RequireRealPhotoForStorefront: home, rails and search results show only products with a real photo.
	RequireRealPhotoForStorefront bool `json:"requireRealPhotoForStorefront"`
	// RequirePhotoForMatching: a page without a real photo is not offered as a match to sellers.
	RequirePhotoForMatching bool `json:"requirePhotoForMatching"`
	// AllowPlaceholders: a stand-in image counts as a real photo.
	AllowPlaceholders bool `json:"allowPlaceholders"`
}

// Overrides are the knobs staff changed on top of the profile; nil keeps the profile's value.
type Overrides struct {
	MinImages                     *int  `json:"minImages,omitempty"`
	MinLongSidePx                 *int  `json:"minLongSidePx,omitempty"`
	MinShortSidePx                *int  `json:"minShortSidePx,omitempty"`
	MaxMegabytes                  *int  `json:"maxMegabytes,omitempty"`
	CheckBackground               *bool `json:"checkBackground,omitempty"`
	CheckAspect                   *bool `json:"checkAspect,omitempty"`
	CheckTextOverlay              *bool `json:"checkTextOverlay,omitempty"`
	RequireRealPhotoForStorefront *bool `json:"requireRealPhotoForStorefront,omitempty"`
	RequirePhotoForMatching       *bool `json:"requirePhotoForMatching,omitempty"`
	AllowPlaceholders             *bool `json:"allowPlaceholders,omitempty"`
}

// Platform bounds no override can cross.
const (
	MaxImagesPerListing = 10
	CeilingMegabytes    = 20
	CeilingSidePx       = 10_000
)

// KnobNames lists the knob keys in display order.
func KnobNames() []string {
	return []string{"minImages", "minLongSidePx", "minShortSidePx", "maxMegabytes", "checkBackground", "checkAspect",
		"checkTextOverlay", "requireRealPhotoForStorefront", "requirePhotoForMatching", "allowPlaceholders"}
}

// ProfileKnobs are the default knob values of a profile (an unknown profile is Strict).
func ProfileKnobs(p Profile) Knobs {
	switch p {
	case Startup:
		return Knobs{MinImages: 1, MinLongSidePx: 500, MinShortSidePx: 300, MaxMegabytes: 8,
			RequireRealPhotoForStorefront: true, RequirePhotoForMatching: true}
	case Testing:
		return Knobs{MinImages: 0, MinLongSidePx: 1, MinShortSidePx: 1, MaxMegabytes: 8,
			AllowPlaceholders: true}
	case Strict:
		fallthrough
	default:
		return Knobs{MinImages: 1, MinLongSidePx: 1000, MinShortSidePx: 500, MaxMegabytes: 8, CheckBackground: true,
			CheckAspect: true, CheckTextOverlay: true, RequireRealPhotoForStorefront: true, RequirePhotoForMatching: true}
	}
}

// Rules are the picture rules in force: the profile, its knobs with the overrides applied and which knobs are overridden.
type Rules struct {
	Profile    Profile  `json:"profile"`
	Knobs      Knobs    `json:"knobs"`
	Overridden []string `json:"overridden"`
	Formats    []string `json:"formats"`
	// Version is the stored version (0 while the environment default is in force).
	Version int64 `json:"version"`
}

// Resolve applies overrides to a profile's knobs.
func Resolve(p Profile, o Overrides) Rules {
	k := ProfileKnobs(p)
	over := []string{}
	setInt := func(dst *int, v *int, name string) {
		if v != nil {
			*dst, over = *v, append(over, name)
		}
	}
	setBool := func(dst *bool, v *bool, name string) {
		if v != nil {
			*dst, over = *v, append(over, name)
		}
	}
	setInt(&k.MinImages, o.MinImages, "minImages")
	setInt(&k.MinLongSidePx, o.MinLongSidePx, "minLongSidePx")
	setInt(&k.MinShortSidePx, o.MinShortSidePx, "minShortSidePx")
	setInt(&k.MaxMegabytes, o.MaxMegabytes, "maxMegabytes")
	setBool(&k.CheckBackground, o.CheckBackground, "checkBackground")
	setBool(&k.CheckAspect, o.CheckAspect, "checkAspect")
	setBool(&k.CheckTextOverlay, o.CheckTextOverlay, "checkTextOverlay")
	setBool(&k.RequireRealPhotoForStorefront, o.RequireRealPhotoForStorefront, "requireRealPhotoForStorefront")
	setBool(&k.RequirePhotoForMatching, o.RequirePhotoForMatching, "requirePhotoForMatching")
	setBool(&k.AllowPlaceholders, o.AllowPlaceholders, "allowPlaceholders")
	return Rules{Profile: p, Knobs: k, Overridden: over, Formats: Formats()}
}

// Default is the rules of a profile with no overrides.
func Default(p Profile) Rules { return Resolve(p, Overrides{}) }

// Problem is why knobs cannot be used ("" when they can).
func (k Knobs) Problem() string {
	switch {
	case k.MinImages < 0 || k.MinImages > MaxImagesPerListing:
		return fmt.Sprintf("minImages must be 0 to %d", MaxImagesPerListing)
	case k.MinLongSidePx < 1 || k.MinLongSidePx > CeilingSidePx:
		return fmt.Sprintf("minLongSidePx must be 1 to %d", CeilingSidePx)
	case k.MinShortSidePx < 1 || k.MinShortSidePx > k.MinLongSidePx:
		return "minShortSidePx must be 1 to minLongSidePx"
	case k.MaxMegabytes < 1 || k.MaxMegabytes > CeilingMegabytes:
		return fmt.Sprintf("maxMegabytes must be 1 to %d", CeilingMegabytes)
	}
	return ""
}

// MaxBytes is the file size cap in bytes.
func (r Rules) MaxBytes() int64 { return int64(r.Knobs.MaxMegabytes) << 20 }

// Strict reports whether today's documented standard is in force unchanged.
func (r Rules) Strict() bool { return r.Profile == Strict && len(r.Overridden) == 0 }

// Relaxed reports whether any rule is looser than the strict standard (the staff console warns).
func (r Rules) Relaxed() bool { return !r.Strict() }

// Applies reports whether a knob must be applied to the checks: always under a non-strict profile, and under strict only
// for the knobs staff overrode (so strict stays exactly today's behaviour, including media's own configuration).
func (r Rules) Applies(knob string) bool {
	return r.Profile != Strict || slices.Contains(r.Overridden, knob)
}

// Summary words the rule for sellers ("at least 1 photo, at least 500 px on the longest side").
func (r Rules) Summary() string {
	k := r.Knobs
	parts := []string{}
	switch {
	case k.MinImages == 0:
		parts = append(parts, "photos are optional")
	case k.MinImages == 1:
		parts = append(parts, "at least 1 photo")
	default:
		parts = append(parts, fmt.Sprintf("at least %d photos", k.MinImages))
	}
	parts = append(parts, fmt.Sprintf("at least %d px on the longest side", k.MinLongSidePx))
	if k.MinShortSidePx > 1 {
		parts = append(parts, fmt.Sprintf("at least %d px on the shortest side", k.MinShortSidePx))
	}
	parts = append(parts, fmt.Sprintf("up to %d MB each", k.MaxMegabytes), "JPG, PNG, WebP or AVIF")
	if k.CheckBackground {
		parts = append(parts, "main photo on a plain white background")
	}
	return strings.Join(parts, ", ")
}

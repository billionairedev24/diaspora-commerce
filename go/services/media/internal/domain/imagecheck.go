package domain

import (
	"fmt"
	"math"
	"slices"
	"strconv"
	"strings"
)

// ImageSlot says what an image is used for on a listing; the rules differ per slot (S388).
type ImageSlot string

// Image slots. The library file name slots (MAIN, PT01 to PT08, SWCH) map to these (SlotOfLibrary).
const (
	ImageSlotMain   ImageSlot = "MAIN"   // the main image: the strict one (zoom, white background, fill)
	ImageSlotOther  ImageSlot = "OTHER"  // additional pictures
	ImageSlotSwatch ImageSlot = "SWATCH" // the small picture of a colour or pattern
	ImageSlotLabel  ImageSlot = "LABEL"  // the label photo (ingredients, nutrition panel)
)

// ImageSlots lists the slots a client may name.
func ImageSlots() []ImageSlot {
	return []ImageSlot{ImageSlotMain, ImageSlotOther, ImageSlotSwatch, ImageSlotLabel}
}

// ParseImageSlot reads a slot name (any case); an empty name is OTHER, the least strict slot, so a client that does
// not know slots keeps working. ok is false for a name that is not a slot.
func ParseImageSlot(s string) (slot ImageSlot, ok bool) {
	s = strings.ToUpper(strings.TrimSpace(s))
	if s == "" {
		return ImageSlotOther, true
	}
	slot = ImageSlot(s)
	return slot, slices.Contains(ImageSlots(), slot)
}

// SlotOfLibrary maps an image library file name slot to the image slot its rules follow.
func SlotOfLibrary(s LibrarySlot) ImageSlot {
	switch s {
	case SlotMain:
		return ImageSlotMain
	case SlotSwatch:
		return ImageSlotSwatch
	default:
		return ImageSlotOther
	}
}

// ImageVerdict is the outcome of the image checks: PASS and WARN publish the image, HOLD keeps it private for
// staff, REJECT deletes it.
type ImageVerdict string

// Image verdicts, from least to most severe. INFO is the level of a check that never changes the verdict.
const (
	VerdictInfo   ImageVerdict = "INFO"
	VerdictPass   ImageVerdict = "PASS"
	VerdictWarn   ImageVerdict = "WARN"
	VerdictHold   ImageVerdict = "HOLD"
	VerdictReject ImageVerdict = "REJECT"
)

func (v ImageVerdict) rank() int {
	switch v {
	case VerdictInfo, VerdictPass:
		return 0
	case VerdictWarn:
		return 1
	case VerdictHold:
		return 2
	case VerdictReject:
		return 3
	default:
		return 0
	}
}

// Check codes (seller-facing, translated by Seller Center under sc.img.check.<CODE>).
const (
	CodeMainTooSmall  = "MAIN_TOO_SMALL"
	CodeTooSmall      = "TOO_SMALL"
	CodeLowRes        = "LOW_RES"
	CodeLargerBetter  = "LARGER_BETTER"
	CodeNotWhite      = "NOT_WHITE"
	CodeFillLow       = "FILL_LOW"
	CodeFillNear      = "FILL_OK_NEAR"
	CodeBorder        = "BORDER"
	CodePlaceholder   = "PLACEHOLDER"
	CodeFormat        = "FORMAT"
	CodeFileTooBig    = "FILE_TOO_BIG"
	CodeNotAnImage    = "NOT_AN_IMAGE"
	CodeBlur          = "BLUR"
	CodeAspect        = "ASPECT"
	CodeHeldText      = "HELD_TEXT"
	CodeHeldMismatch  = "HELD_MISMATCH"
	CodeHeldDuplicate = "HELD_DUPLICATE"
	CodeHeldProps     = "HELD_PROPS"
	CodeHeldReview    = "HELD_REVIEW"
	CodeProhibited    = "PROHIBITED"
	CodeFetchFailed   = "FETCH_FAILED"
	CodeFetchPrivate  = "FETCH_PRIVATE"
	CodeNotVerified   = "NOT_VERIFIED"
)

// messages are the plain English texts of the checks (no ids, no jargon). {w} {h} {px} {fill} are filled from the
// check's params.
var messages = map[string]string{
	CodeMainTooSmall: "This photo is too small. The main photo must be at least {px} pixels on its longest side so shoppers can zoom. Yours is {w} by {h}.",
	CodeTooSmall:     "This photo is too small. Use a photo that is at least {min} pixels wide and tall.",
	CodeLowRes:       "This photo is under {px} pixels, so shoppers cannot zoom in. A larger photo sells better.",
	CodeLargerBetter: "A photo of {px} pixels or more on its longest side zooms best.",
	CodeNotWhite: "The main photo needs a plain white background (pure white, RGB 255, 255, 255). " +
		"Retake it or edit the background and upload it again.",
	CodeFillLow:       "The product is too small in the photo. It should fill at least {fill}% of the height or width. Crop closer and upload again.",
	CodeFillNear:      "The product could fill more of the photo. {fill}% or more looks best.",
	CodeBorder:        "Remove the border or frame around the photo.",
	CodePlaceholder:   "This looks like a placeholder, not a photo of your product.",
	CodeFormat:        "Use a JPG, PNG or WebP file.",
	CodeFileTooBig:    "This file is larger than {mb} MB. Save it at a smaller size.",
	CodeNotAnImage:    "This file is not a photo we can read.",
	CodeBlur:          "This photo looks blurry. A sharper photo sells better.",
	CodeAspect:        "This photo is very wide or very tall. Square or close to square works best.",
	CodeHeldText:      "We are checking this photo for text, logos or watermarks. Main photos show the product only. We usually answer within 2 business days.",
	CodeHeldMismatch:  "We are checking that this photo matches your product. We usually answer within 2 business days.",
	CodeHeldDuplicate: "We are checking this photo because it looks like one already used on AfroTransact.",
	CodeHeldProps: "We are checking this photo for props or people. The main photo shows the product only. " +
		"We usually answer within 2 business days.",
	CodeHeldReview:   "We are checking this photo before it goes live. We usually answer within 2 business days.",
	CodeProhibited:   "This photo cannot be used on AfroTransact.",
	CodeFetchFailed:  "We could not get this photo from the link. Check that the link is public and starts with https.",
	CodeFetchPrivate: "This link points to a private address, so we cannot use it.",
	CodeNotVerified:  "We could not check this photo. Upload it again from your device or your image library.",
	CodeMainProps: "The main photo must show the product alone, without props or people. " +
		"Use a photo of the product alone as the main photo.",
	CodeReviewRejected: "A reviewer did not approve this photo. Upload a different one.",
}

// Check is one finding on an image: a code Seller Center translates, its level, the numbers the text needs and an
// English message for API readers.
type Check struct {
	Code    string         `json:"code"`
	Level   ImageVerdict   `json:"level"`
	Params  map[string]any `json:"params,omitempty"`
	Message string         `json:"message"`
}

// NewCheck builds a check with its English message.
func NewCheck(code string, level ImageVerdict, params map[string]any) Check {
	return Check{Code: code, Level: level, Params: params, Message: MessageFor(code, params)}
}

// MessageFor is the English text of a check code with its params filled in ("" for an unknown code).
func MessageFor(code string, params map[string]any) string {
	msg, ok := messages[code]
	if !ok {
		return ""
	}
	for k, v := range params {
		msg = strings.ReplaceAll(msg, "{"+k+"}", fmt.Sprint(v))
	}
	return msg
}

// CheckCodes lists every code that has a seller-facing message (the i18n keys must cover them).
func CheckCodes() []string {
	out := make([]string, 0, len(messages))
	for c := range messages {
		out = append(out, c)
	}
	slices.Sort(out)
	return out
}

// VerdictOf is the most severe level among the checks (PASS when none is more than INFO).
func VerdictOf(checks []Check) ImageVerdict {
	worst := VerdictPass
	for _, c := range checks {
		if c.Level.rank() > worst.rank() {
			worst = c.Level
		}
	}
	return worst
}

// Messages are the plain texts of the checks that matter to the seller (WARN and above), in order.
func Messages(checks []Check) []string {
	out := []string{}
	for _, c := range checks {
		if c.Level.rank() > 0 && c.Message != "" {
			out = append(out, c.Message)
		}
	}
	return out
}

// StatusOfVerdict maps a verdict to the asset status: REJECT is REJECTED, HOLD is FLAGGED (private), PASS and WARN
// are READY.
func StatusOfVerdict(v ImageVerdict) Status {
	switch v {
	case VerdictReject:
		return StatusRejected
	case VerdictHold:
		return StatusFlagged
	case VerdictInfo, VerdictPass, VerdictWarn:
		return StatusReady
	default:
		return StatusReady
	}
}

// ImageMetrics is what the analysis measured on the pixels. The numbers are kept with the asset, so a verdict for
// another slot or after a threshold change needs no second look at the file.
type ImageMetrics struct {
	// Measured is false for an image whose pixels were not analysed (legacy assets, images too large to decode);
	// only the size rules apply to it.
	Measured bool `json:"measured"`
	// RingWhiteRatio is the share of the sampled outer ring whose channels are all at least the white level.
	RingWhiteRatio float64 `json:"ringWhiteRatio"`
	// CornersWhite is true when all four corners are at least the white level.
	CornersWhite bool `json:"cornersWhite"`
	// FillRatio is how much of the frame the product spans (the larger of width and height shares); -1 when the
	// background is not white, so there is nothing to measure against.
	FillRatio float64 `json:"fillRatio"`
	// BorderBand is true when a uniform non-white band runs along three or more edges.
	BorderBand bool `json:"borderBand"`
	// BorderRatio is the thickness of that band as a share of the image's shorter side (0 when none).
	BorderRatio float64 `json:"borderRatio"`
	// Sharpness is the variance of the Laplacian of the product area (a blur heuristic); -1 when not measured.
	Sharpness float64 `json:"sharpness"`
	// Placeholder is true for a blank image or one that matches the placeholder hash list.
	Placeholder bool `json:"placeholder"`
	// FlattenedAlpha and ConvertedCMYK record what the pipeline did to the pixels.
	FlattenedAlpha bool `json:"flattenedAlpha,omitempty"`
	ConvertedCMYK  bool `json:"convertedCmyk,omitempty"`
	// SnappedWhite is true when near-white background pixels were set to pure white in the stored file.
	SnappedWhite bool `json:"snappedWhite,omitempty"`
}

// MainRules are the thresholds of the main image. Every number is configuration (images.main.*) and staff can change
// the starred ones at run time (ImageRulesOverride).
type MainRules struct {
	// MinLongestSide is the zoom rule: a main image below it is rejected (1000). *
	MinLongestSide int `json:"minLongestSide" yaml:"minLongestSide"`
	// RecommendedLongestSide is the size that is recommended, never required (2000). *
	RecommendedLongestSide int `json:"recommendedLongestSide" yaml:"recommendedLongestSide"`
	// MinShortestSide rejects a main image that is too thin (500). *
	MinShortestSide int `json:"minShortestSide" yaml:"minShortestSide"`
	// WhiteChannelMin is the channel value that counts as white in the background test (250: JPEG noise makes
	// 254 common, so exactly 255 is not asked for).
	WhiteChannelMin int `json:"whiteChannelMin" yaml:"whiteChannelMin"`
	// RingFraction is the width of the sampled outer ring as a share of the shorter side (0.02).
	RingFraction float64 `json:"ringFraction" yaml:"ringFraction"`
	// RingPassRatio is the share of ring pixels that must be white (0.95).
	RingPassRatio float64 `json:"ringPassRatio" yaml:"ringPassRatio"`
	// FillReject rejects a product that fills less of the frame (0.75). *
	FillReject float64 `json:"fillReject" yaml:"fillReject"`
	// FillWarn warns below it (0.85). *
	FillWarn float64 `json:"fillWarn" yaml:"fillWarn"`
	// FillTolerance is how far (per channel) from white a pixel must be to count as product (12).
	FillTolerance int `json:"fillTolerance" yaml:"fillTolerance"`
	// BorderMinRatio and BorderMaxRatio bound the thickness of a uniform band that counts as a border or frame,
	// as shares of the shorter side (0.01 and 0.12); a wider uniform area is a background, not a frame.
	BorderMinRatio float64 `json:"borderMinRatio" yaml:"borderMinRatio"`
	BorderMaxRatio float64 `json:"borderMaxRatio" yaml:"borderMaxRatio"`
	// AspectWarn warns when the longer side is more than this many times the shorter (2).
	AspectWarn float64 `json:"aspectWarn" yaml:"aspectWarn"`
	// BlurWarnBelow warns when the Laplacian variance of the product is below it (heuristic, warn only).
	BlurWarnBelow float64 `json:"blurWarnBelow" yaml:"blurWarnBelow"`
	// AllowOnModelApparel lets apparel main images show a person or mannequin. *
	AllowOnModelApparel bool `json:"allowOnModelApparel" yaml:"allowOnModelApparel"`
	// ApparelTypePrefixes name the product types (by code prefix, any case) that count as apparel.
	ApparelTypePrefixes []string `json:"apparelTypePrefixes" yaml:"apparelTypePrefixes"`
	// MismatchHold holds an image whose title and brand mismatch score (0 to 1) reaches it (0.6).
	MismatchHold float64 `json:"mismatchHold" yaml:"mismatchHold"`
	// PlaceholderHashes are dHashes (hex) of known "no image available" pictures; PlaceholderDistance is how close
	// an image must be to one of them (4).
	PlaceholderHashes   []string `json:"placeholderHashes" yaml:"placeholderHashes"`
	PlaceholderDistance int      `json:"placeholderDistance" yaml:"placeholderDistance"`
	// SnapMaxPixels bounds the images whose white background is snapped to pure white (larger ones are only judged).
	SnapMaxPixels int `json:"snapMaxPixels" yaml:"snapMaxPixels"`
}

// OtherRules are the thresholds of the other slots.
type OtherRules struct {
	// WarnLongestSide warns when the longest side is below it (1000: no zoom). *
	WarnLongestSide int `json:"warnLongestSide" yaml:"warnLongestSide"`
	// MinShortestSide rejects a smaller photo (500). *
	MinShortestSide int `json:"minShortestSide" yaml:"minShortestSide"`
	// SwatchMinShortestSide is the smallest swatch (50).
	SwatchMinShortestSide int `json:"swatchMinShortestSide" yaml:"swatchMinShortestSide"`
	// AspectWarn warns when the longer side is more than this many times the shorter (2).
	AspectWarn float64 `json:"aspectWarn" yaml:"aspectWarn"`
}

// ImageRules are the thresholds the checks apply (config images.*, then the staff override on top).
type ImageRules struct {
	// Enforce is the shadow-mode switch (media.image.enforce): when false the verdict and checks are recorded but a
	// REJECT or HOLD from the quality rules does not change the asset (nothing is deleted or held). Prohibited
	// content, file type and size are always enforced.
	Enforce bool `json:"enforce" yaml:"enforce" env:"MEDIA_IMAGE_ENFORCE"`
	// WarnOnly turns the appearance rejects of the main image (not white, product too small, border) into warnings;
	// staff flip it per environment. Size and format rules stay rejects. *
	WarnOnly bool `json:"warnOnly" yaml:"warnOnly" env:"MEDIA_IMAGE_WARN_ONLY"`
	// MaxBytes is the file size cap of product images (8 MB). *
	MaxBytes int64      `json:"maxBytes" yaml:"maxBytes" env:"MEDIA_IMAGE_MAX_BYTES"`
	Main     MainRules  `json:"main" yaml:"main"`
	Other    OtherRules `json:"other" yaml:"other"`
}

// DefaultImageRules are the approved defaults (owner decisions of 2026-11-12).
func DefaultImageRules() ImageRules {
	return ImageRules{
		Enforce:  true,
		WarnOnly: false,
		MaxBytes: 8 << 20,
		Main: MainRules{
			MinLongestSide: 1000, RecommendedLongestSide: 2000, MinShortestSide: 500,
			WhiteChannelMin: 250, RingFraction: 0.02, RingPassRatio: 0.95,
			FillReject: 0.75, FillWarn: 0.85, FillTolerance: 12,
			BorderMinRatio: 0.01, BorderMaxRatio: 0.12, AspectWarn: 2, BlurWarnBelow: 100,
			AllowOnModelApparel: false, ApparelTypePrefixes: []string{"APPAREL", "CLOTHING", "FASHION"},
			MismatchHold: 0.6, PlaceholderDistance: 4, SnapMaxPixels: 16_000_000,
		},
		Other: OtherRules{WarnLongestSide: 1000, MinShortestSide: 500, SwatchMinShortestSide: 50, AspectWarn: 2},
	}
}

// Problem is why a set of rules cannot be used ("" when they can).
func (r ImageRules) Problem() string {
	m, o := r.Main, r.Other
	switch {
	case r.MaxBytes < 100<<10 || r.MaxBytes > 50<<20:
		return "maxBytes must be 100 KB to 50 MB"
	case m.MinLongestSide < 1 || m.MinLongestSide > 10_000:
		return "main.minLongestSide must be 1 to 10000"
	case m.RecommendedLongestSide < m.MinLongestSide || m.RecommendedLongestSide > 10_000:
		return "main.recommendedLongestSide must be at least minLongestSide and at most 10000"
	case m.MinShortestSide < 1 || m.MinShortestSide > m.MinLongestSide:
		return "main.minShortestSide must be 1 to minLongestSide"
	case m.WhiteChannelMin < 200 || m.WhiteChannelMin > 255:
		return "main.whiteChannelMin must be 200 to 255"
	case m.RingFraction <= 0 || m.RingFraction > 0.2:
		return "main.ringFraction must be above 0 and at most 0.2"
	case m.RingPassRatio <= 0.5 || m.RingPassRatio > 1:
		return "main.ringPassRatio must be above 0.5 and at most 1"
	case m.FillReject < 0 || m.FillReject > 1 || m.FillWarn < m.FillReject || m.FillWarn > 1:
		return "fill thresholds must satisfy 0 <= fillReject <= fillWarn <= 1"
	case m.FillTolerance < 1 || m.FillTolerance > 60:
		return "main.fillTolerance must be 1 to 60"
	case m.BorderMinRatio <= 0 || m.BorderMaxRatio < m.BorderMinRatio || m.BorderMaxRatio > 0.5:
		return "border ratios must satisfy 0 < min <= max <= 0.5"
	case m.AspectWarn < 1 || o.AspectWarn < 1:
		return "aspectWarn must be at least 1"
	case m.MismatchHold < 0 || m.MismatchHold > 1:
		return "main.mismatchHold must be 0 to 1"
	case o.MinShortestSide < 1 || o.WarnLongestSide < o.MinShortestSide:
		return "other thresholds must satisfy 1 <= minShortestSide <= warnLongestSide"
	case o.SwatchMinShortestSide < 1:
		return "other.swatchMinShortestSide must be at least 1"
	}
	for _, h := range m.PlaceholderHashes {
		if _, err := strconv.ParseUint(strings.TrimPrefix(strings.ToLower(strings.TrimSpace(h)), "0x"), 16, 64); err != nil {
			return "main.placeholderHashes must be 64-bit hex numbers"
		}
	}
	return ""
}

// IsApparel reports whether a product type code counts as apparel (for on-model main images).
func (m MainRules) IsApparel(productTypeCode string) bool {
	c := strings.ToUpper(strings.TrimSpace(productTypeCode))
	if c == "" {
		return false
	}
	for _, p := range m.ApparelTypePrefixes {
		if p = strings.ToUpper(strings.TrimSpace(p)); p != "" && strings.HasPrefix(c, p) {
			return true
		}
	}
	return false
}

// OnModelAllowed reports whether a main image of this product type may show a person or mannequin.
func (m MainRules) OnModelAllowed(productTypeCode string) bool {
	return m.AllowOnModelApparel && m.IsApparel(productTypeCode)
}

// PlaceholderHashList parses PlaceholderHashes (invalid entries are skipped; Problem rejects them earlier).
func (m MainRules) PlaceholderHashList() []uint64 {
	out := make([]uint64, 0, len(m.PlaceholderHashes))
	for _, h := range m.PlaceholderHashes {
		if v, err := strconv.ParseUint(strings.TrimPrefix(strings.ToLower(strings.TrimSpace(h)), "0x"), 16, 64); err == nil {
			out = append(out, v)
		}
	}
	return out
}

// ImageFacts is what the checks look at.
type ImageFacts struct {
	Slot          ImageSlot
	Width, Height int
	// Metrics are the pixel measurements; nil or not Measured means only the size rules apply.
	Metrics *ImageMetrics
}

// EvaluateImage applies the rules of the facts' slot and returns the findings (possibly none). It is pure: the same
// facts and rules always give the same checks, so a stored image can be judged again for another slot.
func EvaluateImage(r ImageRules, f ImageFacts) []Check {
	checks := []Check{}
	w, h := f.Width, f.Height
	if w <= 0 || h <= 0 {
		return append(checks, NewCheck(CodeNotAnImage, VerdictReject, nil))
	}
	longest, shortest := max(w, h), min(w, h)
	aspect := float64(longest) / float64(shortest)
	switch f.Slot {
	case ImageSlotMain:
		checks = append(checks, mainChecks(r, f, longest, shortest, aspect)...)
	case ImageSlotSwatch:
		if shortest < r.Other.SwatchMinShortestSide {
			checks = append(checks, NewCheck(CodeTooSmall, VerdictReject, map[string]any{"min": r.Other.SwatchMinShortestSide}))
		}
	case ImageSlotOther, ImageSlotLabel:
		checks = append(checks, otherChecks(r, longest, shortest, aspect)...)
	}
	return checks
}

func otherChecks(r ImageRules, longest, shortest int, aspect float64) []Check {
	var checks []Check
	switch {
	case shortest < r.Other.MinShortestSide:
		checks = append(checks, NewCheck(CodeTooSmall, VerdictReject, map[string]any{"min": r.Other.MinShortestSide}))
	case longest < r.Other.WarnLongestSide:
		checks = append(checks, NewCheck(CodeLowRes, VerdictWarn, map[string]any{"px": r.Other.WarnLongestSide}))
	}
	if aspect > r.Other.AspectWarn {
		checks = append(checks, NewCheck(CodeAspect, VerdictWarn, nil))
	}
	return checks
}

func mainChecks(r ImageRules, f ImageFacts, longest, shortest int, aspect float64) []Check {
	m := r.Main
	var checks []Check
	switch {
	case longest < m.MinLongestSide:
		checks = append(checks, NewCheck(CodeMainTooSmall, VerdictReject,
			map[string]any{"w": f.Width, "h": f.Height, "px": m.MinLongestSide}))
	case shortest < m.MinShortestSide:
		checks = append(checks, NewCheck(CodeTooSmall, VerdictReject, map[string]any{"min": m.MinShortestSide}))
	case longest < m.RecommendedLongestSide:
		checks = append(checks, NewCheck(CodeLargerBetter, VerdictInfo, map[string]any{"px": m.RecommendedLongestSide}))
	}
	if aspect > m.AspectWarn {
		checks = append(checks, NewCheck(CodeAspect, VerdictWarn, nil))
	}
	met := f.Metrics
	if met == nil || !met.Measured {
		return checks
	}
	appearance := VerdictReject
	if r.WarnOnly {
		appearance = VerdictWarn
	}
	whiteOK := met.RingWhiteRatio >= m.RingPassRatio && met.CornersWhite
	switch {
	case met.Placeholder:
		return append(checks, NewCheck(CodePlaceholder, VerdictReject, nil))
	case met.BorderBand:
		checks = append(checks, NewCheck(CodeBorder, appearance, nil))
	case !whiteOK:
		checks = append(checks, NewCheck(CodeNotWhite, appearance, nil))
	}
	if !whiteOK || met.FillRatio < 0 {
		return checks
	}
	pct := func(x float64) int { return int(math.Round(x * 100)) }
	switch {
	case met.FillRatio < m.FillReject:
		checks = append(checks, NewCheck(CodeFillLow, appearance, map[string]any{"fill": pct(m.FillWarn), "actual": pct(met.FillRatio)}))
	case met.FillRatio < m.FillWarn:
		checks = append(checks, NewCheck(CodeFillNear, VerdictWarn, map[string]any{"fill": pct(m.FillWarn), "actual": pct(met.FillRatio)}))
	default:
		// Blur only counts when the product fills the frame: a small product in white space has little detail.
		if met.Sharpness >= 0 && met.Sharpness < m.BlurWarnBelow {
			checks = append(checks, NewCheck(CodeBlur, VerdictWarn, nil))
		}
	}
	return checks
}

// MismatchScore threshold and on-model rules applied to the AI's labels. The labels are the ones ai-gateway returns.
const (
	LabelTextOverlay = "TEXT_OVERLAY"
	LabelWatermark   = "WATERMARK"
	LabelPlaceholder = "PLACEHOLDER"
	LabelBorder      = "BORDER"
	LabelProps       = "PROPS"
	LabelMannequin   = "MANNEQUIN"
	LabelNotProduct  = "NOT_PRODUCT"
)

// AIChecks turns the AI hook's labels and mismatch score into held findings for a slot. Text, logos and watermarks
// hold any image; props and people hold only a main image (unless the product type is apparel and the rules allow
// on-model pictures); a mismatch with the title and brand holds a main image. mismatch is nil when the hook was not
// given a title or brand.
func AIChecks(r ImageRules, slot ImageSlot, productTypeCode string, labels []string, mismatch *float64) []Check {
	var checks []Check
	add := func(code string) {
		if !slices.ContainsFunc(checks, func(c Check) bool { return c.Code == code }) {
			checks = append(checks, NewCheck(code, VerdictHold, nil))
		}
	}
	for _, l := range labels {
		switch strings.ToUpper(l) {
		case LabelTextOverlay, LabelWatermark, "CONTACT_INFO", "THIRD_PARTY_LOGO":
			add(CodeHeldText)
		case LabelProps, LabelMannequin:
			if slot == ImageSlotMain && !r.Main.OnModelAllowed(productTypeCode) {
				add(CodeHeldProps)
			}
		case LabelPlaceholder, LabelBorder, LabelNotProduct, "NOT_A_PRODUCT", "MISLEADING":
			add(CodeHeldReview)
		}
	}
	if slot == ImageSlotMain && mismatch != nil && *mismatch >= r.Main.MismatchHold {
		add(CodeHeldMismatch)
	}
	return checks
}

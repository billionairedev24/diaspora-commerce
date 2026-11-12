package domain_test

import (
	"slices"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

func codesOf(checks []domain.Check) []string {
	out := []string{}
	for _, c := range checks {
		out = append(out, c.Code)
	}
	return out
}

func measured(m domain.ImageMetrics) *domain.ImageMetrics {
	m.Measured = true
	return &m
}

var cleanMetrics = domain.ImageMetrics{RingWhiteRatio: 1, CornersWhite: true, FillRatio: 0.9, Sharpness: 500}

func TestMainImageSizeRuleUsesTheLongestSideFromConfig(t *testing.T) {
	r := domain.DefaultImageRules()
	for _, c := range []struct {
		w, h    int
		verdict domain.ImageVerdict
		code    string
	}{
		{999, 999, domain.VerdictReject, "MAIN_TOO_SMALL"},
		{1000, 1000, domain.VerdictPass, ""}, // the old rule asked for both edges; the longest side is enough
		{1000, 600, domain.VerdictPass, ""},
		{1000, 400, domain.VerdictReject, "TOO_SMALL"}, // shortest side under 500
		{2400, 1200, domain.VerdictPass, ""},
		{0, 0, domain.VerdictReject, "NOT_AN_IMAGE"},
	} {
		got := domain.EvaluateImage(r, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: c.w, Height: c.h, Metrics: measured(cleanMetrics)})
		if v := domain.VerdictOf(got); v != c.verdict || (c.code != "" && !slices.Contains(codesOf(got), c.code)) {
			t.Fatalf("%dx%d: %s %v", c.w, c.h, v, codesOf(got))
		}
	}
	// Nothing is hard-coded: a different configuration moves the line.
	r.Main.MinLongestSide = 1500
	if v := domain.VerdictOf(domain.EvaluateImage(r, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 1200, Height: 1200,
		Metrics: measured(cleanMetrics)})); v != domain.VerdictReject {
		t.Fatalf("1200 px under a 1500 px rule: %s", v)
	}
	got := domain.EvaluateImage(r, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 1200, Height: 1200})
	if !strings.Contains(got[0].Message, "at least 1500 pixels") || !strings.Contains(got[0].Message, "Yours is 1200 by 1200") {
		t.Fatalf("message: %q", got[0].Message)
	}
}

func TestRecommendedSizeIsOnlyInformation(t *testing.T) {
	got := domain.EvaluateImage(domain.DefaultImageRules(), domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 1500, Height: 1500,
		Metrics: measured(cleanMetrics)})
	if len(got) != 1 || got[0].Code != "LARGER_BETTER" || domain.VerdictOf(got) != domain.VerdictPass || len(domain.Messages(got)) != 0 {
		t.Fatalf("%+v", got)
	}
}

func TestBackgroundFillBlurAndBorderRules(t *testing.T) {
	r := domain.DefaultImageRules()
	facts := func(m domain.ImageMetrics) domain.ImageFacts {
		return domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 2000, Height: 2000, Metrics: measured(m)}
	}
	with := func(f func(*domain.ImageMetrics)) domain.ImageMetrics {
		m := cleanMetrics
		f(&m)
		return m
	}
	for _, c := range []struct {
		name    string
		m       domain.ImageMetrics
		verdict domain.ImageVerdict
		code    string
	}{
		{"clean", cleanMetrics, domain.VerdictPass, ""},
		{"ring 94 percent white", with(func(m *domain.ImageMetrics) { m.RingWhiteRatio = 0.94 }), domain.VerdictReject, "NOT_WHITE"},
		{"ring exactly 95 percent", with(func(m *domain.ImageMetrics) { m.RingWhiteRatio = 0.95 }), domain.VerdictPass, ""},
		{"a corner is not white", with(func(m *domain.ImageMetrics) { m.CornersWhite = false }), domain.VerdictReject, "NOT_WHITE"},
		{"fill 74 percent", with(func(m *domain.ImageMetrics) { m.FillRatio = 0.74 }), domain.VerdictReject, "FILL_LOW"},
		{"fill 75 percent warns", with(func(m *domain.ImageMetrics) { m.FillRatio = 0.75 }), domain.VerdictWarn, "FILL_OK_NEAR"},
		{"fill 84 percent warns", with(func(m *domain.ImageMetrics) { m.FillRatio = 0.84 }), domain.VerdictWarn, "FILL_OK_NEAR"},
		{"fill 85 percent", with(func(m *domain.ImageMetrics) { m.FillRatio = 0.85 }), domain.VerdictPass, ""},
		{"border", with(func(m *domain.ImageMetrics) { m.BorderBand, m.RingWhiteRatio = true, 0 }), domain.VerdictReject, "BORDER"},
		{"placeholder", with(func(m *domain.ImageMetrics) { m.Placeholder = true }), domain.VerdictReject, "PLACEHOLDER"},
		{"blur warns", with(func(m *domain.ImageMetrics) { m.Sharpness = 10 }), domain.VerdictWarn, "BLUR"},
		{"blur is not judged when the product is small", with(func(m *domain.ImageMetrics) { m.Sharpness, m.FillRatio = 10, 0.8 }), domain.VerdictWarn, "FILL_OK_NEAR"},
	} {
		got := domain.EvaluateImage(r, facts(c.m))
		if v := domain.VerdictOf(got); v != c.verdict || (c.code != "" && !slices.Contains(codesOf(got), c.code)) {
			t.Fatalf("%s: %s %v", c.name, v, codesOf(got))
		}
		if c.name == "blur is not judged when the product is small" && slices.Contains(codesOf(got), "BLUR") {
			t.Fatalf("%s: %v", c.name, codesOf(got))
		}
	}
	// "Warn only" turns the three appearance rejects into warnings, not the size rule.
	r.WarnOnly = true
	for _, m := range []domain.ImageMetrics{with(func(m *domain.ImageMetrics) { m.RingWhiteRatio = 0.1 }),
		with(func(m *domain.ImageMetrics) { m.FillRatio = 0.5 }), with(func(m *domain.ImageMetrics) { m.BorderBand = true })} {
		if v := domain.VerdictOf(domain.EvaluateImage(r, facts(m))); v != domain.VerdictWarn {
			t.Fatalf("warn only: %s", v)
		}
	}
	if v := domain.VerdictOf(domain.EvaluateImage(r, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 500, Height: 500, Metrics: measured(cleanMetrics)})); v != domain.VerdictReject {
		t.Fatalf("size stays a reject: %s", v)
	}
	// Unmeasured images (legacy) are judged on their size only.
	r.WarnOnly = false
	if got := domain.EvaluateImage(r, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 2000, Height: 2000}); len(got) != 0 {
		t.Fatalf("unmeasured: %v", codesOf(got))
	}
}

func TestOtherSlotRules(t *testing.T) {
	r := domain.DefaultImageRules()
	for _, c := range []struct {
		slot    domain.ImageSlot
		w, h    int
		verdict domain.ImageVerdict
		code    string
	}{
		{domain.ImageSlotOther, 1600, 1600, domain.VerdictPass, ""},
		{domain.ImageSlotOther, 999, 999, domain.VerdictWarn, "LOW_RES"},
		{domain.ImageSlotOther, 499, 1200, domain.VerdictReject, "TOO_SMALL"},
		{domain.ImageSlotOther, 3000, 1000, domain.VerdictWarn, "ASPECT"},
		{domain.ImageSlotLabel, 700, 700, domain.VerdictWarn, "LOW_RES"},
		{domain.ImageSlotSwatch, 60, 60, domain.VerdictPass, ""},
		{domain.ImageSlotSwatch, 40, 40, domain.VerdictReject, "TOO_SMALL"},
	} {
		got := domain.EvaluateImage(r, domain.ImageFacts{Slot: c.slot, Width: c.w, Height: c.h})
		if v := domain.VerdictOf(got); v != c.verdict || (c.code != "" && !slices.Contains(codesOf(got), c.code)) {
			t.Fatalf("%s %dx%d: %s %v", c.slot, c.w, c.h, v, codesOf(got))
		}
	}
}

func TestSlotParsingAndLibraryMapping(t *testing.T) {
	for in, want := range map[string]domain.ImageSlot{"main": domain.ImageSlotMain, " Other ": domain.ImageSlotOther, "": domain.ImageSlotOther,
		"SWATCH": domain.ImageSlotSwatch, "label": domain.ImageSlotLabel} {
		if got, ok := domain.ParseImageSlot(in); !ok || got != want {
			t.Fatalf("%q: %s %v", in, got, ok)
		}
	}
	if _, ok := domain.ParseImageSlot("hero"); ok {
		t.Fatal("unknown slot")
	}
	if domain.SlotOfLibrary(domain.SlotMain) != domain.ImageSlotMain || domain.SlotOfLibrary(domain.OtherSlot(3)) != domain.ImageSlotOther ||
		domain.SlotOfLibrary(domain.SlotSwatch) != domain.ImageSlotSwatch {
		t.Fatal("library slot mapping")
	}
}

func TestStatusMapping(t *testing.T) {
	for v, want := range map[domain.ImageVerdict]domain.Status{domain.VerdictPass: domain.StatusReady, domain.VerdictWarn: domain.StatusReady,
		domain.VerdictHold: domain.StatusFlagged, domain.VerdictReject: domain.StatusRejected} {
		if got := domain.StatusOfVerdict(v); got != want {
			t.Fatalf("%s: %s", v, got)
		}
	}
	checks := []domain.Check{domain.NewCheck("BLUR", domain.VerdictWarn, nil), domain.NewCheck("HELD_TEXT", domain.VerdictHold, nil)}
	if domain.VerdictOf(checks) != domain.VerdictHold || domain.VerdictOf(nil) != domain.VerdictPass {
		t.Fatal("the most severe level wins")
	}
}

func TestAIChecksBySlot(t *testing.T) {
	r := domain.DefaultImageRules()
	score := func(v float64) *float64 { return &v }
	for _, c := range []struct {
		name     string
		slot     domain.ImageSlot
		ptype    string
		labels   []string
		mismatch *float64
		want     []string
	}{
		{"text", domain.ImageSlotMain, "", []string{"TEXT_OVERLAY"}, nil, []string{"HELD_TEXT"}},
		{"watermark and text are one finding", domain.ImageSlotOther, "", []string{"WATERMARK", "TEXT_OVERLAY", "CONTACT_INFO"}, nil, []string{"HELD_TEXT"}},
		{"props on main", domain.ImageSlotMain, "FOOD_SPICE", []string{"PROPS"}, nil, []string{"HELD_PROPS"}},
		{"props on other", domain.ImageSlotOther, "", []string{"PROPS", "MANNEQUIN"}, nil, nil},
		{"unlabelled", domain.ImageSlotMain, "", []string{"LOW_QUALITY"}, nil, nil},
		{"mismatch at the threshold", domain.ImageSlotMain, "", nil, score(0.6), []string{"HELD_MISMATCH"}},
		{"mismatch below", domain.ImageSlotMain, "", nil, score(0.59), nil},
		{"mismatch on other", domain.ImageSlotOther, "", nil, score(1), nil},
		{"placeholder, border, not product", domain.ImageSlotMain, "", []string{"PLACEHOLDER", "BORDER", "NOT_PRODUCT"}, nil, []string{"HELD_REVIEW"}},
	} {
		if got := codesOf(domain.AIChecks(r, c.slot, c.ptype, c.labels, c.mismatch)); !slices.Equal(got, c.want) && (len(got) != 0 || len(c.want) != 0) {
			t.Fatalf("%s: %v, want %v", c.name, got, c.want)
		}
	}
	r.Main.AllowOnModelApparel = true
	if got := domain.AIChecks(r, domain.ImageSlotMain, "apparel_dress", []string{"MANNEQUIN"}, nil); len(got) != 0 {
		t.Fatalf("on-model apparel: %v", got)
	}
}

func TestRulesOverrideAndValidation(t *testing.T) {
	base := domain.DefaultImageRules()
	if p := base.Problem(); p != "" {
		t.Fatal(p)
	}
	yes, px, fill := true, 1200, 0.8
	got := domain.ImageRulesOverride{WarnOnly: &yes, MinLongestSide: &px, FillReject: &fill}.Apply(base)
	if !got.WarnOnly || got.Main.MinLongestSide != 1200 || got.Main.FillReject != 0.8 || got.Main.FillWarn != 0.85 || base.Main.MinLongestSide != 1000 {
		t.Fatalf("%+v", got)
	}
	bad := []func(*domain.ImageRules){
		func(r *domain.ImageRules) { r.Main.FillWarn = 0.5 },
		func(r *domain.ImageRules) { r.Main.MinLongestSide = 0 },
		func(r *domain.ImageRules) { r.Main.RecommendedLongestSide = 500 },
		func(r *domain.ImageRules) { r.MaxBytes = 10 },
		func(r *domain.ImageRules) { r.Main.PlaceholderHashes = []string{"zz"} },
		func(r *domain.ImageRules) { r.Other.WarnLongestSide = 100 },
		func(r *domain.ImageRules) { r.Main.RingPassRatio = 0.3 },
	}
	for i, f := range bad {
		r := domain.DefaultImageRules()
		f(&r)
		if r.Problem() == "" {
			t.Fatalf("case %d should be refused", i)
		}
	}
}

func TestEveryCodeHasAMessageWithoutJargon(t *testing.T) {
	want := []string{"MAIN_TOO_SMALL", "TOO_SMALL", "LOW_RES", "NOT_WHITE", "FILL_LOW", "FILL_OK_NEAR", "BORDER", "PLACEHOLDER", "FORMAT",
		"FILE_TOO_BIG", "NOT_AN_IMAGE", "BLUR", "ASPECT", "HELD_TEXT", "HELD_MISMATCH", "HELD_DUPLICATE", "FETCH_FAILED", "FETCH_PRIVATE"}
	have := domain.CheckCodes()
	for _, c := range want {
		if !slices.Contains(have, c) {
			t.Fatalf("section 6.5 code %s has no message", c)
		}
	}
	if got := domain.MessageFor("NOT_WHITE", nil); !strings.Contains(got, "pure white, RGB 255, 255, 255") {
		t.Fatalf("%q", got)
	}
	if domain.MessageFor("NOPE", nil) != "" {
		t.Fatal("unknown code")
	}
}

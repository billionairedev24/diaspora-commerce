package app

import (
	"context"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func usePictureProfile(t *testing.T, p picturerules.Profile) {
	t.Helper()
	UsePictureRules(picturerules.Static(picturerules.Default(p)))
	t.Cleanup(func() { UsePictureRules(picturerules.Static(picturerules.Default(picturerules.Strict))) })
}

// Matrix: which product cards the home, rails and storefront show, per profile.
func TestEligibleCardMatrixPerProfile(t *testing.T) {
	cases := []struct {
		name  string
		image *string
		stock bool
		want  map[picturerules.Profile]bool
	}{
		{"real photo in stock", strp("https://media.example/p/a.jpg"), true, map[picturerules.Profile]bool{picturerules.Strict: true, picturerules.Startup: true, picturerules.Testing: true}},
		{"avif photo in stock", strp("https://media.example/p/a.avif"), true, map[picturerules.Profile]bool{picturerules.Strict: true, picturerules.Startup: true, picturerules.Testing: true}},
		{"no photo in stock", nil, true, map[picturerules.Profile]bool{picturerules.Strict: false, picturerules.Startup: false, picturerules.Testing: true}},
		{"empty photo in stock", strp(""), true, map[picturerules.Profile]bool{picturerules.Strict: false, picturerules.Startup: false, picturerules.Testing: true}},
		{"svg icon in stock", strp("https://media.example/p/icon.svg"), true, map[picturerules.Profile]bool{picturerules.Strict: false, picturerules.Startup: false, picturerules.Testing: true}},
		{"placeholder in stock", strp("https://media.example/placeholder.png"), true, map[picturerules.Profile]bool{picturerules.Strict: false, picturerules.Startup: false, picturerules.Testing: true}},
		{"real photo out of stock", strp("https://media.example/p/a.jpg"), false, map[picturerules.Profile]bool{picturerules.Strict: false, picturerules.Startup: false, picturerules.Testing: false}},
		{"no photo out of stock", nil, false, map[picturerules.Profile]bool{picturerules.Strict: false, picturerules.Startup: false, picturerules.Testing: false}},
	}
	for _, p := range picturerules.Profiles() {
		usePictureProfile(t, p)
		for _, c := range cases {
			if got := eligibleCard(domain.ProductSummary{InStock: c.stock, ImageURL: c.image}); got != c.want[p] {
				t.Errorf("%s: %s: eligible=%v want %v", p, c.name, got, c.want[p])
			}
		}
	}
}

func TestWithoutASourceTheStrictRuleApplies(t *testing.T) {
	UsePictureRules(picturerules.Static(picturerules.Default(picturerules.Strict)))
	if eligibleCard(domain.ProductSummary{InStock: true}) {
		t.Fatal("no photo must not be eligible under strict")
	}
}

func TestEditorialImagesStayStrictUnderEveryProfile(t *testing.T) {
	usePictureProfile(t, picturerules.Testing)
	if realPhoto(strp("https://x/hero.svg")) || realPhoto(nil) {
		t.Fatal("a hero slide or tile without a real photo is never shown, whatever the profile")
	}
}

func TestSellerDraftNeedsAsManyImagesAsTheRulesAsk(t *testing.T) {
	d := domain.ProductDraft{Title: "Handmade Ankara tote bag", CategoryID: "cat", Variants: []domain.VariantInput{{}}}
	if e := d.ValidateMin(1); e["images"] != "Add at least one image." {
		t.Fatalf("one: %v", e)
	}
	if e := d.ValidateMin(0); e["images"] != "" {
		t.Fatalf("testing: no photo is fine: %v", e)
	}
	if e := d.ValidateMin(3); e["images"] != "Add at least 3 images." {
		t.Fatalf("three: %v", e)
	}
	if e := d.Validate(); e["images"] != "Add at least one image." {
		t.Fatalf("default stays today's rule: %v", e)
	}
}

func TestCheckPictureRulesBody(t *testing.T) {
	profile, over, problems := checkPictureRulesBody(map[string]any{"profile": " Startup ", "reason": "  Launch  ",
		"overrides": map[string]any{"minImages": float64(2), "allowPlaceholders": true, "maxMegabytes": nil}})
	if len(problems) != 0 || profile != "startup" || over["minImages"] != int64(2) || over["allowPlaceholders"] != true {
		t.Fatalf("%v %v %v", profile, over, problems)
	}
	_, _, problems = checkPictureRulesBody(map[string]any{"profile": "x", "reason": "ab", "overrides": map[string]any{"minImages": 1.5, "nope": 1.0}})
	for _, k := range []string{"profile", "reason", "minImages", "nope"} {
		if problems[k] == "" {
			t.Errorf("missing problem for %s: %v", k, problems)
		}
	}
	_, _, problems = checkPictureRulesBody(map[string]any{"profile": "testing", "reason": strings.Repeat("é", 301)})
	if problems["reason"] == "" {
		t.Error("301 characters is too long")
	}
}

func TestSellerPictureRulesStatesTheActiveRule(t *testing.T) {
	usePictureProfile(t, picturerules.Startup)
	v := SellerPictureRules(context.Background(), nil)
	s, _ := v["summary"].(string)
	if !strings.Contains(s, "at least 1 photo") || !strings.Contains(s, "at least 500 px") || v["profile"] != "startup" {
		t.Fatalf("%v", v)
	}
}

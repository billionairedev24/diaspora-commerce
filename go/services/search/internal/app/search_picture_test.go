package app_test

import (
	"context"
	"io"
	"log/slog"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// The storefront photo requirement of eligibleOnly follows the picture rules profile.
func TestEligibleOnlyKeepsThePhotoRequirementOnlyWhereTheRulesAskForIt(t *testing.T) {
	cases := []struct {
		name         string
		rules        picturerules.Rules
		eligibleOnly bool
		wantAnyPhoto bool
	}{
		{"strict requires a photo", picturerules.Default(picturerules.Strict), true, false},
		{"startup requires a photo", picturerules.Default(picturerules.Startup), true, false},
		{"testing lists products without a photo", picturerules.Default(picturerules.Testing), true, true},
		{"not eligibleOnly is untouched", picturerules.Default(picturerules.Testing), false, false},
	}
	off := false
	for _, c := range cases {
		idx := &fakeIndex{}
		s := app.NewProductSearcher(idx, nil, slog.New(slog.NewTextHandler(io.Discard, nil)),
			app.WithPictureRules(picturerules.Static(c.rules)))
		if _, err := s.Search(context.Background(), domain.Query{Text: "rice", Market: "us", EligibleOnly: c.eligibleOnly}); err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		if idx.gotQuery.AnyPhoto != c.wantAnyPhoto {
			t.Errorf("%s: AnyPhoto=%v want %v", c.name, idx.gotQuery.AnyPhoto, c.wantAnyPhoto)
		}
	}
	// The knob alone does it too: startup with the storefront requirement switched off.
	r := picturerules.Resolve(picturerules.Startup, picturerules.Overrides{RequireRealPhotoForStorefront: &off})
	idx := &fakeIndex{}
	s := app.NewProductSearcher(idx, nil, slog.New(slog.NewTextHandler(io.Discard, nil)), app.WithPictureRules(picturerules.Static(r)))
	_, _ = s.Search(context.Background(), domain.Query{Text: "rice", Market: "us", EligibleOnly: true})
	if !idx.gotQuery.AnyPhoto {
		t.Error("the override must drop the photo filter")
	}
}

func TestWithoutPictureRulesTheStrictFilterApplies(t *testing.T) {
	idx := &fakeIndex{}
	s := app.NewProductSearcher(idx, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	_, _ = s.Search(context.Background(), domain.Query{Text: "rice", Market: "us", EligibleOnly: true})
	if idx.gotQuery.AnyPhoto {
		t.Error("no rules source means the strict photo filter")
	}
}

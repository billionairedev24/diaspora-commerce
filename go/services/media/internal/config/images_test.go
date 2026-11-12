package config_test

import (
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// S388: every image threshold comes from configuration; each environment states the approved values and is valid.
func TestImageThresholdsAreConfigurationInEveryEnvironment(t *testing.T) {
	want := domain.DefaultImageRules()
	for _, env := range []string{pkgconfig.EnvLocal, pkgconfig.EnvDev, pkgconfig.EnvUAT, pkgconfig.EnvProd} {
		c := config.Default()
		if err := pkgconfig.NewLoaderFor(media.Files, env, func(string) (string, bool) { return "", false }).Load(&c); err != nil {
			t.Fatal(err)
		}
		if p := c.Images.Problem(); p != "" {
			t.Fatalf("%s: %s", env, p)
		}
		m := c.Images.Main
		if !c.Images.Enforce || c.Images.WarnOnly || c.Images.MaxBytes != 8<<20 || m.MinLongestSide != 1000 || m.RecommendedLongestSide != 2000 ||
			m.MinShortestSide != 500 || m.FillReject != 0.75 || m.FillWarn != 0.85 || m.AllowOnModelApparel ||
			c.Images.Other.WarnLongestSide != 1000 || c.Images.Other.MinShortestSide != 500 {
			t.Errorf("%s: approved defaults not in force: %+v", env, c.Images)
		}
		if m.RingPassRatio != want.Main.RingPassRatio || m.RingFraction != want.Main.RingFraction || m.WhiteChannelMin != want.Main.WhiteChannelMin {
			t.Errorf("%s: background rule %+v", env, m)
		}
	}
}

func TestImageEnforceAndWarnOnlyFollowEnvironmentVariables(t *testing.T) {
	c := config.Default()
	env := map[string]string{"MEDIA_IMAGE_ENFORCE": "false", "MEDIA_IMAGE_WARN_ONLY": "true", "MEDIA_IMAGE_MAX_BYTES": "10485760"}
	lookup := func(k string) (string, bool) { v, ok := env[k]; return v, ok }
	if err := pkgconfig.NewLoaderFor(media.Files, pkgconfig.EnvDev, lookup).Load(&c); err != nil {
		t.Fatal(err)
	}
	if c.Images.Enforce || !c.Images.WarnOnly || c.Images.MaxBytes != 10<<20 {
		t.Fatalf("%+v", c.Images)
	}
}

func TestInvalidImageThresholdsStopTheService(t *testing.T) {
	c := config.Default()
	c.Images.Main.FillWarn = 0.1
	if err := c.Validate(pkgconfig.EnvDev, func(string) string { return "" }); err == nil {
		t.Fatal("fillWarn below fillReject must be refused at start")
	}
}

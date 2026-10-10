package domain_test

import (
	"math"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
)

func TestContrastMatchesWCAGReferenceValues(t *testing.T) {
	cases := []struct {
		a, b string
		want float64
	}{
		{"#000000", "#FFFFFF", 21},
		{"#FFFFFF", "#FFFFFF", 1},
		{"#767676", "#FFFFFF", 4.54}, // the classic AA grey
		{"#067457", "#FFFFFF", 5.77}, // AfroTransact green on white (L = 0.1321)
	}
	for _, c := range cases {
		if got := domain.Contrast(c.a, c.b); math.Abs(got-c.want) > 0.02 {
			t.Errorf("contrast(%s,%s) = %.2f, want %.2f", c.a, c.b, got, c.want)
		}
	}
}

func TestOKLCHRoundTrip(t *testing.T) {
	for _, h := range []string{"#FFD400", "#067457", "#C0392B", "#1E3A8A", "#808080"} {
		if got := domain.FromOKLCH(domain.ToOKLCH(h)); got != h {
			t.Errorf("round trip %s -> %s", h, got)
		}
	}
}

func TestRampIsLightToDark(t *testing.T) {
	r := domain.Ramp("#067457")
	if len(r) != 10 || r[0].Step != 50 || r[9].Step != 900 {
		t.Fatalf("ramp shape %+v", r)
	}
	for i := 1; i < len(r); i++ {
		if domain.Luminance(r[i].Hex) >= domain.Luminance(r[i-1].Hex) {
			t.Fatalf("ramp not monotonic at %d: %s >= %s", r[i].Step, r[i].Hex, r[i-1].Hex)
		}
	}
}

func TestFitReachesTarget(t *testing.T) {
	fixed := domain.Fit("#FFD400", "#FFFFFF", 4.5) // gold is far too light for text on white
	if domain.Contrast(fixed, "#FFFFFF") < 4.5 {
		t.Fatalf("fit did not reach 4.5: %s", fixed)
	}
}

func TestVetBlocksUnreadableButtons(t *testing.T) {
	ok, err := domain.Vet(domain.ThemeInput{Primary: "#067457", Accent: "#FFD400"})
	if err != nil || !ok.OK {
		t.Fatalf("brand theme should pass: %+v %v", ok, err)
	}
	if ok.Tokens["--store-on-accent"] != "#000000" || ok.Tokens["--store-on-primary"] != "#FFFFFF" {
		t.Fatalf("on-colours: %+v", ok.Tokens)
	}
	bad, err := domain.Vet(domain.ThemeInput{Primary: "#777777", Accent: "#FFD400", Text: "#AAAAAA"})
	if err != nil {
		t.Fatal(err)
	}
	if bad.OK {
		t.Fatal("light grey body text must block publishing")
	}
	if _, err := domain.Vet(domain.ThemeInput{Primary: "teal", Accent: "#FFF"}); err == nil {
		t.Fatal("named colours are rejected")
	}
}

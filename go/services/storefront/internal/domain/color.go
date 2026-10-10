// Package domain holds storefront themes, layouts and provider pages. color.go is the server-side twin of the
// Theme Studio engine (design/at-theme.js): OKLCH ramps and WCAG 2.2 contrast vetting, so a theme that fails
// contrast can never be published regardless of what the client sends.
package domain

import (
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
)

// ErrInvalidColor is returned for colours that are not #RGB or #RRGGBB.
var ErrInvalidColor = errors.New("invalid colour")

type rgb [3]float64

// ParseHex parses #RGB or #RRGGBB.
func ParseHex(h string) (rgb, error) {
	h = strings.TrimPrefix(strings.TrimSpace(h), "#")
	if len(h) == 3 {
		h = string([]byte{h[0], h[0], h[1], h[1], h[2], h[2]})
	}
	if len(h) != 6 {
		return rgb{}, fmt.Errorf("%w: %q", ErrInvalidColor, h)
	}
	n, err := strconv.ParseUint(h, 16, 32)
	if err != nil {
		return rgb{}, fmt.Errorf("%w: %q", ErrInvalidColor, h)
	}
	return rgb{float64(n >> 16 & 255), float64(n >> 8 & 255), float64(n & 255)}, nil
}

// NormalizeHex returns #RRGGBB upper-case.
func NormalizeHex(h string) (string, error) {
	c, err := ParseHex(h)
	if err != nil {
		return "", err
	}
	return toHex(c), nil
}

func toHex(c rgb) string {
	b := func(v float64) int { return int(math.Round(clamp(v, 255))) }
	return fmt.Sprintf("#%02X%02X%02X", b(c[0]), b(c[1]), b(c[2]))
}

func clamp(x, hi float64) float64 { return math.Min(hi, math.Max(0, x)) }

func cube(x float64) float64 { return x * x * x }

func s2l(c float64) float64 {
	c /= 255
	if c <= 0.04045 {
		return c / 12.92
	}
	return math.Pow((c+0.055)/1.055, 2.4)
}

func l2s(c float64) float64 {
	if c <= 0.0031308 {
		return 255 * 12.92 * c
	}
	return 255 * (1.055*math.Pow(c, 1/2.4) - 0.055)
}

// Luminance is the WCAG relative luminance.
func Luminance(hex string) float64 {
	c, err := ParseHex(hex)
	if err != nil {
		return 0
	}
	return 0.2126*s2l(c[0]) + 0.7152*s2l(c[1]) + 0.0722*s2l(c[2])
}

// Contrast is the WCAG 2.2 contrast ratio between two colours (1–21).
func Contrast(a, b string) float64 {
	la, lb := Luminance(a), Luminance(b)
	return (math.Max(la, lb) + 0.05) / (math.Min(la, lb) + 0.05)
}

// OKLCH is a colour in the OKLCH space (H in radians).
type OKLCH struct{ L, C, H float64 }

// ToOKLCH converts a hex colour.
func ToOKLCH(hex string) OKLCH {
	c, _ := ParseHex(hex)
	r, g, b := s2l(c[0]), s2l(c[1]), s2l(c[2])
	l := math.Cbrt(0.4122214708*r + 0.5363325363*g + 0.0514459929*b)
	m := math.Cbrt(0.2119034982*r + 0.6806995451*g + 0.1073969566*b)
	s := math.Cbrt(0.0883024619*r + 0.2817188376*g + 0.6299787005*b)
	L := 0.2104542553*l + 0.793617785*m - 0.0040720468*s
	A := 1.9779984951*l - 2.428592205*m + 0.4505937099*s
	B := 0.0259040371*l + 0.7827717662*m - 0.808675766*s
	return OKLCH{L: L, C: math.Hypot(A, B), H: math.Atan2(B, A)}
}

// FromOKLCH converts back to hex, reducing chroma until the colour is inside the sRGB gamut.
func FromOKLCH(o OKLCH) string {
	for c := o.C; c >= 0; c -= 0.005 {
		A, B := c*math.Cos(o.H), c*math.Sin(o.H)
		l := cube(o.L + 0.3963377774*A + 0.2158037573*B)
		m := cube(o.L - 0.1055613458*A - 0.0638541728*B)
		s := cube(o.L - 0.0894841775*A - 1.291485548*B)
		lin := [3]float64{
			4.0767416621*l - 3.3077115913*m + 0.2309699292*s,
			-1.2684380046*l + 2.6097574011*m - 0.3413193965*s,
			-0.0041960863*l - 0.7034186147*m + 1.707614701*s,
		}
		inGamut := true
		for _, v := range lin {
			if v < -0.0005 || v > 1.0005 {
				inGamut = false
				break
			}
		}
		if inGamut {
			return toHex(rgb{l2s(clamp(lin[0], 1)), l2s(clamp(lin[1], 1)), l2s(clamp(lin[2], 1))})
		}
	}
	g := l2s(clamp(o.L, 1))
	return toHex(rgb{g, g, g})
}

// RampStep is one shade of a generated ramp.
type RampStep struct {
	Step int    `json:"step"`
	Hex  string `json:"hex"`
}

var (
	steps     = []int{50, 100, 200, 300, 400, 500, 600, 700, 800, 900}
	lightness = []float64{0.975, 0.945, 0.885, 0.815, 0.735, 0.655, 0.565, 0.475, 0.38, 0.285}
)

// Ramp builds a 50–900 shade ramp with the colour's hue.
func Ramp(hex string) []RampStep {
	o := ToOKLCH(hex)
	out := make([]RampStep, len(steps))
	for i, step := range steps {
		chroma := o.C
		switch {
		case i < 2:
			chroma *= 0.35
		case i < 4:
			chroma *= 0.75
		}
		out[i] = RampStep{Step: step, Hex: FromOKLCH(OKLCH{L: lightness[i], C: chroma, H: o.H})}
	}
	return out
}

// Fit moves lightness until the colour reaches target contrast against bg.
func Fit(hex, bg string, target float64) string {
	if Contrast(hex, bg) >= target {
		return strings.ToUpper(hex)
	}
	o := ToOKLCH(hex)
	darker := Luminance(bg) > 0.18
	for i := 1; i <= 100; i++ {
		delta := float64(i) * 0.008
		if darker {
			delta = -delta
		}
		c := FromOKLCH(OKLCH{L: clamp(o.L+delta, 1), C: o.C, H: o.H})
		if Contrast(c, bg) >= target {
			return c
		}
	}
	if darker {
		return "#000000"
	}
	return "#FFFFFF"
}

// OnColor picks black or white text for a background, whichever contrasts more.
func OnColor(bg string) string {
	if Contrast("#000000", bg) >= Contrast("#FFFFFF", bg) {
		return "#000000"
	}
	return "#FFFFFF"
}

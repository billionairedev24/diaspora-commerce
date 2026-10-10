package domain_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestCountSmsSegments(t *testing.T) {
	cases := []struct {
		name, body      string
		encoding        string
		units, segments int
	}{
		{"empty", "", domain.EncodingGSM7, 0, 0},
		{"plain", "AfroTransact: your code is 123456.", domain.EncodingGSM7, 34, 1},
		{"extension chars count two", "Total €5 [x]", domain.EncodingGSM7, 15, 1},
		{"160 septets fit one segment", strings.Repeat("a", 160), domain.EncodingGSM7, 160, 1},
		{"161 septets need two", strings.Repeat("a", 161), domain.EncodingGSM7, 161, 2},
		{"306 septets need two", strings.Repeat("a", 306), domain.EncodingGSM7, 306, 2},
		{"307 septets need three", strings.Repeat("a", 307), domain.EncodingGSM7, 307, 3},
		{"middle dot forces UCS-2", "Sat, Oct 17 · 10:00 AM", domain.EncodingUCS2, 22, 1},
		{"71 UCS-2 units need two", "–" + strings.Repeat("a", 70), domain.EncodingUCS2, 71, 2},
		{"emoji is two UTF-16 units", "Hi 👋", domain.EncodingUCS2, 5, 1},
	}
	for _, c := range cases {
		got := domain.CountSmsSegments(c.body)
		if got.Encoding != c.encoding || got.Units != c.units || got.Segments != c.segments {
			t.Errorf("%s: %+v", c.name, got)
		}
	}
}

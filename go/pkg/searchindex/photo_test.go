package searchindex_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
)

func TestPhotoOK(t *testing.T) {
	cases := []struct {
		name string
		url  string
		want bool
	}{
		{"https photo", "https://cdn.afrotransact.com/p/jollof.jpg", true},
		{"http photo", "http://cdn.example.com/a.webp", true},
		{"site relative", "/media/p/1.jpg", true},
		{"empty", "", false},
		{"blank", "   ", false},
		{"data uri", "data:image/png;base64,AAAA", false},
		{"svg", "https://cdn.example.com/p/logo.svg", false},
		{"svg upper case", "https://cdn.example.com/p/LOGO.SVG", false},
		{"ico", "https://cdn.example.com/favicon.ico", false},
		{"placeholder path", "https://cdn.example.com/img/placeholder-300.png", false},
		{"no-image path", "https://cdn.example.com/img/no-image.jpg", false},
		{"icons folder", "https://cdn.example.com/icons/box.png", false},
		{"coming soon", "https://cdn.example.com/coming-soon.jpg", false},
		{"relative without slash", "images/p.jpg", false},
		{"javascript scheme", "javascript:alert(1)", false},
		{"svg with query", "https://cdn.example.com/p/x.svg?v=2", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := searchindex.PhotoOK(c.url); got != c.want {
				t.Fatalf("PhotoOK(%q) = %v, want %v", c.url, got, c.want)
			}
		})
	}
}

func TestMeiliDeriveSetsPhotoOKFromImage(t *testing.T) {
	m := map[string]any{"in_stock": true, "image_url": "https://cdn.example.com/a.jpg"}
	searchindex.MeiliDerive(m)
	if m["photo_ok"] != true {
		t.Fatalf("photo_ok = %v, want true", m["photo_ok"])
	}
	m = map[string]any{"in_stock": true, "image_url": "https://cdn.example.com/a.svg"}
	searchindex.MeiliDerive(m)
	if m["photo_ok"] != false {
		t.Fatalf("photo_ok = %v, want false for svg", m["photo_ok"])
	}
}

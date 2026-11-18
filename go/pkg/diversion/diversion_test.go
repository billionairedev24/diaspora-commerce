package diversion_test

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/diversion"
)

type vector struct {
	Text   string `json:"text"`
	Reject bool   `json:"reject"`
	Kind   string `json:"kind"`
	Strict bool   `json:"strict"`
}

func TestGoldenVectors(t *testing.T) {
	raw, err := os.ReadFile("../../../contracts/diversion/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Vectors []vector `json:"vectors"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Vectors) < 100 {
		t.Fatalf("only %d vectors", len(f.Vectors))
	}
	d := diversion.Default()
	for _, v := range f.Vectors {
		got := d.Scan(v.Text, diversion.Options{Strict: v.Strict})
		if v.Reject && len(got) == 0 {
			t.Errorf("%q (strict=%v): want a %s finding, got none", v.Text, v.Strict, v.Kind)
			continue
		}
		if !v.Reject && len(got) > 0 {
			t.Errorf("%q (strict=%v): want clean, got %+v", v.Text, v.Strict, got)
			continue
		}
		if v.Reject {
			ok := false
			for _, g := range got {
				if string(g.Kind) == v.Kind {
					ok = true
				}
			}
			if !ok {
				t.Errorf("%q (strict=%v): want kind %s, got %+v", v.Text, v.Strict, v.Kind, got)
			}
		}
	}
}

func TestMessageNamesWhatToRemove(t *testing.T) {
	got := diversion.Message(diversion.Default().Scan("Call 713-555-1234 or visit mystore.com", diversion.Options{}))
	for _, want := range []string{"web address", "phone number", "AfroTransact messages"} {
		if !strings.Contains(got, want) {
			t.Errorf("message %q lacks %q", got, want)
		}
	}
	if diversion.Message(nil) != "" {
		t.Error("clean text has no message")
	}
}

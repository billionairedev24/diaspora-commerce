package searchindex_test

import (
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
)

func TestEmbeddingText(t *testing.T) {
	got := searchindex.EmbeddingText("Gari Ijebu,  Fine", "Mama Tee", "Groceries › Pantry › Flours & swallow", "")
	if got != "Gari Ijebu, Fine. Mama Tee. Groceries / Pantry / Flours & swallow" {
		t.Fatalf("text %q", got)
	}
	long := searchindex.EmbeddingText("Égusi", "", "", strings.Repeat("é", 3000))
	if len(long) > 2000 || !utf8.ValidString(long) {
		t.Fatalf("long text: %d bytes, valid UTF-8 %v", len(long), utf8.ValidString(long))
	}
	if searchindex.EmbeddingText("", " ", "", "") != "" {
		t.Fatal("empty input gives empty text")
	}
}

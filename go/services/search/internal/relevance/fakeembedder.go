package relevance

import (
	"context"
	"fmt"
	"hash/fnv"
	"math"
	"strings"
	"unicode"
)

// FakeEmbedder is a deterministic stand-in for ai-gateway's embeddings (tests never call a model): a hashed bag of
// words plus hand-written concept dimensions (relevance/concepts.yaml), normalised to unit length.
type FakeEmbedder struct {
	dims      int
	stopwords map[string]bool
	concepts  map[string][]string // word → concepts
}

// conceptWeight is how much a concept counts against a single word.
const conceptWeight = 3

// LoadFakeEmbedder reads the concept lexicon.
func LoadFakeEmbedder(path string, dims int) (*FakeEmbedder, error) {
	var doc struct {
		Stopwords []string            `yaml:"stopwords"`
		Concepts  map[string][]string `yaml:"concepts"`
	}
	if err := readYAML(path, &doc); err != nil {
		return nil, err
	}
	if len(doc.Concepts) == 0 {
		return nil, fmt.Errorf("%s: no concepts", path)
	}
	f := &FakeEmbedder{dims: dims, stopwords: map[string]bool{}, concepts: map[string][]string{}}
	for _, w := range doc.Stopwords {
		f.stopwords[w] = true
	}
	for c, words := range doc.Concepts {
		for _, w := range words {
			f.concepts[stem(w)] = append(f.concepts[stem(w)], c)
		}
	}
	return f, nil
}

// Embed returns the text's unit vector (an all-zero vector when no word is left after stop words).
func (f *FakeEmbedder) Embed(_ context.Context, text string) ([]float32, error) {
	v := make([]float64, f.dims)
	words := strings.FieldsFunc(strings.ToLower(text), func(r rune) bool { return !unicode.IsLetter(r) })
	for _, w := range words {
		if f.stopwords[w] || len(w) < 2 {
			continue
		}
		w = stem(w)
		v[f.bucket("w:"+w)]++
		for _, c := range f.concepts[w] {
			v[f.bucket("c:"+c)] += conceptWeight
		}
	}
	var norm float64
	for _, x := range v {
		norm += x * x
	}
	out := make([]float32, f.dims)
	if norm == 0 {
		out[0] = 1 // cosine similarity is undefined for a zero vector
		return out, nil
	}
	norm = math.Sqrt(norm)
	for i, x := range v {
		out[i] = float32(x / norm)
	}
	return out, nil
}

func (f *FakeEmbedder) bucket(s string) int {
	h := fnv.New32a()
	_, _ = h.Write([]byte(s))
	return int(h.Sum32() % uint32(f.dims)) //nolint:gosec // dims is a small positive constant
}

// stem drops a plural "s" so "snacks" and "snack" share a dimension.
func stem(w string) string {
	if len(w) > 3 && strings.HasSuffix(w, "s") && !strings.HasSuffix(w, "ss") {
		return w[:len(w)-1]
	}
	return w
}

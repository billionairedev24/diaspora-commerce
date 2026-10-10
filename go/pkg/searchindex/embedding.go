package searchindex

import (
	"strings"
	"unicode/utf8"
)

// EmbeddingDimensions is the size of the embedding dense_vector in the mapping; ai-gateway's EMBEDDING_DIMENSIONS
// must match it. Vectors of another size are not indexed (the mapping would reject the document).
const EmbeddingDimensions = 1536

// EmbeddingText is the text the indexer embeds for a product (ai-gateway purpose search-document): what a shopper
// would describe — title, brand, category path and description — joined into one passage, at most maxEmbeddingChars.
func EmbeddingText(title, brand, categoryPath, description string) string {
	parts := make([]string, 0, 4)
	for _, p := range []string{title, brand, strings.ReplaceAll(categoryPath, " › ", " / "), description} {
		if p = strings.Join(strings.Fields(p), " "); p != "" {
			parts = append(parts, p)
		}
	}
	text := strings.Join(parts, ". ")
	if len(text) > maxEmbeddingChars {
		text = text[:maxEmbeddingChars]
		// Do not cut a UTF-8 sequence in half.
		for !utf8.ValidString(text) {
			text = text[:len(text)-1]
		}
	}
	return text
}

// maxEmbeddingChars keeps document text well inside ai-gateway's input limit.
const maxEmbeddingChars = 2000

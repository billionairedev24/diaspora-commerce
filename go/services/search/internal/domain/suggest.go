package domain

import (
	"strings"
	"unicode"
)

// CompletePhrase lowercases a title and keeps its first n words, e.g. "Jollof Seasoning Blend, 8 oz" → "jollof seasoning".
func CompletePhrase(title string, n int) string {
	fields := strings.FieldsFunc(strings.ToLower(title), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r) && r != '-' && r != '\''
	})
	if len(fields) == 0 {
		return ""
	}
	if n > len(fields) {
		n = len(fields)
	}
	return strings.Join(fields[:n], " ")
}

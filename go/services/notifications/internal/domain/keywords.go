package domain

import "strings"

// Keyword is an inbound SMS compliance keyword class.
type Keyword string

// Keyword classes (CTIA / Twilio Advanced Opt-Out).
const (
	KeywordNone   Keyword = ""
	KeywordStop   Keyword = "stop"
	KeywordStart  Keyword = "start"
	KeywordHelp   Keyword = "help"
	stopKeywords          = "STOP END CANCEL UNSUBSCRIBE QUIT STOPALL OPTOUT REVOKE"
	startKeywords         = "START UNSTOP YES"
	helpKeywords          = "HELP INFO"
)

// ClassifyKeyword maps an inbound SMS body to a keyword class. Only a message that is exactly the keyword
// (ignoring case, whitespace and trailing punctuation) counts, as carriers require.
func ClassifyKeyword(body string) Keyword {
	w := strings.ToUpper(strings.Trim(strings.TrimSpace(body), ".!? "))
	if w == "" || strings.ContainsAny(w, " \t\n") {
		return KeywordNone
	}
	switch {
	case contains(stopKeywords, w):
		return KeywordStop
	case contains(startKeywords, w):
		return KeywordStart
	case contains(helpKeywords, w):
		return KeywordHelp
	default:
		return KeywordNone
	}
}

func contains(list, w string) bool {
	for _, k := range strings.Fields(list) {
		if k == w {
			return true
		}
	}
	return false
}

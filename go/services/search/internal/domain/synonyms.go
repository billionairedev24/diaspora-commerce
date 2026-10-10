package domain

import (
	"errors"
	"regexp"
	"strings"
)

// SynonymRule is one line of equivalent terms in the diaspora synonym set, e.g. "gari, garri, cassava flakes".
type SynonymRule struct {
	ID       string `json:"id" yaml:"id"`
	Synonyms string `json:"synonyms" yaml:"synonyms"`
}

// SynonymSet is the configurable synonym set loaded into the index settings.
type SynonymSet struct {
	Name  string        `yaml:"set"`
	Rules []SynonymRule `yaml:"rules"`
}

// ErrInvalidSynonym is returned for malformed rules.
var ErrInvalidSynonym = errors.New("invalid synonym rule")

var ruleID = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)

// Normalize trims and lowercases the rule and checks it has at least two distinct terms.
func (r SynonymRule) Normalize() (SynonymRule, error) {
	r.ID = strings.TrimSpace(strings.ToLower(r.ID))
	if !ruleID.MatchString(r.ID) {
		return r, errors.Join(ErrInvalidSynonym, errors.New("id must be lowercase letters, digits and dashes"))
	}
	if strings.Contains(r.Synonyms, "=>") {
		return r, errors.Join(ErrInvalidSynonym, errors.New("only equivalent synonyms (a, b, c) are allowed"))
	}
	seen := map[string]bool{}
	terms := make([]string, 0, 4)
	for _, t := range strings.Split(r.Synonyms, ",") {
		t = strings.Join(strings.Fields(strings.ToLower(t)), " ")
		if t == "" || seen[t] {
			continue
		}
		if len(t) > 60 {
			return r, errors.Join(ErrInvalidSynonym, errors.New("terms are at most 60 characters"))
		}
		seen[t] = true
		terms = append(terms, t)
	}
	if len(terms) < 2 || len(terms) > 20 {
		return r, errors.Join(ErrInvalidSynonym, errors.New("a rule needs 2 to 20 distinct terms"))
	}
	r.Synonyms = strings.Join(terms, ", ")
	return r, nil
}

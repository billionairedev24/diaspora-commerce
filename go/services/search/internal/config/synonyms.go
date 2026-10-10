package config

import (
	"errors"
	"fmt"
	"io/fs"
	"os"

	"gopkg.in/yaml.v3"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// LoadSynonyms reads the synonym set from path when set (SYNONYMS_FILE, e.g. a ConfigMap mount), else from
// config/synonyms.yaml in fsys.
func LoadSynonyms(fsys fs.FS, path string) (domain.SynonymSet, error) {
	var (
		raw []byte
		err error
	)
	if path != "" {
		raw, err = os.ReadFile(path) //nolint:gosec // operator-provided configuration path
	} else {
		raw, err = fs.ReadFile(fsys, "config/synonyms.yaml")
	}
	if err != nil {
		return domain.SynonymSet{}, fmt.Errorf("read synonyms: %w", err)
	}
	return ParseSynonyms(raw)
}

// ParseSynonyms decodes and validates a synonym set document.
func ParseSynonyms(raw []byte) (domain.SynonymSet, error) {
	var set domain.SynonymSet
	if err := yaml.Unmarshal(raw, &set); err != nil {
		return domain.SynonymSet{}, fmt.Errorf("parse synonyms: %w", err)
	}
	if set.Name == "" {
		return domain.SynonymSet{}, errors.New("synonyms: set name is required")
	}
	seen := map[string]bool{}
	for i, r := range set.Rules {
		n, err := r.Normalize()
		if err != nil {
			return domain.SynonymSet{}, fmt.Errorf("synonyms rule %q: %w", r.ID, err)
		}
		if seen[n.ID] {
			return domain.SynonymSet{}, fmt.Errorf("synonyms: duplicate rule id %q", n.ID)
		}
		seen[n.ID] = true
		set.Rules[i] = n
	}
	return set, nil
}

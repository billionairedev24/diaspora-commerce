package domain

import (
	"strings"
	"unicode"
)

// Department and community naming for the nav, tiles, footer and sitemap (S419, S420). Everything is derived from the
// live taxonomy; nothing here lists the departments or communities that exist.

// DepartmentKey is the stable slug of a catalog department name: "Groceries & Food" is "groceries-food".
func DepartmentKey(name string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(strings.TrimSpace(name)) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			if dash && b.Len() > 0 {
				b.WriteByte('-')
			}
			b.WriteRune(r)
			dash = false
		} else {
			dash = true
		}
	}
	return b.String()
}

// shortLabelLimit is the longest full name kept as is; a longer "A & B" name is shortened to "A".
const shortLabelLimit = 14

// DerivedShortLabel shortens a catalog name for the bar: "Groceries & Food" becomes "Groceries", "Beauty & Personal Care"
// becomes "Beauty", while "Baby & Kids" stays. It is the fallback when neither staff nor the i18n table has a label.
func DerivedShortLabel(name string) string {
	name = strings.TrimSpace(name)
	if len([]rune(name)) <= shortLabelLimit {
		return name
	}
	for _, sep := range []string{" & ", " and ", ", "} {
		if head, _, ok := strings.Cut(name, sep); ok && strings.TrimSpace(head) != "" {
			return strings.TrimSpace(head)
		}
	}
	return name
}

// DepartmentLabels resolves the display label of a department in every locale: a staff override first, then the i18n
// table by key, then the label derived from the live name (English only; other locales fall back to it).
func DepartmentLabels(name string, staff map[string]LocaleLabels) LocaleLabels {
	key := DepartmentKey(name)
	out := LocaleLabels{"en": DerivedShortLabel(name)}
	for l, v := range deptLabels[key] {
		out[l] = v
	}
	for l, v := range staff[key] {
		if strings.TrimSpace(v) != "" {
			out[l] = strings.TrimSpace(v)
		}
	}
	return out
}

// CommunityEntry is one community registry row (content): the canonical key, labels, aliases and an optional
// editorial image.
type CommunityEntry struct {
	Key       string       `json:"key"`
	Labels    LocaleLabels `json:"labels"`
	Aliases   []string     `json:"aliases"`
	ImageURL  *string      `json:"imageUrl"`
	UpdatedAt string       `json:"updatedAt,omitempty"`
}

// CommunityResolver maps raw product values ("nigerian", "Nigerian ", an alias) onto one canonical key.
type CommunityResolver interface {
	// Resolve returns the canonical key and its entry (nil when the value is not registered).
	Resolve(raw string) (key string, entry *CommunityEntry)
	// Labels are the labels of a canonical key (the registry's, else the value title-cased).
	Labels(key string) LocaleLabels
}

type communityResolverImpl struct {
	byAlias map[string]*CommunityEntry
}

var _ CommunityResolver = (*communityResolverImpl)(nil)

// NewCommunityResolver builds the resolver from the registry.
func NewCommunityResolver(entries []CommunityEntry) CommunityResolver {
	r := &communityResolverImpl{byAlias: map[string]*CommunityEntry{}}
	for i := range entries {
		e := &entries[i]
		r.byAlias[normaliseCommunity(e.Key)] = e
		for _, a := range e.Aliases {
			if n := normaliseCommunity(a); n != "" {
				if _, taken := r.byAlias[n]; !taken {
					r.byAlias[n] = e
				}
			}
		}
		for _, l := range e.Labels {
			if n := normaliseCommunity(l); n != "" {
				if _, taken := r.byAlias[n]; !taken {
					r.byAlias[n] = e
				}
			}
		}
	}
	return r
}

// normaliseCommunity lower-cases, trims and turns separators into single dashes, so "Nigerian ", "nigerian" and
// "NIGERIAN" are one community.
func normaliseCommunity(s string) string { return DepartmentKey(s) }

func (r *communityResolverImpl) Resolve(raw string) (string, *CommunityEntry) {
	n := normaliseCommunity(raw)
	if n == "" {
		return "", nil
	}
	if e, ok := r.byAlias[n]; ok {
		return e.Key, e
	}
	return n, nil
}

func (r *communityResolverImpl) Labels(key string) LocaleLabels {
	if e, ok := r.byAlias[key]; ok && len(e.Labels) > 0 {
		out := LocaleLabels{}
		for l, v := range e.Labels {
			out[l] = v
		}
		if out["en"] == "" {
			out["en"] = titleWords(key)
		}
		return out
	}
	return LocaleLabels{"en": titleWords(key)}
}

func titleWords(key string) string {
	parts := strings.Split(key, "-")
	for i, p := range parts {
		if p != "" {
			parts[i] = strings.ToUpper(p[:1]) + p[1:]
		}
	}
	return strings.Join(parts, " ")
}

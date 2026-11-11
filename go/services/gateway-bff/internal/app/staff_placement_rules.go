package app

import (
	"fmt"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Home tile rules and hero schedules in the placement editor. A tile has up to four cells; each is a fixed image and
// link, or a rule ("Top rated in Fashion") that the home page resolves to a real product. The editor shows these
// fields for the home_tiles slot only (create: when that slot is chosen; edit: when the placement is a tile).

const (
	slotHomeTiles    = "home_tiles"
	slotHeroCarousel = "hero_carousel"
	tileCellCount    = 4

	ruleFixed      = ""
	ruleTopRated   = "top-rated"
	ruleNew        = "new"
	ruleBestseller = "bestsellers"
	ruleSearch     = "search"
	ruleFastest    = "fastest"
	rulePicked     = "picked"
	ruleNear       = "near-you"
	ruleDeliver    = "deliverable-fast"
)

var tileRuleOptions = []domain.FieldOption{
	{Value: ruleTopRated, Label: "Top rated in a department"},
	{Value: ruleNew, Label: "New in a department"},
	{Value: ruleBestseller, Label: "Best sellers in a department"},
	{Value: ruleSearch, Label: "Search results for words"},
	{Value: ruleFastest, Label: "Fastest delivery from AfroTransact"},
	{Value: ruleNear, Label: "Ships from near the shopper (their city, state or region)"},
	{Value: ruleDeliver, Label: "Delivered within 2 days to the shopper's ZIP"},
	{Value: rulePicked, Label: "Picked products (ATINs)"},
}

// tileDepartments are the storefront's department bar entries a rule can be scoped to.
var tileDepartments = []domain.FieldOption{
	{Value: "Groceries", Label: "Groceries"}, {Value: "Fashion", Label: "Fashion"}, {Value: "Beauty", Label: "Beauty"},
	{Value: "Home & Art", Label: "Home & Art"}, {Value: "Books", Label: "Books"}, {Value: "Health", Label: "Health"}, {Value: "Event & party", Label: "Event & party"},
}

func cellKey(n int, field string) string { return fmt.Sprintf("cell%d_%s", n, field) }

func tileSection(n int) string {
	return fmt.Sprintf("Tile cell %d (home tiles and shop by occasion)", n)
}

const heroSection = "Hero slide schedule (hero carousel only)"

func onlyFor(f domain.FormField, slot string, create bool) domain.FormField {
	if create {
		f.ShowWhen = &domain.FieldCondition{Key: "slot", In: []string{slot}}
	}
	return f
}

func inSection(f domain.FormField, s string) domain.FormField { f.Section = s; return f }

// cellDefaults reads a stored tile cell back into the editor's fields.
func cellDefaults(cell jobj) map[string]string {
	out := map[string]string{"label": jstr(cell, "label"), "href": jstr(cell, "href"), "imageUrl": jstr(cell, "imageUrl")}
	src, ok := cell["source"].(map[string]any)
	if !ok {
		return out
	}
	out["department"], out["query"] = jstr(src, "department"), jstr(src, "query")
	switch jstr(src, "kind") {
	case "department-bestsellers":
		out["rule"] = ruleBestseller
	case "fulfilled-fastest":
		out["rule"] = ruleFastest
	case "near-you":
		out["rule"] = ruleNear
	case "deliverable-fast":
		out["rule"] = ruleDeliver
	case "atins":
		out["rule"] = rulePicked
		if list, ok := src["atins"].([]any); ok {
			names := make([]string, 0, len(list))
			for _, a := range list {
				if s, ok := a.(string); ok {
					names = append(names, s)
				}
			}
			out["atins"] = strings.Join(names, ", ")
		}
	default:
		switch jstr(src, "sort") {
		case domain.SortRating:
			out["rule"] = ruleTopRated
		case domain.SortNewest:
			out["rule"] = ruleNew
		default:
			out["rule"] = ruleSearch
		}
	}
	return out
}

// tileFields are the four cells' fields. prev (the stored payload, or nil) fills the values; create adds the slot
// condition so they only show once "Home tiles" is chosen.
func tileFields(prev jobj, create bool) []domain.FormField {
	var cells []any
	if prev != nil {
		if p, ok := prev["payload"].(map[string]any); ok {
			cells, _ = p["cells"].([]any)
		}
	}
	var out []domain.FormField
	for n := 1; n <= tileCellCount; n++ {
		def := map[string]string{}
		if n <= len(cells) {
			if c, ok := cells[n-1].(map[string]any); ok {
				def = cellDefaults(c)
			}
		}
		sec := tileSection(n)
		add := func(f domain.FormField) {
			out = append(out, showFor(inSection(f, sec), create, slotHomeTiles, blockPrefix+domain.BlockOccasions))
		}
		rule := withOptions(ff(cellKey(n, "rule"), "Product rule (leave empty for a fixed image)", "select", false), tileRuleOptions)
		rule.Help = "A rule shows the best in-stock product with a real photo; its image, name and link are filled in for you."
		add(withValue(rule, def["rule"]))
		add(withValue(withOptions(ff(cellKey(n, "department"), "Department (for department rules)", "select", false), tileDepartments), def["department"]))
		add(withValue(withMax(ff(cellKey(n, "query"), "Search words (for search rules)", "text", false), 120), def["query"]))
		add(withValue(withMax(ff(cellKey(n, "atins"), "Product ATINs, comma separated (for picked products)", "text", false), 240), def["atins"]))
		add(withValue(withMax(ff(cellKey(n, "label"), "Fixed cell: label", "text", false), 40), def["label"]))
		add(withValue(withPattern(ff(cellKey(n, "href"), "Fixed cell: link", "text", false), `^(/|https://)`, "A storefront path starting with / or an https:// address"), def["href"]))
		add(withValue(withPattern(ff(cellKey(n, "imageUrl"), "Fixed cell: image address", "text", false), `^https://`, "An https:// image address"), def["imageUrl"]))
	}
	return out
}

// heroFields are the optional per-slide schedule fields.
func heroFields(prev jobj, create bool) []domain.FormField {
	return []domain.FormField{
		onlyFor(inSection(withValue(ff("slideStartsAt", "Show the slide from", "datetime", false), jstr(prev, "payload.startsAt")), heroSection), slotHeroCarousel, create),
		onlyFor(inSection(withValue(ff("slideEndsAt", "Hide the slide after", "datetime", false), jstr(prev, "payload.endsAt")), heroSection), slotHeroCarousel, create),
	}
}

// ruleSource builds a cell's source from the editor's fields; the error is the message for the offending field.
func ruleSource(v jobj, n int) (map[string]any, string, string) {
	dept, query := vs(v, cellKey(n, "department")), vs(v, cellKey(n, "query"))
	switch rule := vs(v, cellKey(n, "rule")); rule {
	case ruleTopRated, ruleNew, ruleBestseller:
		if dept == "" {
			return nil, cellKey(n, "department"), "Choose a department"
		}
		switch rule {
		case ruleTopRated:
			return map[string]any{"kind": "search", "department": dept, "sort": domain.SortRating}, "", ""
		case ruleNew:
			return map[string]any{"kind": "search", "department": dept, "sort": domain.SortNewest}, "", ""
		}
		return map[string]any{"kind": "department-bestsellers", "department": dept}, "", ""
	case ruleSearch:
		if query == "" {
			return nil, cellKey(n, "query"), "Enter the search words"
		}
		src := map[string]any{"kind": "search", "query": query}
		if dept != "" {
			src["department"] = dept
		}
		return src, "", ""
	case ruleFastest:
		return map[string]any{"kind": "fulfilled-fastest"}, "", ""
	case ruleNear:
		src := map[string]any{"kind": "near-you"}
		if dept != "" {
			src["department"] = dept
		}
		return src, "", ""
	case ruleDeliver:
		src := map[string]any{"kind": "deliverable-fast", "maxDays": 2}
		if dept != "" {
			src["department"] = dept
		}
		return src, "", ""
	case rulePicked:
		var atins []string
		for _, a := range strings.Split(vs(v, cellKey(n, "atins")), ",") {
			if a = strings.ToUpper(strings.TrimSpace(a)); a != "" {
				atins = append(atins, a)
			}
		}
		if len(atins) == 0 {
			return nil, cellKey(n, "atins"), "Enter at least one ATIN"
		}
		if len(atins) > 24 {
			return nil, cellKey(n, "atins"), "At most 24 products"
		}
		return map[string]any{"kind": "atins", "atins": atins}, "", ""
	default:
		return nil, "", ""
	}
}

// tileCells turns the editor's fields into the placement's cells, with per-field problems. A cell with nothing filled in
// is left out; at least one cell is needed.
func tileCells(v jobj) ([]any, map[string]string) {
	errs := map[string]string{}
	cells := []any{}
	for n := 1; n <= tileCellCount; n++ {
		if src, key, msg := ruleSource(v, n); key != "" {
			errs[key] = msg
			continue
		} else if src != nil {
			cells = append(cells, map[string]any{"source": src})
			continue
		}
		label, href, img := vs(v, cellKey(n, "label")), vs(v, cellKey(n, "href")), vs(v, cellKey(n, "imageUrl"))
		switch {
		case label == "" && href == "" && img == "":
		case label == "":
			errs[cellKey(n, "label")] = "Required for a fixed cell"
		case href == "":
			errs[cellKey(n, "href")] = "Required for a fixed cell"
		default:
			cell := map[string]any{"label": label, "href": href, "imageUrl": nil}
			if img != "" {
				cell["imageUrl"] = img
			}
			cells = append(cells, cell)
		}
	}
	if len(cells) == 0 && len(errs) == 0 {
		errs[cellKey(1, "rule")] = "Set up at least one cell"
	}
	return cells, errs
}

// validateHeroWindow checks the optional slide window reads as RFC 3339 and ends after it starts.
func validateHeroWindow(v jobj) map[string]string {
	errs := map[string]string{}
	var from, to time.Time
	var okFrom, okTo bool
	if s := vs(v, "slideStartsAt"); s != "" {
		t, err := time.Parse(time.RFC3339, s)
		if err != nil {
			errs["slideStartsAt"] = "Enter a date and time"
		}
		from, okFrom = t, err == nil
	}
	if s := vs(v, "slideEndsAt"); s != "" {
		t, err := time.Parse(time.RFC3339, s)
		if err != nil {
			errs["slideEndsAt"] = "Enter a date and time"
		}
		to, okTo = t, err == nil
	}
	if okFrom && okTo && !to.After(from) {
		errs["slideEndsAt"] = "Must be after the start"
	}
	return errs
}

// applySlotFields writes the slot-specific fields onto the payload and returns problems with them.
func applySlotFields(slot string, v jobj, p jobj) map[string]string {
	switch slot {
	case slotHomeTiles:
		if _, stored := p["cells"]; stored && !hasTileInput(v) {
			return nil // a save that did not carry the cell fields keeps the stored cells
		}
		cells, errs := tileCells(v)
		if len(errs) == 0 {
			p["cells"] = cells
		}
		if cta := vs(v, "cta"); cta != "" {
			p["cta"] = cta
		}
		return errs
	case slotHeroCarousel:
		errs := validateHeroWindow(v)
		if len(errs) > 0 {
			return errs
		}
		for key, field := range map[string]string{"startsAt": "slideStartsAt", "endsAt": "slideEndsAt"} {
			if s := vs(v, field); s != "" {
				p[key] = s
			} else {
				delete(p, key)
			}
		}
	}
	return nil
}

func hasTileInput(v jobj) bool {
	for n := 1; n <= tileCellCount; n++ {
		for _, f := range []string{"rule", "label", "href", "imageUrl", "department", "query", "atins"} {
			if vs(v, cellKey(n, f)) != "" {
				return true
			}
		}
	}
	return false
}

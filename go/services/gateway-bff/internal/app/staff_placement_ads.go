package app

import (
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The home composition in the placement editor. Besides the older slots, staff can place four paid modules (brand
// banner, sponsored products rail, 2-3 up image ads, advertorial) and the rule-based home modules. A module's place on
// the page is its priority (higher first); the "Home page layout" list orders them without opening each one.
//
// The Slot choice of the create form carries the module type for home modules ("home_block:deals"); the stored slot is
// always the real one (home_block) with the type in its payload.

const (
	slotBrandBanner   = "home_brand_banner"
	slotSponsored     = "home_sponsored_products"
	slotAdRow         = "home_ad_row"
	slotAdvertorial   = "home_advertorial"
	slotHomeBlock     = "home_block"
	slotProductRow    = "home_product_row"
	slotDisplayBanner = "display_banner"
	adCount           = 3
	blockPrefix       = slotHomeBlock + ":"
)

// homeBlockKinds are the rule-based modules: wire type and the label staff see.
var homeBlockKinds = []struct{ typ, label string }{
	{domain.BlockDeals, "Deals of the day (countdown, discount from compare-at price)"},
	{domain.BlockOccasions, "Shop by occasion"},
	{domain.BlockCommunity, "Community spotlight"},
	{domain.BlockSeller, "Seller spotlight (top rated sellers near the shopper)"},
	{domain.BlockServices, "Services near you (hidden where no provider lists)"},
	{domain.BlockPreorders, "Pre-order near you (position of the pre-order band)"},
	{domain.BlockMembership, "Membership / gift cards band"},
	{domain.BlockSignIn, "Sign-in band (signed-out visitors only)"},
	{domain.BlockBasedOnOrders, "Based on your orders (signed-in shoppers)"},
	{domain.BlockRecentlyViewed, "Recently viewed (this browser)"},
}

// slotChoices are the create form's Slot options: the older slots, then the paid modules, then the home modules.
func slotChoices() []domain.FieldOption {
	out := []domain.FieldOption{
		{Value: slotHeroCarousel, Label: "Hero carousel"}, {Value: slotDisplayBanner, Label: "Display banner (sponsored)"},
		{Value: slotHomeTiles, Label: "Home tiles"}, {Value: slotProductRow, Label: "Home product row"},
		{Value: "announcement_bar", Label: "Announcement bar"},
		{Value: slotBrandBanner, Label: "Sponsored brand banner (paid, full width)"},
		{Value: slotSponsored, Label: "Sponsored products rail (paid)"},
		{Value: slotAdRow, Label: "Display ad row, 2 or 3 image ads (paid)"},
		{Value: slotAdvertorial, Label: "Advertorial: From our partners (paid)"},
	}
	for _, k := range homeBlockKinds {
		out = append(out, domain.FieldOption{Value: blockPrefix + k.typ, Label: "Home module: " + k.label})
	}
	return out
}

func blockValues(types ...string) []string {
	out := make([]string, 0, len(types))
	for _, t := range types {
		out = append(out, blockPrefix+t)
	}
	return out
}

// splitSlot separates the create form's slot choice into the stored slot and the home module type.
func splitSlot(choice string) (slot, blockType string) {
	if t, ok := strings.CutPrefix(choice, blockPrefix); ok {
		return slotHomeBlock, t
	}
	return choice, ""
}

// showFor limits a field to the given slot choices on the create form; on edit the form is built for the one record.
func showFor(f domain.FormField, create bool, choices ...string) domain.FormField {
	if create {
		f.ShowWhen = &domain.FieldCondition{Key: "slot", In: choices}
	}
	return f
}

var (
	paidChoices   = []string{slotBrandBanner, slotSponsored, slotAdRow, slotAdvertorial}
	bodyChoices   = append(append([]string{slotProductRow, slotDisplayBanner}, paidChoices...), blockValues(allBlockTypes()...)...)
	stateCodeRe   = regexp.MustCompile(`^[A-Z]{2}$`)
	zipPrefixRe   = regexp.MustCompile(`^\d{1,5}$`)
	httpsOrPathRe = regexp.MustCompile(`^(/|https://)`)
)

func allBlockTypes() []string {
	out := make([]string, 0, len(homeBlockKinds))
	for _, k := range homeBlockKinds {
		out = append(out, k.typ)
	}
	return out
}

const (
	adsSection    = "Paid module"
	targetSection = "Where and to whom it shows"
)

// ctaChoices are the slot choices that have a button.
var ctaChoices = append([]string{slotHomeTiles, slotBrandBanner, slotAdvertorial}, blockValues(domain.BlockMembership, domain.BlockSignIn, domain.BlockServices, domain.BlockCommunity, domain.BlockSeller)...)

// ctaField is the button text field, shown for the slots that have a button.
func ctaField(prev jobj, create bool) []domain.FormField {
	current := jstr(prev, "slot")
	if current == slotHomeBlock {
		current = blockPrefix + jstr(prev, "payload.type")
	}
	if !create && !slices.Contains(ctaChoices, current) {
		return nil
	}
	return []domain.FormField{showFor(withValue(withMax(ff("cta", "Button text", "text", false), 40), jstr(prev, "payload.cta")), create, ctaChoices...)}
}

// adFields are the fields of the new slots plus the shared position and targeting fields. prev is the stored
// placement (nil when creating).
func adFields(prev jobj, create bool) []domain.FormField {
	p := func(k string) string { return jstr(prev, "payload."+k) }
	list := func(k string) string { return joinStrings(prev, "payload."+k) }
	var out []domain.FormField
	current := jstr(prev, "slot")
	if current == slotHomeBlock {
		current = blockPrefix + jstr(prev, "payload.type")
	}
	add := func(f domain.FormField, section string, choices ...string) {
		if !create && !slices.Contains(choices, current) {
			return // editing one record: only the fields of its own slot
		}
		f.Section = section
		out = append(out, showFor(f, create, choices...))
	}
	blocks := func(types ...string) []string { return blockValues(types...) }

	add(withValue(withMax(ff("sub", "Second line (optional)", "text", false), 160), p("sub")), adsSection,
		append([]string{slotBrandBanner}, blocks(domain.BlockMembership, domain.BlockSignIn, domain.BlockServices, domain.BlockCommunity, domain.BlockSeller, domain.BlockDeals)...)...)
	add(withValue(withMax(ff("body", "Text (two lines at most)", "textarea", true), 200), p("text")), adsSection, slotAdvertorial)
	add(withValue(withPattern(ff("imageUrl", "Image address (https, media CDN)", "text", false), `^https://`, "An https:// image address"), p("imageUrl")), adsSection,
		append([]string{slotBrandBanner, slotAdvertorial}, blocks(domain.BlockMembership, domain.BlockSignIn)...)...)
	add(withValue(withPattern(ff("logoUrl", "Advertiser logo (https, transparent background)", "text", false), `^https://`, "An https:// image address"), p("logoUrl")), adsSection, slotBrandBanner)
	add(withValue(withMax(ff("skus", "Product strip: up to 3 ATINs, comma separated", "text", false), 100), list("skus")), adsSection, slotBrandBanner)
	add(withValue(withMax(ff("query", "Search words", "text", false), 200), p("query")), adsSection,
		append([]string{slotSponsored}, blocks(domain.BlockDeals)...)...)
	add(withValue(withOptions(ff("department", "Department", "select", false), tileDepartments), p("department")), adsSection,
		append([]string{slotSponsored}, blocks(domain.BlockDeals, domain.BlockSeller)...)...)
	add(withValue(withMax(ff("community", "Community key (leave empty to rotate by rule)", "text", false), 40), p("community")), adsSection, blocks(domain.BlockCommunity)...)
	add(withValue(withOptions(ff("tone", "Colour", "select", false), domain.StaffOptions("ink", "gold", "green", "sand")), p("tone")), adsSection,
		append([]string{slotBrandBanner}, blocks(domain.BlockMembership, domain.BlockSignIn)...)...)
	add(withValue(withOptions(ff("audience", "Shown to", "select", false), domain.StaffOptions("all", "signed-in", "signed-out")), p("audience")), adsSection, blockValues(allBlockTypes()...)...)
	add(withValue(withRange(ff("maxPerSession", "Most times one session sees it (0 = no cap)", "number", false), 20), numStr(prev, "payload.maxPerSession")), adsSection, paidChoices...)
	for n := 1; n <= adCount; n++ {
		ad := func(k string) string { return jstr(prev, "payload.ads."+strconv.Itoa(n-1)+"."+k) }
		sec := "Image ad " + strconv.Itoa(n) + " (ad row only)"
		add(withValue(withPattern(ff(adKey(n, "imageUrl"), "Image address (https)", "text", n <= 2), `^https://`, "An https:// image address"), ad("imageUrl")), sec, slotAdRow)
		add(withValue(withMax(ff(adKey(n, "alt"), "Alt text (describe the ad)", "text", n <= 2), 160), ad("alt")), sec, slotAdRow)
		add(withValue(withPattern(ff(adKey(n, "href"), "Link", "text", n <= 2), `^(/|https://)`, "A storefront path or an https:// address"), ad("href")), sec, slotAdRow)
		add(withValue(withMax(ff(adKey(n, "advertiser"), "Advertiser (if different from the placement's)", "text", false), 120), ad("advertiser")), sec, slotAdRow)
	}
	// Position and targeting for everything on the page body.
	add(withValue(withRange(ff("priority", "Position on the page (higher shows first; see Home page layout)", "number", false), 1000), numStr(prev, "priority")), targetSection, bodyChoices...)
	add(withValue(withMax(ff("states", "US states (e.g. TX, GA; empty = everywhere)", "text", false), 160), list("states")), targetSection, bodyChoices...)
	out = append(out, domain.FormField{Key: "zipPrefixes", Label: "ZIP prefixes (e.g. 77, 750; empty = everywhere)", Type: "text", MaxLength: 120,
		Value: joinStrings(prev, "targeting.zipPrefixes"), Section: targetSection})
	return out
}

func adKey(n int, f string) string { return "ad" + strconv.Itoa(n) + "_" + f }

func joinStrings(o jobj, path string) string {
	var cur any = map[string]any(o)
	for _, part := range strings.Split(path, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur = m[part]
	}
	list, ok := cur.([]any)
	if !ok {
		return ""
	}
	parts := make([]string, 0, len(list))
	for _, x := range list {
		if s, ok := x.(string); ok {
			parts = append(parts, s)
		}
	}
	return strings.Join(parts, ", ")
}

func numStr(o jobj, path string) any {
	if o == nil {
		return nil
	}
	if _, ok := lookup(o, path); !ok {
		return nil
	}
	return int(num(o, path))
}

func lookup(o jobj, path string) (any, bool) {
	var cur any = map[string]any(o)
	for _, part := range strings.Split(path, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil, false
		}
		cur, ok = m[part]
		if !ok {
			return nil, false
		}
	}
	return cur, cur != nil
}

// csv splits a comma separated field, trimming and dropping blanks.
func commaList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// applyAdFields writes the paid and home module fields onto the payload and returns problems with them. blockType is
// the home module type when the slot is home_block (from the create choice, or the stored payload).
func applyAdFields(slot, blockType string, v jobj, p jobj, advertiser string) map[string]string {
	errs := map[string]string{}
	set := func(k, val string) {
		if val != "" {
			p[k] = val
		} else {
			delete(p, k)
		}
	}
	if slot == slotBrandBanner || slot == slotAdvertorial {
		// These slots call the headline `headline`, not `title`.
		if t, ok := p["title"].(string); ok {
			p["headline"] = t
			delete(p, "title")
		}
	}
	paid := slices.Contains([]string{slotBrandBanner, slotSponsored, slotAdRow, slotAdvertorial}, slot)
	if paid {
		if advertiser == "" {
			errs["advertiser"] = "A paid module names its advertiser"
		} else {
			p["advertiser"] = advertiser
		}
		if _, shown := v["maxPerSession"]; shown {
			if c, ok := intField(v, "maxPerSession"); ok && c > 0 {
				p["maxPerSession"] = c
			} else {
				delete(p, "maxPerSession")
			}
		}
	}
	switch slot {
	case slotBrandBanner:
		set("sub", vs(v, "sub"))
		set("cta", vs(v, "cta"))
		set("imageUrl", vs(v, "imageUrl"))
		set("logoUrl", vs(v, "logoUrl"))
		set("tone", vs(v, "tone"))
		if vs(v, "cta") == "" {
			errs["cta"] = "Required"
		}
		if skus := upperList(vs(v, "skus")); len(skus) > 3 {
			errs["skus"] = "At most 3 products"
		} else if len(skus) > 0 {
			p["skus"] = skus
		} else {
			delete(p, "skus")
		}
	case slotAdvertorial:
		set("text", vs(v, "body"))
		set("cta", vs(v, "cta"))
		set("imageUrl", vs(v, "imageUrl"))
		if strings.TrimSpace(vs(v, "body")) == "" {
			errs["body"] = "Required"
		}
	case slotSponsored:
		set("query", vs(v, "query"))
		set("department", vs(v, "department"))
	case slotAdRow:
		delete(p, "title") // an ad row has no headline or link of its own: every ad carries its own
		delete(p, "href")
		var ads []any
		for n := 1; n <= adCount; n++ {
			img, alt, href := vs(v, adKey(n, "imageUrl")), vs(v, adKey(n, "alt")), vs(v, adKey(n, "href"))
			if img == "" && alt == "" && href == "" {
				continue
			}
			switch {
			case img == "":
				errs[adKey(n, "imageUrl")] = "Required"
			case alt == "":
				errs[adKey(n, "alt")] = "Describe the image for screen readers"
			case href == "":
				errs[adKey(n, "href")] = "Required"
			default:
				ad := map[string]any{"imageUrl": img, "alt": alt, "href": href}
				if a := vs(v, adKey(n, "advertiser")); a != "" {
					ad["advertiser"] = a
				}
				ads = append(ads, ad)
			}
		}
		if len(ads) < 2 && len(errs) == 0 {
			errs[adKey(2, "imageUrl")] = "An ad row needs two or three ads"
		}
		p["ads"] = ads
	case slotHomeBlock:
		p["type"] = blockType
		for _, k := range []string{"sub", "cta", "imageUrl", "tone", "department", "community", "query"} {
			if _, shown := v[k]; shown {
				set(k, vs(v, k))
			}
		}
		if a := vs(v, "audience"); a != "" {
			p["audience"] = a
		}
		if blockType == domain.BlockOccasions {
			cells, cellErrs := occasionCells(v)
			for k, m := range cellErrs {
				errs[k] = m
			}
			if len(cellErrs) == 0 {
				p["cells"] = cells
			}
		}
		if blockType == domain.BlockMembership || blockType == domain.BlockSignIn {
			if strings.TrimSpace(vs(v, "href")) == "" {
				errs["href"] = "Required for a band"
			}
		}
	}
	if _, shown := v["states"]; shown {
		states := upperList(vs(v, "states"))
		for _, s := range states {
			if !stateCodeRe.MatchString(s) {
				errs["states"] = "Use two-letter state codes, e.g. TX, GA"
			}
		}
		if len(states) > 0 && errs["states"] == "" {
			p["states"] = states
		} else if len(states) == 0 {
			delete(p, "states")
		}
	}
	for k, m := range errs {
		if m == "" {
			delete(errs, k)
		}
	}
	return errs
}

func upperList(s string) []string {
	out := commaList(s)
	for i := range out {
		out[i] = strings.ToUpper(out[i])
	}
	return out
}

// targetingFrom applies the editor's ZIP prefix field to the stored (or default) targeting.
func targetingFrom(v jobj, prev any) (map[string]any, map[string]string) {
	t := map[string]any{"markets": []string{"us"}}
	if m, ok := prev.(map[string]any); ok && m != nil {
		t = map[string]any{}
		for k, val := range m {
			t[k] = val
		}
	}
	errs := map[string]string{}
	if raw, shown := v["zipPrefixes"]; shown {
		_ = raw
		zips := commaList(vs(v, "zipPrefixes"))
		for _, z := range zips {
			if !zipPrefixRe.MatchString(z) {
				errs["zipPrefixes"] = "ZIP prefixes are 1 to 5 digits"
			}
		}
		if zips == nil {
			zips = []string{}
		}
		t["zipPrefixes"] = zips
	}
	return t, errs
}

// priorityFrom reads the editor's position field, keeping prev when it was not part of the form.
func priorityFrom(v jobj, prev int) int {
	if n, ok := intField(v, "priority"); ok {
		return max(-1000, min(1000, n))
	}
	return prev
}

// intField reads a number field (JSON numbers arrive as float64; a text answer is parsed).
func intField(v jobj, k string) (int, bool) {
	switch x := v[k].(type) {
	case float64:
		return int(x), true
	case string:
		n, err := strconv.Atoi(strings.TrimSpace(x))
		return n, err == nil
	}
	return 0, false
}

func mergeErr(errs map[string]string, k, m string) map[string]string {
	if errs == nil {
		errs = map[string]string{}
	}
	errs[k] = m
	return errs
}

// occasionCells reads the occasion cells: each has a label and link; its picture is a fixed image or, with a product
// rule, the photo of the best eligible product of that rule (resolved on the page, never stored).
func occasionCells(v jobj) ([]any, map[string]string) {
	errs := map[string]string{}
	cells := []any{}
	for n := 1; n <= tileCellCount; n++ {
		label, href, img := vs(v, cellKey(n, "label")), vs(v, cellKey(n, "href")), vs(v, cellKey(n, "imageUrl"))
		src, key, msg := ruleSource(v, n)
		if key != "" {
			errs[key] = msg
			continue
		}
		if label == "" && href == "" && img == "" && src == nil {
			continue
		}
		switch {
		case label == "":
			errs[cellKey(n, "label")] = "Required"
			continue
		case href == "":
			errs[cellKey(n, "href")] = "Required"
			continue
		}
		cell := map[string]any{"label": label, "href": href, "imageUrl": nil}
		if img != "" {
			cell["imageUrl"] = img
		}
		if src != nil {
			cell["source"] = src
		}
		cells = append(cells, cell)
	}
	if len(cells) < 3 && len(errs) == 0 {
		errs[cellKey(3, "label")] = "Shop by occasion needs at least three cells"
	}
	return cells, errs
}

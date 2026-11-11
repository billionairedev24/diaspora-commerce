package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Home page layout: the placements that make up the home body in the order shoppers see them (priority, highest first),
// with a preview of what each one shows, who sees it and how it is disclosed. Staff move a module up or down, or set its
// position, without opening it; the change is the placement's priority and needs no new review (the content is the same).

// bodySlotNames are the stored slots of the home body with the name staff know them by.
var bodySlotNames = map[string]string{
	slotProductRow: "Product rail", slotDisplayBanner: "Display banner", slotBrandBanner: "Sponsored brand banner",
	slotSponsored: "Sponsored products rail", slotAdRow: "Display ad row", slotAdvertorial: "Advertorial", slotHomeBlock: "Home module",
}

var blockLabels = func() map[string]string {
	m := map[string]string{}
	for _, k := range homeBlockKinds {
		m[k.typ] = strings.SplitN(k.label, " (", 2)[0]
	}
	return m
}()

// layoutKind is the name of what a placement shows ("Deals of the day", "Sponsored brand banner").
func layoutKind(o jobj) string {
	if jstr(o, "slot") == slotHomeBlock {
		if l := blockLabels[jstr(o, "payload.type")]; l != "" {
			return l
		}
	}
	return firstNonEmpty(bodySlotNames[jstr(o, "slot")], jstr(o, "slot"))
}

func isBodyPlacement(o jobj) bool {
	_, ok := bodySlotNames[jstr(o, "slot")]
	return ok && jstr(o, "status") != "ENDED"
}

// layoutOrder is the home body order: priority desc, then content's tie order (newest start first is content's; here the
// stable order of the list is kept).
func layoutOrder(items []jobj) []jobj {
	out := make([]jobj, 0, len(items))
	for _, o := range items {
		if isBodyPlacement(o) {
			out = append(out, o)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return num(out[i], "priority") > num(out[j], "priority") })
	return out
}

// targetingSummary reads "TX, GA · ZIP 77, 750" or "everywhere".
func targetingSummary(o jobj) string {
	var parts []string
	if s := joinStrings(o, "payload.states"); s != "" {
		parts = append(parts, s)
	}
	if z := joinStrings(o, "targeting.zipPrefixes"); z != "" {
		parts = append(parts, "ZIP "+z)
	}
	if a := jstr(o, "payload.audience"); a != "" && a != "all" {
		parts = append(parts, a)
	}
	if len(parts) == 0 {
		return "Everywhere"
	}
	return strings.Join(parts, " · ")
}

func disclosure(o jobj) string {
	if boolean(o, "paid") || strings.HasPrefix(jstr(o, "slot"), "home_") && jstr(o, "advertiser") != "" {
		return "Labelled Sponsored · " + firstNonEmpty(jstr(o, "advertiser"), "advertiser missing")
	}
	return "Organic (no label)"
}

// whatItShows describes the module in a sentence: the preview staff read before they move or approve it.
func whatItShows(o jobj) string {
	title := firstNonEmpty(jstr(o, "payload.headline"), jstr(o, "payload.title"), jstr(o, "name"))
	switch jstr(o, "slot") {
	case slotAdRow:
		return fmt.Sprintf("%d image ads in a row, each tagged Ad", countList(o, "payload.ads"))
	case slotBrandBanner:
		return fmt.Sprintf("Full-width banner \"%s\"%s", title, withProducts(o))
	case slotSponsored:
		return "Products sellers promote for \"" + firstNonEmpty(jstr(o, "payload.query"), jstr(o, "payload.department"), "everything") + "\" (the sponsored search auction)"
	case slotAdvertorial:
		return "Advertorial card \"" + title + "\": image, headline and two lines of text"
	case slotHomeBlock:
		switch jstr(o, "payload.type") {
		case domain.BlockDeals:
			return "In-stock items with a real photo and at least 10% off compare-at price, with a countdown"
		case domain.BlockCommunity:
			return "Products of " + firstNonEmpty(jstr(o, "payload.community"), "a community picked by rule each day") + ", hidden when thin"
		case domain.BlockSeller:
			return "The best rated seller shipping near the shopper, with their products"
		case domain.BlockServices:
			return "A link to /services, shown only where providers list"
		case domain.BlockOccasions:
			return fmt.Sprintf("%d occasion cells with a real product photo each", countList(o, "payload.cells"))
		}
		return title
	case slotProductRow:
		return "Product rail \"" + title + "\" (hidden when it has fewer than 4 products)"
	}
	return title
}

func withProducts(o jobj) string {
	if n := countList(o, "payload.skus"); n > 0 {
		return fmt.Sprintf(" with a strip of %d products", n)
	}
	return ""
}

func countList(o jobj, path string) int {
	v, ok := lookup(o, path)
	if !ok {
		return 0
	}
	l, _ := v.([]any)
	return len(l)
}

func homeLayoutResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Content, "/v1/placements", nil, &raw); err != nil {
			return nil, err
		}
		ordered := layoutOrder(jpage(raw).items)
		for i, o := range ordered {
			o["position"] = i + 1
			o["kind"] = layoutKind(o)
			o["shows"] = targetingSummary(o)
		}
		return ordered, nil
	}
	list := listAll(load)
	// move renumbers the whole body in steps of ten so ties are broken and order is exact, changing only what moved.
	move := func(delta int) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
			items, err := load(ctx, sc, staffQuery{})
			if err != nil {
				return err
			}
			ids := make([]string, 0, len(items))
			for _, it := range items {
				ids = append(ids, jstr(it, "id"))
			}
			return applyOrder(ctx, sc, items, reorder(ids, jstr(o, "id"), delta))
		}
	}
	return &staffResource{
		key: "home-layout", module: "marketing", title: "Home page layout", statusKey: "status", exportable: false,
		columns: []domain.Column{col("position", "#", "number", 5), col("name", "Module", "strong", 24), col("kind", "Type", "text", 20),
			col("advertiser", "Advertiser", "text", 14), col("shows", "Shown to", "text", 16), col("priority", "Priority", "number", 8), col("status", "Status", "status", 10)},
		facets: nil,
		list:   list, get: findIn(list, "id"),
		titleOf: func(o jobj) string { return fmt.Sprintf("%d. %s", int(num(o, "position")), jstr(o, "name")) },
		subtitleOf: func(o jobj) string {
			return layoutKind(o) + " · " + disclosure(o) + " · " + targetingSummary(o)
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "Preview", Fields: []domain.DetailField{
					{Label: "Position on the page", Value: fmt.Sprintf("%d (priority %d; higher shows first)", int(num(o, "position")), int(num(o, "priority"))), Kind: "text"},
					{Label: "Type", Value: layoutKind(o), Kind: "text"},
					{Label: "Shows", Value: whatItShows(o), Kind: "text"},
					{Label: "Disclosure", Value: disclosure(o), Kind: "text"},
					{Label: "Shown to", Value: targetingSummary(o), Kind: "text"},
					{Label: "Schedule", Value: scheduleSummary(o), Kind: "text"},
				}},
			}
		},
		actions: []staffAction{
			{key: "move-up", label: "Move up", tone: "neutral", permission: "content:placements.edit", run: move(-1)},
			{key: "move-down", label: "Move down", tone: "neutral", permission: "content:placements.edit", run: move(1)},
			{key: "set-priority", label: "Set priority", tone: "neutral", permission: "content:placements.edit",
				form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
					return &domain.Form{Title: "Position on the page", SubmitLabel: "Save", Fields: []domain.FormField{
						withValue(withRange(withPattern(ff("priority", "Priority (higher shows first; -1000 to 1000)", "number", true), `^-?\d+$`, ""), 1000), int(num(o, "priority")))}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					n, ok := intField(v, "priority")
					if !ok {
						return domain.ValidationProblem(map[string]string{"priority": "Enter a number"})
					}
					return setPriority(ctx, sc, jstr(o, "id"), n)
				}},
		},
		rowActions: []string{"move-up", "move-down"},
	}
}

func scheduleSummary(o jobj) string {
	from, to := jstr(o, "schedule.startsAt"), jstr(o, "schedule.endsAt")
	switch {
	case from != "" && to != "":
		return from + " to " + to
	case from != "":
		return "From " + from + ", no end"
	}
	return "Always"
}

// reorder moves id by delta places in ids (clamped) and returns the new order.
func reorder(ids []string, id string, delta int) []string {
	at := -1
	for i, x := range ids {
		if x == id {
			at = i
		}
	}
	to := at + delta
	if at < 0 || to < 0 || to >= len(ids) {
		return ids
	}
	out := append([]string(nil), ids...)
	out[at], out[to] = out[to], out[at]
	return out
}

// priorityFor is the priority of position i (0-based) in a body of n modules: steps of ten, highest first.
func priorityFor(i, n int) int { return (n - i) * 10 }

// applyOrder gives the modules the priorities of their new positions, writing only the ones that change.
func applyOrder(ctx context.Context, sc staffCtx, items []jobj, order []string) error {
	current := map[string]int{}
	for _, it := range items {
		current[jstr(it, "id")] = int(num(it, "priority"))
	}
	for i, id := range order {
		if want := priorityFor(i, len(order)); current[id] != want {
			if err := setPriority(ctx, sc, id, want); err != nil {
				return err
			}
		}
	}
	return nil
}

func setPriority(ctx context.Context, sc staffCtx, id string, priority int) error {
	return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/placements/" + url.PathEscape(id) + "/priority",
		Body: jobj{"priority": priority}}, nil)
}

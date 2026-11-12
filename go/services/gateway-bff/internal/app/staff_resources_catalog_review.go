package app

import (
	"context"
	"fmt"
	"math"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The catalog review queues of the staff console (S385 to S387, S390): change requests sellers file on pages they did not
// create, wrong-match and duplicate-page reports, no-barcode exemptions, and product images held by moderation. Catalog and
// media own the rules and publish the audit events; the BFF names people and organisations, lays out what changes (the
// page as it is next to what is asked), and asks for a reason of 3 or more characters on every decision, which the seller
// reads. Every action needs catalog:moderate (media:moderate also decides held images).

const (
	permCatalogModerate = "catalog:moderate"
	permMediaModerate   = "media:moderate"
	reviewMinReason     = 3
	reviewMaxReason     = 1000
	reviewSLABusiness   = 2 // business days, the service level shown next to every waiting row (a catalog setting)
	heldImagesLimit     = 100
)

var changeTypeLabels = map[string]string{
	"IMAGE_ADD": "Add a picture", "IMAGE_REPLACE": "Replace a picture", "TITLE": "Change the title", "ATTRIBUTE": "Change a detail",
	"DESCRIPTION": "Change the description", "CATEGORY": "Change the category", "BRAND": "Change the brand",
	"NEW_VARIANT": "Add a size or colour", "WRONG_MATCH": "Wrong match", "DUPLICATE_PAGE": "Duplicate page",
}

var changeResolutionLabels = map[string]string{
	"CORRECT_GTIN": "Barcode corrected", "MOVE_VARIANT": "Variant moved to another page", "SPLIT_VARIANT": "Variant split onto its own page",
	"MERGE": "Pages merged", "NEW_VARIANT": "New variant added",
}

var exemptionReasonLabels = map[string]string{
	"BRAND_GATED_CATEGORY": "Brand-gated category", "KNOWN_GS1_BRAND": "Brand that carries barcodes on other pages",
}

var heldCheckLabels = map[string]string{
	"HELD_TEXT": "Text, logo or watermark", "HELD_MISMATCH": "Does not match the title or brand", "HELD_DUPLICATE": "Looks like a photo already used",
	"HELD_PROPS": "Props or people in a main photo", "HELD_REVIEW": "Needs a person to look",
}

func catalogReviewResources() []*staffResource {
	return []*staffResource{changeRequestsResource(), wrongMatchesResource(), duplicatePagesResource(), gtinExemptionsResource(), heldImagesResource()}
}

// ---------------------------------------------------------------- shared pieces

// reviewReasonField is the reason every decision needs; the seller reads it.
func reviewReasonField() domain.FormField {
	f := withMin(withMax(ff("reason", "Reason (the seller reads it)", "textarea", true), reviewMaxReason), reviewMinReason)
	f.Help = "At least 3 characters."
	return f
}

func reviewForm(title, submit, intro string, extra ...domain.FormField) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: append(slices.Clone(extra), reviewReasonField())}
	}
}

// waitingText says how long a request has waited and whether it is past the service level ("3 days · overdue").
func waitingText(ageHours float64, overdue bool) string {
	var age string
	switch h := int(math.Round(ageHours)); {
	case h < 1:
		age = "Under an hour"
	case h < 48:
		age = strconv.Itoa(h) + " h"
	default:
		age = strconv.Itoa(h/24) + " days"
	}
	if overdue {
		return age + " · overdue"
	}
	return age
}

// addBusinessDays is the instant n business days (Monday to Friday, UTC) after t.
func addBusinessDays(t time.Time, n int) time.Time {
	t = t.UTC()
	for n > 0 {
		t = t.AddDate(0, 0, 1)
		if t.Weekday() != time.Saturday && t.Weekday() != time.Sunday {
			n--
		}
	}
	return t
}

func millis(o jobj, path string) (time.Time, bool) {
	t, err := time.Parse(time.RFC3339, jstr(o, path))
	return t, err == nil
}

func titleOrAtin(o jobj, titleKey, atinKey string) string {
	return firstNonEmpty(jstr(o, titleKey), jstr(o, atinKey))
}

// optionsText reads a variant's options as "Size: XL, Color: Gold".
func optionsText(o jobj, path string) string {
	m, _ := at(o, path).(map[string]any)
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, k+": "+displayValue(m[k]))
	}
	return strings.Join(parts, ", ")
}

// loadPage reads a product page by its ATIN for the sheet; a page that cannot be read is simply left out.
func loadPage(ctx context.Context, sc staffCtx, atin string) jobj {
	if atin == "" {
		return nil
	}
	var page jobj
	if err := sc.get(ctx, sc.up.Catalog, "/v1/products/atin/"+url.PathEscape(atin), nil, &page); err != nil {
		return nil
	}
	return page
}

func variantOf(page jobj, atin string) jobj {
	for _, v := range objs(page, "variants") {
		if jstr(v, "atin") == atin {
			return v
		}
	}
	return nil
}

func categoryPathOf(ctx context.Context, sc staffCtx, id string) string {
	if id == "" {
		return ""
	}
	var tree []any
	if err := sc.get(ctx, sc.up.Catalog, "/v1/categories", nil, &tree); err != nil {
		return ""
	}
	for _, it := range tree {
		if n, ok := it.(map[string]any); ok && jstr(n, "id") == id {
			return jstr(n, "path")
		}
	}
	return ""
}

// askedText is the change a request asks for, in plain words.
func askedText(o jobj) string {
	p := func(k string) string { return jstr(o, "payload."+k) }
	switch jstr(o, "type") {
	case "TITLE":
		return "New title: " + p("value")
	case "DESCRIPTION":
		return "New description: " + p("value")
	case "BRAND":
		return "New brand: " + p("value")
	case "ATTRIBUTE":
		return domain.Humanize(p("key")) + ": " + p("value")
	case "CATEGORY":
		return firstNonEmpty(jstr(o, "categoryPath"), "A different category")
	case "IMAGE_ADD":
		if jstr(o, "field") == "MAIN" {
			return "A new main picture"
		}
		return "An extra picture"
	case "IMAGE_REPLACE":
		return "A picture to replace picture " + strconv.Itoa(int(num(o, "payload.position"))+1)
	case "NEW_VARIANT":
		return "A new " + strings.ToLower(optionsText(o, "payload.options"))
	case "WRONG_MATCH":
		return "Seller says the barcode " + p("gtin") + " is not this product"
	case "DUPLICATE_PAGE":
		return "Same product as " + firstNonEmpty(jstr(o, "otherPage.title"), p("duplicateOfAtin"))
	}
	return ""
}

func requestCells(o jobj) map[string]any {
	open := jstr(o, "status") == "PENDING"
	waiting := ""
	if open {
		waiting = waitingText(num(o, "ageHours"), boolean(o, "overdue"))
	}
	return map[string]any{
		"title": titleOrAtin(o, "title", "atin"), "change": changeTypeLabels[jstr(o, "type")], "asked": askedText(o), "waiting": waiting,
	}
}

// requestSections lays out one request: who asks what, the page as it is, and the decision.
func requestSections(o jobj) []domain.DetailSection {
	request := domain.DetailSection{Title: "Request", Fields: []domain.DetailField{
		fieldOf("Product page", titleOrAtin(o, "title", "atin"), "text"),
		fieldOf("ATIN", jstr(o, "atin"), "mono"),
		fieldOf("Asked by", jstr(o, "requestedByOrgName"), "text"),
		fieldOf("Kind", changeTypeLabels[jstr(o, "type")], "text"),
		fieldOf("What is asked", askedText(o), "text"),
		fieldOf("Seller's note", jstr(o, "note"), "text"),
		fieldOf("Filed", jstr(o, "requestedAt"), "datetime"),
		fieldOf("Answer due", jstr(o, "slaDueAt"), "datetime"),
	}}
	if jstr(o, "status") == "PENDING" {
		request.Fields = append(request.Fields, fieldOf("Waiting", waitingText(num(o, "ageHours"), boolean(o, "overdue")), "text"))
	}
	secs := []domain.DetailSection{request}
	if p := pictureSection(o); p != nil {
		secs = append(secs, *p)
	}
	if page, _ := o["page"].(map[string]any); page != nil {
		secs = append(secs, domain.DetailSection{Title: "The page now", Fields: []domain.DetailField{
			fieldOf("Title", jstr(page, "title"), "text"), fieldOf("Status", jstr(page, "status"), "status"),
			fieldOf("Brand", jstr(page, "attributes.brand"), "text"), fieldOf("Version", jstr(page, "version"), "text"),
		}})
	}
	if jstr(o, "status") != "PENDING" {
		secs = append(secs, domain.DetailSection{Title: "Decision", Fields: []domain.DetailField{
			fieldOf("Status", jstr(o, "status"), "status"), fieldOf("Decided by", jstr(o, "decidedByName"), "text"),
			fieldOf("When", jstr(o, "decidedAt"), "datetime"), fieldOf("Reason", jstr(o, "reason"), "text"),
			fieldOf("How", changeResolutionLabels[jstr(o, "resolution")], "text"),
		}})
	}
	return secs
}

// pictureSection shows the asked picture (and the picture it replaces) as a gallery; https only.
func pictureSection(o jobj) *domain.DetailSection {
	t := jstr(o, "type")
	if t != "IMAGE_ADD" && t != "IMAGE_REPLACE" {
		return nil
	}
	fields := []domain.DetailField{}
	if u := jstr(o, "payload.url"); strings.HasPrefix(u, "https://") {
		fields = append(fields, domain.DetailField{Label: "Asked", Value: u, Kind: "image"})
	}
	if t == "IMAGE_REPLACE" {
		pos := int(num(o, "payload.position"))
		pics := objs(o, "page.images")
		if pos >= 0 && pos < len(pics) {
			if u := jstr(pics[pos], "url"); strings.HasPrefix(u, "https://") {
				fields = append(fields, domain.DetailField{Label: "Now", Value: u, Kind: "image"})
			}
		}
	}
	if len(fields) == 0 {
		return nil
	}
	return &domain.DetailSection{Title: "Picture", Fields: fields}
}

func decideRequest(approve bool, resolution string, params func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
	return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
		decision := "REJECT"
		if approve {
			decision = "APPROVE"
		}
		body := jobj{"decision": decision, "reason": vs(v, "reason"), "expectedVersion": int64(num(o, "version"))}
		if approve && resolution != "" {
			body["resolution"] = resolution
			if params != nil {
				body["resolutionParams"] = params(v)
			}
		}
		return sc.post(ctx, sc.up.Catalog, "/v1/change-requests/"+url.PathEscape(jstr(o, "id"))+"/decision", body, nil)
	}
}

// requestResource is the shared shape of the three tables that read catalog's change requests.
func requestResource(key, title string, extra func(q staffQuery, v url.Values), enrich func(ctx context.Context, sc staffCtx, o jobj)) *staffResource {
	return &staffResource{
		key: key, module: "catalog", title: title, statusKey: "status", exportable: true,
		columns: []domain.Column{col("title", "Product page", "strong", 24), col("change", "Kind", "text", 16), col("asked", "What is asked", "text", 30),
			col("requestedByOrgName", "Seller", "text", 14), col("waiting", "Waiting", "text", 12), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PENDING", "APPLIED", "REJECTED", "WITHDRAWN"),
		names:  []nameRef{sellerRef("requestedByOrg"), personRef("decidedBy", "Decided by")},
		list:   listPaged("/v1/change-requests", upCatalog, extra),
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var o jobj
			if err := sc.get(ctx, sc.up.Catalog, "/v1/change-requests/"+url.PathEscape(id), nil, &o); err != nil {
				return nil, err
			}
			if page := loadPage(ctx, sc, jstr(o, "atin")); page != nil {
				o["page"] = page
			}
			if enrich != nil {
				enrich(ctx, sc, o)
			}
			return o, nil
		},
		cells:      requestCells,
		titleOf:    func(o jobj) string { return titleOrAtin(o, "title", "atin") },
		subtitleOf: func(o jobj) string { return changeTypeLabels[jstr(o, "type")] + " · " + jstr(o, "requestedByOrgName") },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections:   requestSections,
	}
}

// ---------------------------------------------------------------- change requests

// changeRequestsResource is the Change requests table: sellers' suggestions to edit a page they did not create (title,
// details, description, category, brand, pictures and new sizes or colours). Approving applies the change to the page and
// tells its owner; a picture is judged by the image rules first and one held for staff waits for the Held images table.
func changeRequestsResource() *staffResource {
	r := requestResource("change-requests", "Change requests", func(_ staffQuery, v url.Values) { v.Set("contentOnly", "true") },
		func(ctx context.Context, sc staffCtx, o jobj) {
			if jstr(o, "type") == "CATEGORY" {
				o["categoryPath"] = categoryPathOf(ctx, sc, jstr(o, "payload.categoryId"))
			}
		})
	r.actions = []staffAction{
		{key: "approve", label: "Approve", tone: "primary", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: func(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
				var extra []domain.FormField
				intro := "The change is applied to the page and its owner is told. Everything is recorded with your name."
				if jstr(o, "type") == "NEW_VARIANT" {
					intro = "The new size or colour becomes a variant of the page with its own ATIN. A page that is on sale cannot take a variant " +
						"that has no tax decision unless you accept that it leaves sale until tax review has approved it."
					extra = append(extra, withHelp(ff("holdPageForTax", "Take the page off sale until tax review approves the new variant", "toggle", false),
						"Only needed when the page is on sale."))
				}
				return reviewForm("Approve the change", "Approve", intro, extra...)(ctx, sc, o)
			},
			run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
				// Only a new variant has a parameter: whether the page may leave sale until tax review approves it.
				var params func(v jobj) jobj
				if jstr(o, "type") == "NEW_VARIANT" {
					params = func(v jobj) jobj { return jobj{"holdPageForTax": vb(v, "holdPageForTax")} }
				}
				body := jobj{"decision": "APPROVE", "reason": vs(v, "reason"), "expectedVersion": int64(num(o, "version"))}
				if params != nil {
					body["resolutionParams"] = params(v)
				}
				return sc.post(ctx, sc.up.Catalog, "/v1/change-requests/"+url.PathEscape(jstr(o, "id"))+"/decision", body, nil)
			}},
		{key: "reject", label: "Reject", tone: "danger", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: reviewForm("Do not apply the change", "Reject", "The seller sees “Not applied, because …” with your reason."),
			run:  decideRequest(false, "", nil)},
	}
	r.rowActions = []string{"reject"}
	return r
}

// ---------------------------------------------------------------- wrong matches

// wrongMatchesResource is the Wrong matches table: a seller was shown a page for the barcode they typed and answered "not
// my product". Staff correct the barcode, move the variant to the page it belongs on, split it onto a page of its own, or
// reject the report. While it is open the seller cannot create a second page for that barcode.
func wrongMatchesResource() *staffResource {
	r := requestResource("wrong-matches", "Wrong matches", func(_ staffQuery, v url.Values) { v.Set("type", "WRONG_MATCH") }, nil)
	r.columns = []domain.Column{col("title", "Product page shown", "strong", 26), col("asked", "What the seller says", "text", 30),
		col("requestedByOrgName", "Seller", "text", 14), col("waiting", "Waiting", "text", 12), col("status", "Status", "status", 10)}
	baseSections := r.sections
	r.get = wrongMatchGet(r.get)
	r.sections = func(o jobj) []domain.DetailSection {
		secs := baseSections(o)
		shown := domain.DetailSection{Title: "The match the seller was shown", Fields: []domain.DetailField{
			fieldOf("Barcode typed", jstr(o, "payload.gtin"), "mono"),
			fieldOf("Product shown", firstNonEmpty(optionsText(o, "shownVariant.options"), jstr(o, "variantAtin")), "text"),
			fieldOf("Barcode on that product", jstr(o, "shownVariant.gtin"), "mono"),
			fieldOf("Variant ATIN", jstr(o, "variantAtin"), "mono"),
		}}
		return append(secs[:1], append([]domain.DetailSection{shown}, secs[1:]...)...)
	}
	r.actions = []staffAction{
		{key: "correct", label: "Correct the barcode", tone: "primary", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: reviewForm("Correct the barcode on the product", "Correct", "The page's variant gets the barcode you type. A barcode another page has is a merge, not a correction.",
				withMax(ff("gtin", "Correct barcode", "text", true), 20)),
			run: decideRequest(true, "CORRECT_GTIN", func(v jobj) jobj { return jobj{"gtin": vs(v, "gtin")} })},
		{key: "move", label: "Move to another page", tone: "primary", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: reviewForm("Move the variant to the page it belongs on", "Move", "The variant keeps its ATIN and its offers follow it. They wait for the tax check again before they are on sale. "+
				"The page it leaves keeps at least one variant.",
				withMax(ff("targetAtin", "ATIN of the page it belongs on", "text", true), 12),
				withHelp(ff("holdPageForTax", "Take the other page off sale until tax review approves", "toggle", false), "Only needed when that page is on sale and taxed differently.")),
			run: decideRequest(true, "MOVE_VARIANT", func(v jobj) jobj {
				return jobj{"targetAtin": strings.ToUpper(vs(v, "targetAtin")), "holdPageForTax": vb(v, "holdPageForTax")}
			})},
		{key: "split", label: "Split onto its own page", tone: "primary", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: reviewForm("Make the variant a page of its own", "Split", "The variant keeps its ATIN and becomes the only variant of a new page in review. It is not on sale until it is reviewed and its tax is approved."),
			run:  decideRequest(true, "SPLIT_VARIANT", nil)},
		{key: "reject", label: "Reject the report", tone: "danger", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: reviewForm("The match is right", "Reject", "The seller sees “Not applied, because …” with your reason."), run: decideRequest(false, "", nil)},
	}
	r.rowActions = []string{"reject"}
	return r
}

// wrongMatchGet adds the variant the seller was shown (its options and barcode) to the record.
func wrongMatchGet(next func(context.Context, staffCtx, string) (jobj, error)) func(context.Context, staffCtx, string) (jobj, error) {
	return func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		o, err := next(ctx, sc, id)
		if err != nil {
			return o, err
		}
		if page, _ := o["page"].(map[string]any); page != nil {
			if v := variantOf(page, jstr(o, "variantAtin")); v != nil {
				o["shownVariant"] = v
			}
		}
		return o, nil
	}
}

// ---------------------------------------------------------------- duplicate pages and merges

// duplicatePagesResource is the Duplicate pages table: two pages describe one item. Approving merges them: the page that
// does not survive is never on sale again, its ATIN and every variant ATIN redirect to the survivor, its barcodes are
// released and the offers of its variants move to the matching variants of the survivor.
func duplicatePagesResource() *staffResource {
	r := requestResource("duplicate-pages", "Duplicate pages", func(_ staffQuery, v url.Values) { v.Set("type", "DUPLICATE_PAGE") },
		func(ctx context.Context, sc staffCtx, o jobj) {
			if other := loadPage(ctx, sc, strings.ToUpper(jstr(o, "payload.duplicateOfAtin"))); other != nil {
				o["otherPage"] = other
			}
		})
	r.columns = []domain.Column{col("title", "Page", "strong", 24), col("asked", "Duplicate of", "text", 30), col("requestedByOrgName", "Reported by", "text", 14),
		col("waiting", "Waiting", "text", 12), col("status", "Status", "status", 10)}
	baseSections := r.sections
	r.sections = func(o jobj) []domain.DetailSection {
		secs := baseSections(o)
		other, _ := o["otherPage"].(map[string]any)
		if other == nil {
			return secs
		}
		pageCol := func(title string, p jobj) domain.DetailSection {
			return domain.DetailSection{Title: title, Fields: []domain.DetailField{
				fieldOf("Title", jstr(p, "title"), "text"), fieldOf("ATIN", jstr(p, "atin"), "mono"), fieldOf("Status", jstr(p, "status"), "status"),
				fieldOf("Brand", jstr(p, "attributes.brand"), "text"), fieldOf("Variants", strconv.Itoa(len(objs(p, "variants"))), "text"),
			}}
		}
		page, _ := o["page"].(map[string]any)
		out := append([]domain.DetailSection{}, secs[:1]...)
		out = append(out, pageCol("This page", page), pageCol("The other page", other))
		for _, s := range secs[1:] {
			if s.Title != "The page now" { // "This page" says it already
				out = append(out, s)
			}
		}
		return out
	}
	r.tables = func(o jobj) []domain.DetailTable {
		cols := []domain.Column{col("atin", "ATIN", "mono", 20), col("options", "Options", "text", 40), col("gtin", "Barcode", "mono", 40)}
		table := func(title string, p jobj) domain.DetailTable {
			items := []jobj{}
			for _, v := range objs(p, "variants") {
				items = append(items, jobj{"atin": jstr(v, "atin"), "options": firstNonEmpty(optionsText(v, "options"), "—"), "gtin": firstNonEmpty(jstr(v, "gtin"), "No barcode")})
			}
			return tableOf(title, items, cols, "atin")
		}
		page, _ := o["page"].(map[string]any)
		other, _ := o["otherPage"].(map[string]any)
		if page == nil || other == nil {
			return []domain.DetailTable{}
		}
		return []domain.DetailTable{table("Variants of this page", page), table("Variants of the other page", other)}
	}
	r.actions = []staffAction{
		{key: "merge", label: "Merge the pages", tone: "primary", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: func(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
				page, _ := o["page"].(map[string]any)
				other, _ := o["otherPage"].(map[string]any)
				options := []domain.FieldOption{{Value: jstr(o, "atin"), Label: "This page: " + firstNonEmpty(jstr(page, "title"), jstr(o, "atin"))}}
				if other != nil {
					options = append(options, domain.FieldOption{Value: jstr(other, "atin"), Label: "The other page: " + jstr(other, "title")})
				}
				survivor := withValue(withOptions(ff("survivorAtin", "The page that stays", "select", true), options), jstr(o, "atin"))
				mapping := withHelp(withMax(ff("variantMap", "Which variant becomes which (only when asked)", "textarea", false), 2000),
					"One per line: the ATIN of a variant of the page that goes = the ATIN of the variant that stays. Variants are matched by barcode, then by options, automatically.")
				return reviewForm("Merge the two pages", "Merge", "The page that goes is never on sale again and its ATIN redirects. Offers move to the page that stays and wait for the tax check "+
					"again. This cannot be undone.", survivor, mapping)(ctx, sc, o)
			},
			run: decideRequest(true, "MERGE", func(v jobj) jobj {
				params := jobj{"survivorAtin": strings.ToUpper(vs(v, "survivorAtin"))}
				if m := parseVariantMap(vs(v, "variantMap")); len(m) > 0 {
					params["variantMap"] = m
				}
				return params
			})},
		{key: "reject", label: "They are different products", tone: "danger", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
			form: reviewForm("Keep both pages", "Reject", "The seller sees “Not applied, because …” with your reason."), run: decideRequest(false, "", nil)},
	}
	r.rowActions = []string{"reject"}
	return r
}

// parseVariantMap reads "AT00000001 = AT00000002" lines (the separators = , : or an arrow) into a map of upper-case ATINs.
func parseVariantMap(text string) map[string]any {
	out := map[string]any{}
	for _, line := range strings.FieldsFunc(text, func(r rune) bool { return r == '\n' || r == ';' }) {
		parts := strings.FieldsFunc(line, func(r rune) bool {
			return r == '=' || r == ',' || r == ':' || r == '>' || r == '-' || r == ' ' || r == '\t'
		})
		if len(parts) == 2 {
			out[strings.ToUpper(parts[0])] = strings.ToUpper(parts[1])
		}
	}
	return out
}

// ---------------------------------------------------------------- no-barcode items

// gtinExemptionsResource is the No-barcode items table: a page that waits (PENDING_EXEMPTION, not on sale) because the item has
// no barcode in a brand-gated category or of a brand that carries barcodes elsewhere. Staff see pages that may be the same
// item (brand, part number and title) before they decide. Approval moves the page to listing review, never straight to sale.
func gtinExemptionsResource() *staffResource {
	return &staffResource{
		key: "gtin-exemptions", module: "catalog", title: "No-barcode items", statusKey: "status", exportable: true,
		columns: []domain.Column{col("title", "Product", "strong", 24), col("brand", "Brand", "text", 14), col("mpn", "Part number", "mono", 12),
			col("requestedByOrgName", "Seller", "text", 14), col("because", "Why staff decide", "text", 20), col("waiting", "Waiting", "text", 12), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PENDING", "APPROVED", "REJECTED"),
		names:  []nameRef{sellerRef("requestedByOrg"), personRef("decidedBy", "Decided by")},
		list:   listPaged("/v1/gtin-exemptions", upCatalog, nil),
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			// The catalog lists the queue oldest first with the hints; a single request is found in it.
			for _, status := range []string{"PENDING", "APPROVED", "REJECTED"} {
				var raw any
				if err := sc.get(ctx, sc.up.Catalog, "/v1/gtin-exemptions", url.Values{"status": {status}, "size": {"100"}}, &raw); err != nil {
					return nil, err
				}
				for _, it := range jpage(raw).items {
					if jstr(it, "id") == id {
						return it, nil
					}
				}
			}
			return nil, notFoundResource()
		},
		cells: func(o jobj) map[string]any {
			waiting := ""
			if jstr(o, "status") == "PENDING" {
				waiting = waitingText(num(o, "ageHours"), boolean(o, "overdue"))
			}
			return map[string]any{"because": exemptionReasonLabels[jstr(o, "requiredBecause")], "waiting": waiting}
		},
		titleOf:    func(o jobj) string { return titleOrAtin(o, "title", "productAtin") },
		subtitleOf: func(o jobj) string { return "No barcode · " + jstr(o, "requestedByOrgName") },
		version:    func(o jobj) string { return jstr(o, "id") },
		sections: func(o jobj) []domain.DetailSection {
			secs := []domain.DetailSection{{Title: "Request", Fields: []domain.DetailField{
				fieldOf("Product", titleOrAtin(o, "title", "productAtin"), "text"), fieldOf("ATIN", jstr(o, "productAtin"), "mono"),
				fieldOf("Seller", jstr(o, "requestedByOrgName"), "text"), fieldOf("Brand", jstr(o, "brand"), "text"), fieldOf("Part number", jstr(o, "mpn"), "text"),
				fieldOf("Why staff decide", exemptionReasonLabels[jstr(o, "requiredBecause")], "text"), fieldOf("Seller's reason", jstr(o, "sellerReason"), "text"),
				fieldOf("What the seller reads", jstr(o, "sellerMessage"), "text"), fieldOf("Filed", jstr(o, "createdAt"), "datetime"),
				fieldOf("Answer due", jstr(o, "slaDueAt"), "datetime"),
			}}}
			if jstr(o, "status") != "PENDING" {
				secs = append(secs, domain.DetailSection{Title: "Decision", Fields: []domain.DetailField{
					fieldOf("Status", jstr(o, "status"), "status"), fieldOf("Decided by", jstr(o, "decidedByName"), "text"),
					fieldOf("When", jstr(o, "decidedAt"), "datetime"), fieldOf("Reason", jstr(o, "reason"), "text"),
				}})
			}
			return secs
		},
		tables: func(o jobj) []domain.DetailTable {
			hints := objs(o, "duplicateHints")
			if len(hints) == 0 {
				return []domain.DetailTable{}
			}
			items := make([]jobj, 0, len(hints))
			for _, h := range hints {
				items = append(items, jobj{"title": jstr(h, "title"), "atin": jstr(h, "atin"), "brand": jstr(h, "brand"), "mpn": jstr(h, "mpn"),
					"why": hintText(h), "score": strconv.Itoa(int(math.Round(num(h, "score")*100))) + "%"})
			}
			return []domain.DetailTable{tableOf("Pages that may be the same item", items, []domain.Column{col("title", "Page", "strong", 34), col("atin", "ATIN", "mono", 14),
				col("brand", "Brand", "text", 14), col("mpn", "Part number", "mono", 12), col("why", "Matched on", "text", 16), col("score", "Similarity", "text", 10)}, "atin")}
		},
		actions: []staffAction{
			{key: "approve", label: "Accept: no barcode", tone: "primary", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
				form: reviewForm("Accept that the item has no barcode", "Accept", "The page moves to listing review. It is not on sale until it is approved there and its tax decision exists."),
				run:  decideExemption(true)},
			{key: "reject", label: "Reject", tone: "danger", permission: permCatalogModerate, level: domain.LevelApprove, when: inState("PENDING"),
				form: reviewForm("The item needs a barcode", "Reject", "The page is rejected and the seller reads your reason."), run: decideExemption(false)},
		},
		rowActions: []string{"reject"},
	}
}

func hintText(h jobj) string {
	why := []string{}
	for _, m := range strs(h, "matchedOn") {
		switch m {
		case "BRAND_MPN":
			why = append(why, "brand and part number")
		case "TITLE":
			why = append(why, "title")
		}
	}
	return strings.Join(why, " and ")
}

func decideExemption(approve bool) func(context.Context, staffCtx, jobj, jobj) error {
	return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
		decision := "REJECT"
		if approve {
			decision = "APPROVE"
		}
		return sc.post(ctx, sc.up.Catalog, "/v1/gtin-exemptions/"+url.PathEscape(jstr(o, "id"))+"/decision",
			jobj{"decision": decision, "reason": vs(v, "reason")}, nil)
	}
}

// ---------------------------------------------------------------- held images

// heldImagesResource is the Held images table: product pictures that media keeps private until a person looks (text, logos
// or watermarks on a main photo, a picture that does not match the title and brand, a photo another seller already used).
// The sheet shows the picture itself through a link media signs for 5 minutes, what the checks found and the mismatch
// score; releasing publishes the picture, rejecting deletes it and the seller reads the reason.
func heldImagesResource() *staffResource {
	list := func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
		var doc jobj
		if err := sc.get(ctx, sc.up.Media, "/v1/assets/held", url.Values{"limit": {strconv.Itoa(heldImagesLimit)}}, &doc); err != nil {
			return staffList{}, err
		}
		items := objs(doc, "assets")
		total := int64(len(items))
		return staffList{items: items, total: &total}, nil
	}
	paged := listAll(func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		page, err := list(ctx, sc, q)
		return page.items, err
	})
	return &staffResource{
		key: "held-images", module: "catalog", title: "Held images", statusKey: "state", exportable: true,
		columns: []domain.Column{col("fileName", "Photo", "strong", 20), col("ownerOrgName", "Seller", "text", 16), col("slot", "Used as", "text", 10),
			col("found", "What was found", "text", 32), col("waiting", "Waiting", "text", 14), col("due", "Answer due", "datetime", 14)},
		names: []nameRef{sellerRef("ownerOrg")},
		list:  paged,
		get:   findIn(list, "id"),
		cells: heldCells,
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "fileName"), "Product photo")
		},
		subtitleOf: func(o jobj) string { return jstr(o, "ownerOrgName") + " · " + heldSlot(o) },
		banner: func(o jobj) *domain.RecordBanner {
			return &domain.RecordBanner{Tone: "info", Title: "Private photo", Text: "The picture is private to staff. The link to it works for 5 minutes; close and open the sheet again if it stops loading."}
		},
		sections: func(o jobj) []domain.DetailSection {
			secs := []domain.DetailSection{}
			if u := jstr(o, "url"); strings.HasPrefix(u, "https://") {
				secs = append(secs, domain.DetailSection{Title: "Photo", Fields: []domain.DetailField{{Label: "Held photo", Value: u, Kind: "image"}}})
			}
			secs = append(secs, domain.DetailSection{Title: "Why it is held", Fields: heldFields(o)})
			return secs
		},
		actions: []staffAction{
			{key: "approve", label: "Release the photo", tone: "primary", permission: permCatalogModerate, also: []string{permMediaModerate}, level: domain.LevelApprove,
				form: reviewForm("Release the photo", "Release", "The photo is published and the listings that use it can go on."),
				run:  reviewImage("APPROVE")},
			{key: "reject", label: "Reject the photo", tone: "danger", permission: permCatalogModerate, also: []string{permMediaModerate}, level: domain.LevelApprove,
				form: reviewForm("Reject the photo", "Reject", "The photo is deleted and the seller reads your reason."), run: reviewImage("REJECT")},
		},
		rowActions: []string{"reject"},
		status:     func(o jobj) string { return "HELD" },
	}
}

func heldSlot(o jobj) string {
	switch jstr(o, "imageSlot") {
	case "MAIN":
		return "Main photo"
	case "LABEL":
		return "Label photo"
	case "SWATCH":
		return "Swatch"
	}
	return "Extra photo"
}

// heldFound lists what the checks and the AI found, in plain words.
func heldFound(o jobj) []string {
	found := []string{}
	for _, c := range objs(o, "checks") {
		if c["level"] != "HOLD" && c["level"] != "WARN" {
			continue
		}
		label := firstNonEmpty(heldCheckLabels[jstr(c, "code")], jstr(c, "message"))
		if label != "" && !slices.Contains(found, label) {
			found = append(found, label)
		}
	}
	return found
}

func heldCells(o jobj) map[string]any {
	due := ""
	waiting := ""
	if t, ok := millis(o, "createdAt"); ok {
		d := addBusinessDays(t, reviewSLABusiness)
		due = d.Format(time.RFC3339)
		hours := time.Since(t).Hours()
		waiting = waitingText(hours, time.Now().After(d))
	}
	return map[string]any{"slot": heldSlot(o), "found": strings.Join(heldFound(o), ", "), "waiting": waiting, "due": due}
}

func heldFields(o jobj) []domain.DetailField {
	fields := []domain.DetailField{
		fieldOf("Seller", jstr(o, "ownerOrgName"), "text"), fieldOf("Used as", heldSlot(o), "text"),
		fieldOf("Size", fmt.Sprintf("%d by %d pixels", int(num(o, "width")), int(num(o, "height"))), "text"),
		fieldOf("Uploaded", jstr(o, "createdAt"), "datetime"),
	}
	if found := heldFound(o); len(found) > 0 {
		fields = append(fields, fieldOf("Checks found", strings.Join(found, "; "), "text"))
	}
	for _, c := range objs(o, "checks") {
		if c["level"] == "HOLD" {
			fields = append(fields, fieldOf(firstNonEmpty(heldCheckLabels[jstr(c, "code")], domain.Humanize(jstr(c, "code"))), jstr(c, "message"), "text"))
		}
	}
	if labels := strs(o, "moderation.aiLabels"); len(labels) > 0 {
		names := make([]string, 0, len(labels))
		for _, l := range labels {
			names = append(names, domain.Humanize(l))
		}
		fields = append(fields, fieldOf("Labels the AI found", strings.Join(names, ", "), "text"))
	}
	if s, ok := at(o, "moderation.mismatchScore").(float64); ok {
		fields = append(fields, fieldOf("Match with the title and brand", fmt.Sprintf("%d%% mismatch (0%% matches, 100%% does not)", int(math.Round(s*100))), "text"))
	}
	if boolean(o, "moderation.duplicateHeld") {
		fields = append(fields, fieldOf("Another seller's photo", "A near-identical photo from another seller is why it was held", "text"))
	}
	if why := strs(o, "moderation.reasons"); len(why) > 0 {
		fields = append(fields, fieldOf("Notes", strings.Join(why, "; "), "text"))
	}
	return fields
}

func reviewImage(decision string) func(context.Context, staffCtx, jobj, jobj) error {
	return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
		return sc.post(ctx, sc.up.Media, "/v1/assets/"+url.PathEscape(jstr(o, "id"))+"/review",
			jobj{"decision": decision, "note": vs(v, "reason")}, nil)
	}
}

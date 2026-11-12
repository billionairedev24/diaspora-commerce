package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// reviewUpstream answers the catalog and media calls of the review queues from canned documents and records every call.
type reviewUpstream struct {
	name  string
	docs  map[string]any
	calls []port.StaffCall
}

func (u *reviewUpstream) Name() string { return u.name }

func (u *reviewUpstream) Call(_ context.Context, _ string, c port.StaffCall, out any) error {
	u.calls = append(u.calls, c)
	if c.Method == http.MethodPost || out == nil {
		return nil
	}
	doc, ok := u.docs[c.Path]
	if !ok {
		return domain.UpstreamUnavailable(u.name)
	}
	raw, _ := json.Marshal(doc)
	return json.Unmarshal(raw, out)
}

func (u *reviewUpstream) last() port.StaffCall { return u.calls[len(u.calls)-1] }

func (u *reviewUpstream) lastGet(path string) port.StaffCall {
	for i := len(u.calls) - 1; i >= 0; i-- {
		if u.calls[i].Path == path && u.calls[i].Method != http.MethodPost {
			return u.calls[i]
		}
	}
	return port.StaffCall{}
}

func reviewCtx(catalog, media *reviewUpstream) staffCtx {
	return staffCtx{up: port.StaffUpstreams{Catalog: catalog, Media: media}}
}

func action(t *testing.T, r *staffResource, o jobj, key string) staffAction {
	t.Helper()
	for _, a := range r.actionList(o) {
		if a.key == key {
			return a
		}
	}
	t.Fatalf("%s has no action %q", r.key, key)
	return staffAction{}
}

func values(kv ...any) jobj {
	out := jobj{}
	for i := 0; i+1 < len(kv); i += 2 {
		out[kv[i].(string)] = kv[i+1]
	}
	return out
}

func postedBody(t *testing.T, u *reviewUpstream) map[string]any {
	t.Helper()
	c := u.last()
	if c.Method != http.MethodPost {
		t.Fatalf("last call = %s %s, want a POST", c.Method, c.Path)
	}
	raw, _ := json.Marshal(c.Body)
	var body map[string]any
	_ = json.Unmarshal(raw, &body)
	return body
}

// ---------------------------------------------------------------- the three request tables

func TestRequestTablesAskCatalogForTheirOwnKindOfRequest(t *testing.T) {
	cat := &reviewUpstream{name: "catalog", docs: map[string]any{"/v1/change-requests": map[string]any{"items": []any{}, "total": 0}}}
	sc := reviewCtx(cat, nil)
	cases := map[string]func(url.Values) bool{
		"change-requests": func(v url.Values) bool { return v.Get("contentOnly") == "true" && v.Get("type") == "" },
		"wrong-matches":   func(v url.Values) bool { return v.Get("type") == "WRONG_MATCH" && v.Get("contentOnly") == "" },
		"duplicate-pages": func(v url.Values) bool { return v.Get("type") == "DUPLICATE_PAGE" && v.Get("contentOnly") == "" },
	}
	byKey := map[string]*staffResource{}
	for _, r := range catalogReviewResources() {
		byKey[r.key] = r
	}
	for key, ok := range cases {
		if _, err := byKey[key].list(context.Background(), sc, staffQuery{Status: "PENDING", Page: 2, Size: 25}); err != nil {
			t.Fatalf("%s: %v", key, err)
		}
		q := cat.lastGet("/v1/change-requests").Query
		if !ok(q) || q.Get("status") != "PENDING" || q.Get("page") != "2" || q.Get("size") != "25" {
			t.Errorf("%s asked catalog for %v", key, q)
		}
	}
}

func TestCatalogReviewResourcesAreRegisteredAndKeyedForTheModuleTables(t *testing.T) {
	all := staffResources()
	for _, key := range []string{"change-requests", "wrong-matches", "duplicate-pages", "gtin-exemptions", "held-images"} {
		r, ok := all[key]
		if !ok || r.module != "catalog" {
			t.Errorf("%s is not a catalog resource: %v", key, r)
		}
		found := false
		for _, k := range domain.ResourceKeys {
			found = found || k == key
		}
		if !found {
			t.Errorf("%s is not a resource key", key)
		}
	}
}

func TestEveryDecisionNeedsAReasonOfThreeCharactersAndCatalogModerate(t *testing.T) {
	for _, r := range catalogReviewResources() {
		for _, a := range r.actions {
			if a.permission != permCatalogModerate || a.level != domain.LevelApprove {
				t.Errorf("%s/%s needs catalog:moderate at the approve level, has %q %q", r.key, a.key, a.permission, a.level)
			}
			form := a.form(context.Background(), staffCtx{}, jobj{"type": "NEW_VARIANT", "atin": "AT00000001"})
			var reason *domain.FormField
			for i := range form.Fields {
				if form.Fields[i].Key == "reason" {
					reason = &form.Fields[i]
				}
			}
			if reason == nil || !reason.Required || reason.MinLength != 3 {
				t.Fatalf("%s/%s has no required reason of 3 characters: %+v", r.key, a.key, reason)
			}
			if errs := domain.ValidateForm(*form, values()); errs["reason"] == "" {
				t.Errorf("%s/%s accepts no reason", r.key, a.key)
			}
		}
	}
}

func TestApprovingAChangeSendsTheDecisionWithTheReasonAndTheVersionTheStaffMemberRead(t *testing.T) {
	cat := &reviewUpstream{name: "catalog"}
	sc := reviewCtx(cat, nil)
	r := changeRequestsResource()
	o := jobj{"id": "6f1d2c3e-0000-4000-8000-000000000001", "type": "TITLE", "status": "PENDING", "version": float64(3)}

	if err := action(t, r, o, "approve").run(context.Background(), sc, o, values("reason", "Matches the pack")); err != nil {
		t.Fatal(err)
	}
	body := postedBody(t, cat)
	if cat.last().Path != "/v1/change-requests/6f1d2c3e-0000-4000-8000-000000000001/decision" ||
		body["decision"] != "APPROVE" || body["reason"] != "Matches the pack" || body["expectedVersion"] != float64(3) {
		t.Fatalf("posted %s %v", cat.last().Path, body)
	}
	if _, has := body["resolutionParams"]; has {
		t.Fatalf("a title change has no parameters: %v", body)
	}

	if err := action(t, r, o, "reject").run(context.Background(), sc, o, values("reason", "Not on the pack")); err != nil {
		t.Fatal(err)
	}
	if body := postedBody(t, cat); body["decision"] != "REJECT" || body["reason"] != "Not on the pack" {
		t.Fatalf("rejected with %v", body)
	}
}

func TestANewVariantCanBeApprovedWithTheHoldForTax(t *testing.T) {
	cat := &reviewUpstream{name: "catalog"}
	r := changeRequestsResource()
	o := jobj{"id": "r1", "type": "NEW_VARIANT", "status": "PENDING", "version": float64(0)}

	form := action(t, r, o, "approve").form(context.Background(), staffCtx{}, o)
	hold := false
	for _, f := range form.Fields {
		hold = hold || f.Key == "holdPageForTax" && f.Type == "toggle"
	}
	if !hold {
		t.Fatalf("a new variant asks whether the page may leave sale: %+v", form.Fields)
	}
	if err := action(t, r, o, "approve").run(context.Background(), reviewCtx(cat, nil), o, values("reason", "A real size", "holdPageForTax", true)); err != nil {
		t.Fatal(err)
	}
	params, _ := postedBody(t, cat)["resolutionParams"].(map[string]any)
	if params["holdPageForTax"] != true {
		t.Fatalf("params = %v", params)
	}

	plain := jobj{"id": "r2", "type": "TITLE", "status": "PENDING"}
	for _, f := range action(t, r, plain, "approve").form(context.Background(), staffCtx{}, plain).Fields {
		if f.Key == "holdPageForTax" {
			t.Fatal("only a new variant has the hold")
		}
	}
}

func TestADecidedRequestCannotBeDecidedAgain(t *testing.T) {
	r := changeRequestsResource()
	for _, status := range []string{"APPLIED", "REJECTED", "WITHDRAWN"} {
		o := jobj{"status": status}
		for _, a := range r.actions {
			if reason := a.when(staffCtx{}, o); reason == "" {
				t.Errorf("%s is possible on a %s request", a.key, status)
			}
		}
	}
}

func TestTheChangeRequestSheetShowsNamesTheAskedChangeAndThePictures(t *testing.T) {
	r := changeRequestsResource()
	o := jobj{"type": "IMAGE_REPLACE", "title": "Ankara tote", "atin": "AT00000001", "requestedByOrgName": "Kente House", "status": "PENDING",
		"payload": jobj{"url": "https://cdn.example/new.jpg", "position": float64(0)}, "ageHours": float64(60), "overdue": true, "note": "The old one is blurry",
		"page": jobj{"title": "Ankara tote", "status": "LIVE", "version": float64(4), "images": []any{jobj{"url": "https://cdn.example/old.jpg"}}}}

	sections := r.sections(o)
	flat := map[string]string{}
	var gallery []domain.DetailField
	for _, s := range sections {
		for _, f := range s.Fields {
			flat[f.Label] = f.Value
		}
		if s.Title == "Picture" {
			gallery = s.Fields
		}
	}
	if flat["Asked by"] != "Kente House" || flat["Product page"] != "Ankara tote" || flat["Waiting"] != "2 days · overdue" || flat["Seller's note"] != "The old one is blurry" {
		t.Fatalf("sheet = %v", flat)
	}
	if len(gallery) != 2 || gallery[0].Kind != "image" || gallery[0].Value != "https://cdn.example/new.jpg" || gallery[1].Value != "https://cdn.example/old.jpg" {
		t.Fatalf("pictures = %+v", gallery)
	}
	for _, v := range flat {
		if strings.HasPrefix(v, "org_") {
			t.Fatalf("an id reached the sheet: %v", flat)
		}
	}
	row := r.cells(o)
	if row["change"] != "Replace a picture" || row["waiting"] != "2 days · overdue" {
		t.Fatalf("row = %v", row)
	}
}

func TestADecidedRequestShowsWhoDecidedAndWhy(t *testing.T) {
	r := changeRequestsResource()
	o := jobj{"type": "TITLE", "status": "APPLIED", "decidedByName": "Mariam Bello", "reason": "Matches the pack", "resolution": "NEW_VARIANT", "decidedAt": "2026-11-13T10:00:00Z"}
	flat := map[string]string{}
	for _, s := range r.sections(o) {
		if s.Title == "Decision" {
			for _, f := range s.Fields {
				flat[f.Label] = f.Value
			}
		}
	}
	if flat["Decided by"] != "Mariam Bello" || flat["Reason"] != "Matches the pack" || flat["How"] != "New variant added" {
		t.Fatalf("decision = %v", flat)
	}
}

// ---------------------------------------------------------------- wrong matches and merges

func TestAWrongMatchHasFourWaysToAnswerAndEachSendsItsResolution(t *testing.T) {
	r := wrongMatchesResource()
	o := jobj{"id": "w1", "type": "WRONG_MATCH", "status": "PENDING", "version": float64(1), "variantAtin": "AT00000002"}
	cases := []struct {
		key, resolution string
		in              jobj
		params          map[string]any
	}{
		{"correct", "CORRECT_GTIN", values("reason", "Seller is right", "gtin", "4006381333931"), map[string]any{"gtin": "4006381333931"}},
		{"move", "MOVE_VARIANT", values("reason", "It is a size of the other dress", "targetAtin", "at00000005", "holdPageForTax", true),
			map[string]any{"targetAtin": "AT00000005", "holdPageForTax": true}},
		{"split", "SPLIT_VARIANT", values("reason", "A different dress"), nil},
	}
	for _, c := range cases {
		cat := &reviewUpstream{name: "catalog"}
		a := action(t, r, o, c.key)
		if errs := domain.ValidateForm(*a.form(context.Background(), staffCtx{}, o), c.in); len(errs) > 0 {
			t.Fatalf("%s form rejects %v: %v", c.key, c.in, errs)
		}
		if err := a.run(context.Background(), reviewCtx(cat, nil), o, c.in); err != nil {
			t.Fatal(err)
		}
		body := postedBody(t, cat)
		if body["decision"] != "APPROVE" || body["resolution"] != c.resolution {
			t.Errorf("%s posted %v", c.key, body)
		}
		for k, want := range c.params {
			if got, _ := body["resolutionParams"].(map[string]any); got[k] != want {
				t.Errorf("%s params = %v, want %s=%v", c.key, got, k, want)
			}
		}
	}
	cat := &reviewUpstream{name: "catalog"}
	if err := action(t, r, o, "reject").run(context.Background(), reviewCtx(cat, nil), o, values("reason", "The barcode is right")); err != nil {
		t.Fatal(err)
	}
	if body := postedBody(t, cat); body["decision"] != "REJECT" || body["resolution"] != nil {
		t.Errorf("reject posted %v", body)
	}
}

func TestAWrongMatchSheetNamesTheProductTheSellerWasShown(t *testing.T) {
	cat := &reviewUpstream{name: "catalog", docs: map[string]any{
		"/v1/change-requests/w1": map[string]any{"id": "w1", "atin": "AT00000001", "title": "Ankara dress", "type": "WRONG_MATCH", "status": "PENDING",
			"variantAtin": "AT00000002", "payload": map[string]any{"gtin": "012345678905"}, "requestedByOrg": "org_b"},
		"/v1/products/atin/AT00000001": map[string]any{"atin": "AT00000001", "title": "Ankara dress", "status": "LIVE", "variants": []any{
			map[string]any{"atin": "AT00000002", "gtin": "0012345678905", "options": map[string]any{"Size": "M", "Color": "Gold"}}}},
	}}
	r := wrongMatchesResource()
	o, err := r.get(context.Background(), reviewCtx(cat, nil), "w1")
	if err != nil {
		t.Fatal(err)
	}
	flat := map[string]string{}
	for _, s := range r.sections(o) {
		for _, f := range s.Fields {
			flat[f.Label] = f.Value
		}
	}
	if flat["Barcode typed"] != "012345678905" || flat["Product shown"] != "Color: Gold, Size: M" || flat["Barcode on that product"] != "0012345678905" {
		t.Fatalf("sheet = %v", flat)
	}
}

func TestMergingNamesTheSurvivorAndReadsTheVariantMap(t *testing.T) {
	cat := &reviewUpstream{name: "catalog"}
	r := duplicatePagesResource()
	o := jobj{"id": "d1", "type": "DUPLICATE_PAGE", "status": "PENDING", "version": float64(0), "atin": "AT00000001",
		"page": jobj{"title": "Ankara tote", "atin": "AT00000001"}, "otherPage": jobj{"title": "Ankara tote bag", "atin": "AT00000009"}}
	form := action(t, r, o, "merge").form(context.Background(), staffCtx{}, o)
	var survivor domain.FormField
	for _, f := range form.Fields {
		if f.Key == "survivorAtin" {
			survivor = f
		}
	}
	if len(survivor.Options) != 2 || !strings.Contains(survivor.Options[0].Label, "Ankara tote") || !strings.Contains(survivor.Options[1].Label, "Ankara tote bag") {
		t.Fatalf("the survivor is chosen by page name: %+v", survivor.Options)
	}
	in := values("reason", "Same product, two pages", "survivorAtin", "at00000009", "variantMap", "at00000002 = at00000010\nAT00000003=AT00000011\nnonsense")
	if err := action(t, r, o, "merge").run(context.Background(), reviewCtx(cat, nil), o, in); err != nil {
		t.Fatal(err)
	}
	body := postedBody(t, cat)
	params, _ := body["resolutionParams"].(map[string]any)
	vm, _ := params["variantMap"].(map[string]any)
	if body["resolution"] != "MERGE" || params["survivorAtin"] != "AT00000009" || vm["AT00000002"] != "AT00000010" || vm["AT00000003"] != "AT00000011" || len(vm) != 2 {
		t.Fatalf("posted %v", body)
	}
}

func TestADuplicateSheetShowsBothPagesWithTheirVariants(t *testing.T) {
	r := duplicatePagesResource()
	o := jobj{"type": "DUPLICATE_PAGE", "status": "PENDING", "atin": "AT00000001", "title": "Ankara tote", "requestedByOrgName": "Kente House",
		"page":      jobj{"title": "Ankara tote", "atin": "AT00000001", "status": "LIVE", "variants": []any{jobj{"atin": "AT00000002", "gtin": "012345678905"}}},
		"otherPage": jobj{"title": "Ankara tote bag", "atin": "AT00000009", "status": "IN_REVIEW", "variants": []any{jobj{"atin": "AT00000010"}}}}
	titles := []string{}
	for _, s := range r.sections(o) {
		titles = append(titles, s.Title)
	}
	if strings.Join(titles, "|") != "Request|This page|The other page" {
		t.Fatalf("sections = %v", titles)
	}
	tables := r.tables(o)
	if len(tables) != 2 || len(tables[1].Rows) != 1 || tables[1].Rows[0].Cells["gtin"] != "No barcode" {
		t.Fatalf("tables = %+v", tables)
	}
}

// ---------------------------------------------------------------- no-barcode items

func TestExemptionDecisionsAreSentWithTheReason(t *testing.T) {
	cat := &reviewUpstream{name: "catalog"}
	r := gtinExemptionsResource()
	o := jobj{"id": "e1", "status": "PENDING"}
	if err := action(t, r, o, "approve").run(context.Background(), reviewCtx(cat, nil), o, values("reason", "Handmade item")); err != nil {
		t.Fatal(err)
	}
	if body := postedBody(t, cat); cat.last().Path != "/v1/gtin-exemptions/e1/decision" || body["decision"] != "APPROVE" || body["reason"] != "Handmade item" {
		t.Fatalf("posted %s %v", cat.last().Path, body)
	}
	if err := action(t, r, o, "reject").run(context.Background(), reviewCtx(cat, nil), o, values("reason", "This brand has barcodes")); err != nil {
		t.Fatal(err)
	}
	if body := postedBody(t, cat); body["decision"] != "REJECT" {
		t.Fatalf("posted %v", body)
	}
}

func TestTheExemptionSheetShowsTheDuplicateHintsToStaff(t *testing.T) {
	r := gtinExemptionsResource()
	o := jobj{"title": "Hand carved stool", "productAtin": "AT10000001", "requestedByOrgName": "Kente House", "status": "PENDING", "brand": "Adire Lagos", "mpn": "AB-123",
		"requiredBecause": "KNOWN_GS1_BRAND", "sellerMessage": "We are checking that this item has no barcode.", "ageHours": float64(5),
		"duplicateHints": []any{jobj{"atin": "AT10000009", "title": "Hand carved teak stool", "brand": "Adire Lagos", "mpn": "ab123", "score": 1.0, "matchedOn": []any{"BRAND_MPN", "TITLE"}}}}
	tables := r.tables(o)
	if len(tables) != 1 || len(tables[0].Rows) != 1 {
		t.Fatalf("hints = %+v", tables)
	}
	row := tables[0].Rows[0].Cells
	if row["why"] != "brand and part number and title" || row["score"] != "100%" || row["title"] != "Hand carved teak stool" {
		t.Fatalf("hint row = %v", row)
	}
	if cells := r.cells(o); cells["because"] != "Brand that carries barcodes on other pages" || cells["waiting"] != "5 h" {
		t.Fatalf("row = %v", cells)
	}
	if len(r.tables(jobj{"status": "PENDING"})) != 0 {
		t.Fatal("no hints, no table")
	}
}

func TestAnExemptionIsFoundInTheQueueByItsId(t *testing.T) {
	cat := &reviewUpstream{name: "catalog", docs: map[string]any{"/v1/gtin-exemptions": map[string]any{"items": []any{map[string]any{"id": "e1", "status": "PENDING"}}, "total": 1}}}
	r := gtinExemptionsResource()
	o, err := r.get(context.Background(), reviewCtx(cat, nil), "e1")
	if err != nil || jstr(o, "id") != "e1" {
		t.Fatalf("found %v %v", o, err)
	}
	if _, err := r.get(context.Background(), reviewCtx(cat, nil), "nope"); err == nil {
		t.Fatal("an unknown request is not found")
	}
}

// ---------------------------------------------------------------- held images

func heldDoc() map[string]any {
	return map[string]any{"assets": []any{map[string]any{
		"id": "a1", "fileName": "tote-main.jpg", "ownerOrg": "org_b", "imageSlot": "MAIN", "width": 1600, "height": 1200, "createdAt": "2026-11-09T10:00:00Z",
		"url": "https://storage.example/signed/a1?sig=x", "urlExpiresAt": "2026-11-09T10:05:00Z", "verdict": "HOLD",
		"checks": []any{map[string]any{"code": "HELD_TEXT", "level": "HOLD", "message": "We are checking this photo for text, logos or watermarks."},
			map[string]any{"code": "LOW_RES", "level": "WARN", "message": "This photo is under 1000 pixels."}},
		"moderation": map[string]any{"verdict": "HELD", "aiLabels": []any{"WATERMARK"}, "mismatchScore": 0.62},
	}}}
}

func TestTheHeldImageQueueIsMediasHeldListAndTheSheetShowsThePhotoItsChecksAndTheScore(t *testing.T) {
	media := &reviewUpstream{name: "media", docs: map[string]any{"/v1/assets/held": heldDoc()}}
	sc := reviewCtx(nil, media)
	r := heldImagesResource()

	page, err := r.list(context.Background(), sc, staffQuery{Size: 25})
	if err != nil || len(page.items) != 1 || *page.total != 1 {
		t.Fatalf("list = %+v %v", page, err)
	}
	if media.lastGet("/v1/assets/held").Query.Get("limit") != "100" {
		t.Fatalf("query = %v", media.last().Query)
	}
	o, err := r.get(context.Background(), sc, "a1")
	if err != nil {
		t.Fatal(err)
	}
	var photo []domain.DetailField
	flat := map[string]string{}
	for _, s := range r.sections(o) {
		if s.Title == "Photo" {
			photo = s.Fields
		}
		for _, f := range s.Fields {
			flat[f.Label] = f.Value
		}
	}
	if len(photo) != 1 || photo[0].Kind != "image" || !strings.HasPrefix(photo[0].Value, "https://") {
		t.Fatalf("photo = %+v", photo)
	}
	if flat["Checks found"] != "Text, logo or watermark; This photo is under 1000 pixels." || flat["Labels the AI found"] != "Watermark" ||
		flat["Match with the title and brand"] != "62% mismatch (0% matches, 100% does not)" || flat["Used as"] != "Main photo" {
		t.Fatalf("sheet = %v", flat)
	}
	if b := r.banner(o); b == nil || !strings.Contains(b.Text, "5 minutes") {
		t.Fatalf("banner = %+v", b)
	}
	row := r.cells(o)
	if row["found"] != "Text, logo or watermark, This photo is under 1000 pixels." || row["slot"] != "Main photo" || row["due"] != "2026-11-11T10:00:00Z" {
		t.Fatalf("row = %v", row)
	}
}

func TestReleasingOrRejectingAHeldImageGoesToMediaWithTheReasonAsTheNote(t *testing.T) {
	media := &reviewUpstream{name: "media"}
	sc := reviewCtx(nil, media)
	r := heldImagesResource()
	o := jobj{"id": "a1"}
	if err := action(t, r, o, "approve").run(context.Background(), sc, o, values("reason", "Packaging text only")); err != nil {
		t.Fatal(err)
	}
	if body := postedBody(t, media); media.last().Path != "/v1/assets/a1/review" || body["decision"] != "APPROVE" || body["note"] != "Packaging text only" {
		t.Fatalf("posted %s %v", media.last().Path, body)
	}
	if err := action(t, r, o, "reject").run(context.Background(), sc, o, values("reason", "A watermark of another shop")); err != nil {
		t.Fatal(err)
	}
	if body := postedBody(t, media); body["decision"] != "REJECT" || body["note"] != "A watermark of another shop" {
		t.Fatalf("posted %v", body)
	}
	for _, a := range r.actions {
		if !strings.Contains(strings.Join(append([]string{a.permission}, a.also...), " "), "media:moderate") {
			t.Errorf("%s is not open to media moderators", a.key)
		}
	}
}

// ---------------------------------------------------------------- time

func TestTheServiceLevelCountsBusinessDays(t *testing.T) {
	friday := time.Date(2026, 11, 13, 10, 0, 0, 0, time.UTC)
	if got := addBusinessDays(friday, 2); !got.Equal(time.Date(2026, 11, 17, 10, 0, 0, 0, time.UTC)) {
		t.Fatalf("two business days after a Friday = %v", got)
	}
	if got := addBusinessDays(friday.AddDate(0, 0, -1), 2); !got.Equal(time.Date(2026, 11, 16, 10, 0, 0, 0, time.UTC)) {
		t.Fatalf("two business days after a Thursday = %v", got)
	}
}

func TestWaitingReadsInPlainUnits(t *testing.T) {
	for in, want := range map[float64]string{0: "Under an hour", 5: "5 h", 47: "47 h", 72: "3 days"} {
		if got := waitingText(in, false); got != want {
			t.Errorf("%v hours = %q, want %q", in, got, want)
		}
	}
	if got := waitingText(80, true); got != "3 days · overdue" {
		t.Errorf("overdue = %q", got)
	}
}

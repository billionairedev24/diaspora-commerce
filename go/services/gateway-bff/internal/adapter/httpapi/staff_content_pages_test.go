package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// contentStaff: Super Admin (Site content FULL), a Marketing editor (EDIT: drafts only for legal documents) and a Support
// agent (VIEW).
var contentStaff = map[string][]any{
	"admin":     {[]string{"platform.super_admin"}, []string{"content:legal.view", "content:legal.edit", "content:site.view", "content:site.edit"}},
	"marketer":  {[]string{"platform.marketing"}, []string{"content:legal.view", "content:legal.edit", "content:site.view", "content:site.edit"}},
	"support":   {[]string{"platform.customer_support"}, []string{"content:legal.view", "content:site.view"}},
	"approver2": {[]string{"platform.super_admin"}, []string{"content:legal.view", "content:legal.edit", "content:site.view", "content:site.edit"}},
}

// fakeContent is content's legal and help authoring API with its rules: one draft per page, row-version checks, a
// second approver for legal documents and a revision per published help change.
type fakeContent struct {
	mu          sync.Mutex
	legalDraft  map[string]any
	helpDraft   map[string]any
	helpTitle   string
	helpBody    string
	revisions   []map[string]any
	legalLatest string
	calls       []string
}

const (
	fakeLegalV1 = "# Privacy\n\n## Data we keep\n\nWrite to {legal}."
	fakeLegalV2 = "# Privacy\n\n## Data we keep\n\nWrite to {legal}. [[LEGAL: Q4 retention]]"
)

func (f *fakeContent) helpArticle() map[string]any {
	return map[string]any{"slug": "shipping", "topic": "ORDERS_DELIVERY", "topicTitle": "Orders & delivery", "title": f.helpTitle,
		"summary": "Carriers and pickup", "sections": []any{map[string]any{"heading": "Carrier shipping", "body": f.helpBody}},
		"status": "PUBLISHED", "sortOrder": 10, "updatedAt": "2026-10-01T10:00:00Z", "version": len(f.revisions) - 1, "updatedBy": "Maya Obi",
		"hasDraft": f.helpDraft != nil, "revision": len(f.revisions)}
}

func (f *fakeContent) legalHistory() []any {
	return []any{
		map[string]any{"version": f.legalLatest, "effectiveDate": "2026-09-01", "status": "CURRENT", "draftBanner": true, "locales": []string{"en", "fr"},
			"changeSummary": "Launch wording", "publishedBy": "Maya Obi", "publishedAt": "2026-09-01T09:00:00Z"},
		map[string]any{"version": "0.9", "effectiveDate": "2026-06-01", "status": "ARCHIVED", "draftBanner": true, "locales": []string{"en"},
			"changeSummary": "First draft", "publishedBy": "Sam Eze", "publishedAt": "2026-06-01T09:00:00Z", "archivedAt": "2026-09-01T09:00:00Z"},
	}
}

func (f *fakeContent) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.calls = append(f.calls, r.Method+" "+p)
	body := map[string]any{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	if p == "/v1/me" {
		who, ok := contentStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": who[0], "permissions": who[1]})
		return
	}
	if p == "/v1/admin/module-access" {
		problemJ(w, 403, "identity.forbidden")
		return
	}
	actor := map[string]string{"admin": "Ada Admin", "marketer": "Mia Marketer", "approver2": "Ola Approver", "support": "Sue Support"}[token]
	switch {
	case p == "/v1/legal/overview":
		overview := []any{}
		for _, k := range []string{"privacy", "returns"} {
			e := map[string]any{"key": k, "title": strings.ToUpper(k[:1]) + k[1:], "version": "1.0", "effectiveDate": "2026-09-01", "draftBanner": k == "privacy",
				"publishedBy": "Maya Obi", "publishedAt": "2026-09-01T09:00:00Z", "hasDraft": k == "privacy" && f.legalDraft != nil}
			if e["hasDraft"] == true {
				e["draftVersion"], e["draftUpdatedBy"], e["draftUpdatedAt"] = f.legalDraft["version"], f.legalDraft["updatedBy"], f.legalDraft["updatedAt"]
			}
			overview = append(overview, e)
		}
		writeJ(w, 200, overview)
	case p == "/v1/help/admin/articles":
		writeJ(w, 200, []any{f.helpArticle()})
	case p == "/v1/site-config/settings":
		writeJ(w, 200, map[string]any{"contacts": map[string]any{"supportPhoneDisplay": "+1 (833) 555-0199", "supportPhone": "+18335550199",
			"emails": map[string]any{"legal": "legal@afrotransact.test"}, "address": map[string]any{"name": "AfroTransact, Inc.", "line1": "1001 Texas Ave", "city": "Houston", "region": "TX", "postalCode": "77002"}},
			"smsProgramName": "AfroTransact Alerts"})
	case p == "/v1/legal/privacy/history":
		writeJ(w, 200, f.legalHistory())
	case p == "/v1/legal/privacy/versions/1.0/source" || p == "/v1/legal/privacy/versions/"+f.legalLatest+"/source":
		writeJ(w, 200, map[string]any{"key": "privacy", "title": "Privacy Notice", "version": f.legalLatest, "effectiveDate": "2026-09-01", "status": "CURRENT", "draftBanner": true,
			"bodies": map[string]any{"en": fakeLegalV1, "fr": "# Confidentialité"}, "changeSummary": "Launch wording", "publishedBy": "Maya Obi", "publishedAt": "2026-09-01T09:00:00Z"})
	case p == "/v1/legal/privacy/versions/0.9/source":
		writeJ(w, 200, map[string]any{"key": "privacy", "title": "Privacy Notice", "version": "0.9", "effectiveDate": "2026-06-01", "status": "ARCHIVED", "draftBanner": true,
			"bodies": map[string]any{"en": "# Privacy\n\n## Data we keep\n\nOld."}, "changeSummary": "First draft", "publishedBy": "Sam Eze", "publishedAt": "2026-06-01T09:00:00Z"})
	case strings.HasPrefix(p, "/v1/legal/privacy/versions/"):
		problemJ(w, 404, "content.legal-version-not-found")
	case p == "/v1/legal/privacy/draft" && r.Method == http.MethodGet:
		if f.legalDraft == nil {
			problemJ(w, 404, "content.legal-draft-not-found")
			return
		}
		writeJ(w, 200, f.legalDraft)
	case p == "/v1/legal/privacy/draft" && r.Method == http.MethodPut:
		if f.legalDraft != nil && body["rowVersion"] != f.legalDraft["rowVersion"] {
			problemJ(w, 409, "content.stale-legal-draft")
			return
		}
		row := float64(1)
		if f.legalDraft != nil {
			row = f.legalDraft["rowVersion"].(float64) + 1
		}
		f.legalDraft = map[string]any{"key": "privacy", "title": "Privacy Notice", "version": body["version"], "baseVersion": "1.0", "effectiveDate": body["effectiveDate"],
			"draftBanner": body["draftBanner"], "bodies": body["bodies"], "needsReview": body["needsReview"], "changeSummary": body["changeSummary"],
			"unresolvedMarkers": map[string]any{}, "rowVersion": row, "createdBy": actor, "createdAt": "2026-10-10T10:00:00Z", "updatedBy": actor, "updatedAt": "2026-10-10T10:05:00Z"}
		writeJ(w, 200, f.legalDraft)
	case p == "/v1/legal/privacy/draft" && r.Method == http.MethodDelete:
		f.legalDraft = nil
		w.WriteHeader(204)
	case p == "/v1/legal/privacy/draft/publication":
		switch {
		case f.legalDraft == nil:
			problemJ(w, 404, "content.legal-draft-not-found")
		case f.legalDraft["updatedBy"] == actor:
			problemJ(w, 403, "content.second-approver-required")
		case body["rowVersion"] != f.legalDraft["rowVersion"]:
			problemJ(w, 409, "content.stale-legal-draft")
		default:
			f.legalLatest = f.legalDraft["version"].(string)
			f.legalDraft = nil
			writeJ(w, 201, map[string]any{"key": "privacy", "version": f.legalLatest, "status": "CURRENT"})
		}
	case p == "/v1/legal/privacy/draft/restoration":
		if f.legalDraft != nil {
			problemJ(w, 409, "content.legal-draft-exists")
			return
		}
		f.legalDraft = map[string]any{"key": "privacy", "title": "Privacy Notice", "version": "1.1", "baseVersion": "1.0", "effectiveDate": "2026-09-01", "draftBanner": true,
			"bodies": map[string]any{"en": "# Privacy\n\n## Data we keep\n\nOld."}, "needsReview": []string{}, "changeSummary": "Restores version " + body["version"].(string),
			"unresolvedMarkers": map[string]any{}, "rowVersion": float64(0), "createdBy": actor, "createdAt": "2026-10-10T10:00:00Z", "updatedBy": actor, "updatedAt": "2026-10-10T10:00:00Z"}
		writeJ(w, 201, f.legalDraft)
	case p == "/v1/help/articles/shipping/revisions":
		out := []any{}
		for i := len(f.revisions) - 1; i >= 0; i-- {
			out = append(out, f.revisions[i])
		}
		writeJ(w, 200, out)
	case strings.HasPrefix(p, "/v1/help/articles/shipping/revisions/") && strings.HasSuffix(p, "/restoration"):
		n := int(strings.Count(p, "")) // revision 1 is the only one the test restores
		_ = n
		first := f.revisions[0]
		sections := first["sections"].([]any)
		f.helpTitle, f.helpBody = first["title"].(string), sections[0].(map[string]any)["body"].(string)
		f.revisions = append(f.revisions, map[string]any{"slug": "shipping", "revision": len(f.revisions) + 1, "topic": "ORDERS_DELIVERY", "topicTitle": "Orders & delivery",
			"title": f.helpTitle, "summary": "Carriers and pickup", "sections": sections, "status": "PUBLISHED", "sortOrder": 10, "changeSummary": "Restores revision 1",
			"reason": body["reason"], "publishedBy": actor, "publishedAt": "2026-10-11T10:00:00Z"})
		writeJ(w, 201, f.helpArticle())
	case strings.HasPrefix(p, "/v1/help/articles/shipping/revisions/"):
		if strings.HasSuffix(p, "/1") {
			writeJ(w, 200, f.revisions[0])
			return
		}
		problemJ(w, 404, "content.help-revision-not-found")
	case p == "/v1/help/articles/shipping/draft" && r.Method == http.MethodGet:
		if f.helpDraft == nil {
			problemJ(w, 404, "content.help-draft-not-found")
			return
		}
		writeJ(w, 200, f.helpDraft)
	case p == "/v1/help/articles/shipping/draft" && r.Method == http.MethodPut:
		row := float64(1)
		if f.helpDraft != nil {
			if body["rowVersion"] != f.helpDraft["rowVersion"] {
				problemJ(w, 409, "content.stale-help-draft")
				return
			}
			row = f.helpDraft["rowVersion"].(float64) + 1
		}
		f.helpDraft = map[string]any{"slug": "shipping", "topic": body["topic"], "topicTitle": "Orders & delivery", "title": body["title"], "summary": body["summary"],
			"sections": body["sections"], "sortOrder": body["sortOrder"], "changeSummary": body["changeSummary"], "baseVersion": 0, "rowVersion": row,
			"createdBy": actor, "createdAt": "2026-10-10T10:00:00Z", "updatedBy": actor, "updatedAt": "2026-10-10T10:05:00Z"}
		writeJ(w, 200, f.helpDraft)
	case p == "/v1/help/articles/shipping/draft" && r.Method == http.MethodDelete:
		f.helpDraft = nil
		w.WriteHeader(204)
	case p == "/v1/help/articles/shipping/draft/publication":
		if f.helpDraft == nil || body["rowVersion"] != f.helpDraft["rowVersion"] {
			problemJ(w, 409, "content.stale-help-draft")
			return
		}
		sections := f.helpDraft["sections"].([]any)
		f.helpTitle, f.helpBody = f.helpDraft["title"].(string), sections[0].(map[string]any)["body"].(string)
		f.revisions = append(f.revisions, map[string]any{"slug": "shipping", "revision": len(f.revisions) + 1, "topic": "ORDERS_DELIVERY", "topicTitle": "Orders & delivery",
			"title": f.helpTitle, "summary": f.helpDraft["summary"], "sections": sections, "status": "PUBLISHED", "sortOrder": 10, "changeSummary": f.helpDraft["changeSummary"],
			"reason": body["reason"], "publishedBy": actor, "publishedAt": "2026-10-11T09:00:00Z"})
		f.helpDraft = nil
		writeJ(w, 201, f.helpArticle())
	default:
		problemJ(w, 404, "not-found")
	}
}

func contentBFF(t *testing.T) (*fakeContent, func(token, method, path string, body any) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeContent{helpTitle: "Shipping & delivery", helpBody: "USPS, UPS and FedEx.", legalLatest: "1.0"}
	f.revisions = []map[string]any{{"slug": "shipping", "revision": 1, "topic": "ORDERS_DELIVERY", "topicTitle": "Orders & delivery", "title": "Shipping & delivery",
		"summary": "Carriers and pickup", "sections": []any{map[string]any{"heading": "Carrier shipping", "body": "USPS, UPS and FedEx."}}, "status": "PUBLISHED",
		"sortOrder": 10, "changeSummary": "Initial content", "reason": "Initial load", "publishedAt": "2026-08-01T09:00:00Z"}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Content: up("content")}, StorefrontURL: "https://shop.example.test/",
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), ContentPages: app.NewStaffContentPagesService(d)}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	return f, func(token, method, path string, body any) *httptest.ResponseRecorder {
		var rd io.Reader
		if body != nil {
			raw, _ := json.Marshal(body)
			rd = bytes.NewReader(raw)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
}

// TestStaffContentPages drives Settings → Published pages through the BFF: the index with absolute live links, the
// legal and help editors (preview data, draft, second approver, publication with a reason, rollback) and the access
// rules, and checks every answer against gateway-bff-staff.yaml. Every operation of the tag must be exercised.
func TestStaffContentPages(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := contentBFF(t)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
		}
		validated[route.Operation.OperationID]++
		if rec.Body.Len() == 0 {
			return nil
		}
		return jsonOf(t, rec)
	}

	// Index: absolute live links on the storefront origin, names not ids, the draft flag, record rows and the developer-only page.
	idx := do("admin", "GET", "/v1/staff/content/pages", nil, 200)
	if idx["storefrontUrl"] != "https://shop.example.test" {
		t.Errorf("storefrontUrl = %v", idx["storefrontUrl"])
	}
	rows := map[string]map[string]any{}
	for _, it := range idx["items"].([]any) {
		m := it.(map[string]any)
		rows[m["kind"].(string)+"/"+m["key"].(string)] = m
	}
	privacy := rows["legal/privacy"]
	if privacy["liveUrl"] != "https://shop.example.test/legal/privacy" || privacy["livePath"] != "/legal/privacy" || privacy["updatedByName"] != "Maya Obi" || privacy["status"] != "DRAFT_FOR_COUNSEL" {
		t.Errorf("privacy row = %v", privacy)
	}
	if rows["help/shipping"]["liveUrl"] != "https://shop.example.test/help/shipping" || rows["help/shipping"]["version"] != "1" {
		t.Errorf("shipping row = %v", rows["help/shipping"])
	}
	if rows["record/about"]["editor"] != "developer" || rows["record/site-settings"]["resource"] != "site-settings" || rows["record/notification-templates"]["consoleHref"] == nil {
		t.Errorf("record rows = %v", rows)
	}
	for _, r := range rows {
		if u, ok := r["liveUrl"].(string); ok && !strings.HasPrefix(u, "https://") {
			t.Errorf("live link %q is not absolute", u)
		}
	}

	// Legal: the page as published, with placeholders and the history.
	page := do("admin", "GET", "/v1/staff/content/pages/legal/privacy", nil, 200)
	pub := page["published"].(map[string]any)
	if pub["version"] != "1.0" || pub["bodies"].(map[string]any)["en"] != fakeLegalV1 || page["draft"] != nil || len(page["versions"].([]any)) != 2 {
		t.Errorf("legal page = %v", page)
	}
	if page["placeholders"].(map[string]any)["legal"] != "legal@afrotransact.test" || !strings.Contains(page["placeholders"].(map[string]any)["address"].(string), "Houston, TX 77002") {
		t.Errorf("placeholders = %v", page["placeholders"])
	}
	if page["liveUrl"] != "https://shop.example.test/legal/privacy" {
		t.Errorf("liveUrl = %v", page["liveUrl"])
	}
	old := do("admin", "GET", "/v1/staff/content/pages/legal/privacy/versions/0.9", nil, 200)
	if old["bodies"].(map[string]any)["en"] == nil || old["updatedByName"] != "Sam Eze" {
		t.Errorf("old version = %v", old)
	}
	do("admin", "GET", "/v1/staff/content/pages/legal/privacy/versions/7.7", nil, 404)
	do("admin", "GET", "/v1/staff/content/pages/legal/nope", nil, 404)
	do("admin", "GET", "/v1/staff/content/pages/faq/privacy", nil, 404)

	// A marketing editor saves a draft; nothing public changes; a stale or invalid save is refused.
	draftBody := func(en string, row any) map[string]any {
		b := map[string]any{"version": "1.1", "effectiveDate": "2026-11-01", "draftBanner": false, "bodies": map[string]any{"en": en, "fr": "# Confidentialité"},
			"needsReview": []string{"fr"}, "changeSummary": "Retention period"}
		if row != nil {
			b["rowVersion"] = row
		}
		return b
	}
	do("marketer", "PUT", "/v1/staff/content/pages/legal/privacy/draft", draftBody("", nil), 422)
	do("marketer", "PUT", "/v1/staff/content/pages/legal/privacy/draft", draftBody("# P\n\n## A\n\nx\n\n## a\n\ny", nil), 422)
	do("marketer", "PUT", "/v1/staff/content/pages/legal/privacy/draft", draftBody("# P\n\n## A\n\n[broken](javascript:alert(1))", nil), 422)
	saved := do("marketer", "PUT", "/v1/staff/content/pages/legal/privacy/draft", draftBody(fakeLegalV2, nil), 200)
	if saved["rowVersion"] != float64(1) || saved["updatedByName"] != "Mia Marketer" || saved["needsReview"].([]any)[0] != "fr" {
		t.Errorf("saved draft = %v", saved)
	}
	do("marketer", "PUT", "/v1/staff/content/pages/legal/privacy/draft", draftBody(fakeLegalV2, 0), 409)
	loaded := do("marketer", "GET", "/v1/staff/content/pages/legal/privacy", nil, 200)
	if loaded["draft"] == nil || loaded["published"].(map[string]any)["version"] != "1.0" {
		t.Errorf("page with draft = %v", loaded)
	}
	acc := loaded["access"].(map[string]any)
	if acc["canEdit"] != true || acc["canPublish"] != false || acc["secondApprover"] != true || acc["reason"] == nil {
		t.Errorf("marketer access = %v", acc)
	}
	for _, it := range do("marketer", "GET", "/v1/staff/content/pages", nil, 200)["items"].([]any) {
		if got := it.(map[string]any); got["key"] == "privacy" && (got["hasDraft"] != true || got["draftUpdatedByName"] != "Mia Marketer") {
			t.Errorf("index row with draft = %v", got)
		}
	}

	// Publishing: the Marketing editor lacks the Approve level; the editor of the draft cannot approve their own change;
	// a reason is required; a second approver publishes.
	do("marketer", "POST", "/v1/staff/content/pages/legal/privacy/publication", map[string]any{"reason": "Retention is agreed", "rowVersion": 1}, 403)
	do("admin", "POST", "/v1/staff/content/pages/legal/privacy/publication", map[string]any{"reason": "no", "rowVersion": 1}, 422)
	published := do("approver2", "POST", "/v1/staff/content/pages/legal/privacy/publication", map[string]any{"reason": "Counsel approved the wording", "rowVersion": 1}, 201)
	if f.legalLatest != "1.1" || published["draft"] != nil || published["versions"].([]any)[0].(map[string]any)["version"] != "1.1" {
		t.Errorf("after publication = %v", published)
	}
	do("admin", "POST", "/v1/staff/content/pages/legal/privacy/publication", map[string]any{"reason": "Counsel approved the wording", "rowVersion": 1}, 404)

	// Rollback of a legal document starts a draft from the older text; a second start is a conflict; a draft can be discarded.
	rolled := do("marketer", "POST", "/v1/staff/content/pages/legal/privacy/rollback", map[string]any{"version": "0.9", "reason": "Back to the first wording"}, 201)
	if rolled["draft"] == nil || rolled["draft"].(map[string]any)["changeSummary"] != "Restores version 0.9" {
		t.Errorf("rollback page = %v", rolled)
	}
	do("marketer", "POST", "/v1/staff/content/pages/legal/privacy/rollback", map[string]any{"version": "0.9"}, 409)
	do("marketer", "DELETE", "/v1/staff/content/pages/legal/privacy/draft", nil, 204)

	// Help: a page that can be edited, previewed from a draft and published with a reason; then rolled back.
	help := do("marketer", "GET", "/v1/staff/content/pages/help/shipping", nil, 200)
	if help["published"].(map[string]any)["bodies"].(map[string]any)["en"] != "## Carrier shipping\n\nUSPS, UPS and FedEx." || help["liveUrl"] != "https://shop.example.test/help/shipping" ||
		len(help["locales"].([]any)) != 1 || help["access"].(map[string]any)["canPublish"] != true || len(help["topics"].([]any)) != 5 {
		t.Errorf("help page = %v", help)
	}
	helpDraft := func(md string, row any) map[string]any {
		b := map[string]any{"title": "Shipping & delivery", "summary": "Carriers and pickup", "topic": "ORDERS_DELIVERY", "sortOrder": 10, "bodies": map[string]any{"en": md}, "changeSummary": "Adds lockers"}
		if row != nil {
			b["rowVersion"] = row
		}
		return b
	}
	do("marketer", "PUT", "/v1/staff/content/pages/help/shipping/draft", helpDraft("No heading here", nil), 422)
	do("marketer", "PUT", "/v1/staff/content/pages/help/shipping/draft", helpDraft("## Pickup\n\nx", nil), 200)
	hd := do("marketer", "PUT", "/v1/staff/content/pages/help/shipping/draft", helpDraft("## Pickup\n\nCollect from a locker.", 1), 200)
	if hd["rowVersion"] != float64(2) || hd["bodies"].(map[string]any)["en"] != "## Pickup\n\nCollect from a locker." {
		t.Errorf("help draft = %v", hd)
	}
	do("marketer", "POST", "/v1/staff/content/pages/help/shipping/publication", map[string]any{"reason": "", "rowVersion": 2}, 422)
	after := do("marketer", "POST", "/v1/staff/content/pages/help/shipping/publication", map[string]any{"reason": "Lockers are live", "rowVersion": 2}, 201)
	if after["draft"] != nil || after["published"].(map[string]any)["version"] != "2" || after["versions"].([]any)[0].(map[string]any)["reason"] != "Lockers are live" ||
		after["versions"].([]any)[0].(map[string]any)["publishedByName"] != "Mia Marketer" {
		t.Errorf("after help publication = %v", after)
	}
	rev := do("marketer", "GET", "/v1/staff/content/pages/help/shipping/versions/1", nil, 200)
	if rev["bodies"].(map[string]any)["en"] != "## Carrier shipping\n\nUSPS, UPS and FedEx." {
		t.Errorf("help revision 1 = %v", rev)
	}
	do("marketer", "GET", "/v1/staff/content/pages/help/shipping/versions/99", nil, 404)
	do("marketer", "POST", "/v1/staff/content/pages/help/shipping/rollback", map[string]any{"version": "1"}, 422)
	back := do("marketer", "POST", "/v1/staff/content/pages/help/shipping/rollback", map[string]any{"version": "1", "reason": "Lockers were paused"}, 201)
	if back["published"].(map[string]any)["version"] != "3" || back["published"].(map[string]any)["bodies"].(map[string]any)["en"] != "## Carrier shipping\n\nUSPS, UPS and FedEx." {
		t.Errorf("after rollback = %v", back["published"])
	}
	do("marketer", "PUT", "/v1/staff/content/pages/help/shipping/draft", helpDraft("## Pickup\n\nAgain.", nil), 200)
	do("marketer", "DELETE", "/v1/staff/content/pages/help/shipping/draft", nil, 204)

	// Access: support can read but not change; customers and guests are refused.
	sup := do("support", "GET", "/v1/staff/content/pages/legal/privacy", nil, 200)
	if sup["access"].(map[string]any)["canEdit"] != false {
		t.Errorf("support access = %v", sup["access"])
	}
	do("support", "PUT", "/v1/staff/content/pages/help/shipping/draft", helpDraft("## A\n\nb", nil), 403)
	do("support", "DELETE", "/v1/staff/content/pages/legal/privacy/draft", nil, 403)
	do("customer", "GET", "/v1/staff/content/pages", nil, 403)
	do("", "GET", "/v1/staff/content/pages", nil, 401)

	for _, op := range []string{"listStaffContentPages", "getStaffContentPage", "getStaffContentVersion", "saveStaffContentDraft",
		"discardStaffContentDraft", "publishStaffContentPage", "rollbackStaffContentPage"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
}

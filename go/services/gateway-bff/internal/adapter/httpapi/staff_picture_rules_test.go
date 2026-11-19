package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

func pictureKnobsDoc(profile string) map[string]any {
	switch profile {
	case "strict":
		return map[string]any{"minImages": 1, "minLongSidePx": 1000, "minShortSidePx": 500, "maxMegabytes": 8, "checkBackground": true, "checkAspect": true,
			"checkTextOverlay": true, "requireRealPhotoForStorefront": true, "requirePhotoForMatching": true, "allowPlaceholders": false}
	case "testing":
		return map[string]any{"minImages": 0, "minLongSidePx": 1, "minShortSidePx": 1, "maxMegabytes": 8, "checkBackground": false, "checkAspect": false,
			"checkTextOverlay": false, "requireRealPhotoForStorefront": false, "requirePhotoForMatching": false, "allowPlaceholders": true}
	default:
		return map[string]any{"minImages": 1, "minLongSidePx": 500, "minShortSidePx": 300, "maxMegabytes": 8, "checkBackground": false, "checkAspect": false,
			"checkTextOverlay": false, "requireRealPhotoForStorefront": true, "requirePhotoForMatching": true, "allowPlaceholders": false}
	}
}

func pictureRulesDoc(profile string, version int, canChange bool) map[string]any {
	rules := func(p string, v int) map[string]any {
		return map[string]any{"profile": p, "knobs": pictureKnobsDoc(p), "overridden": []any{}, "formats": []any{"image/jpeg", "image/png", "image/webp", "image/avif"},
			"version": v, "summary": "at least 1 photo"}
	}
	return map[string]any{"rules": rules(profile, version), "overrides": map[string]any{}, "updatedBy": capChangerID, "updatedAt": "2026-11-19T09:00:00Z",
		"canChange": canChange, "environmentDefault": "startup", "relaxed": profile != "strict",
		"profiles": []any{rules("strict", 0), rules("startup", 0), rules("testing", 0)},
		"history": []any{map[string]any{"version": version, "previousProfile": "testing", "profile": profile, "overridden": []any{}, "changedBy": capChangerID,
			"changedAt": "2026-11-19T09:00:00Z", "reason": "Unblock testing"}}}
}

// TestStaffPictureRulesConformance: Settings > Picture rules. The BFF checks the permission it knows (catalog:picture-rules.manage to
// change, catalog:moderate to look), the shape the form can get wrong and the reason, then relays reads and the change to catalog with the
// staff member's own token, If-Match and reason. People are named, never shown as ids.
func TestStaffPictureRulesConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var reads, writes []map[string]any
	f := &fakePlatform{payoutSt: "REQUESTED"}
	f.override = func(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if r.URL.Path == "/v1/me" {
			perms := map[string][]string{"pr-admin": {"catalog:picture-rules.manage", "catalog:moderate"}, "pr-mod": {"catalog:moderate"}, "pr-none": {"orders:read"}}[token]
			if perms == nil {
				return false
			}
			writeJ(w, 200, map[string]any{"id": "stf-9", "firstName": "Ngozi", "lastName": "Eze", "principalType": "STAFF", "roles": []string{"platform.catalog_manager"}, "permissions": perms})
			return true
		}
		if r.URL.Path != "/v1/catalog/picture-rules" {
			return false
		}
		mu.Lock()
		defer mu.Unlock()
		if r.Method == http.MethodGet {
			reads = append(reads, map[string]any{"auth": r.Header.Get("Authorization")})
			writeJ(w, 200, pictureRulesDoc("startup", 3, token == "pr-admin"))
			return true
		}
		writes = append(writes, map[string]any{"auth": r.Header.Get("Authorization"), "ifMatch": r.Header.Get("If-Match"), "body": body})
		writeJ(w, 200, pictureRulesDoc(body["profile"].(string), 4, true))
		return true
	}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) {
		staff.PictureRules = app.NewStaffPictureRulesService(d)
	})
	conforms := func(rec *httptest.ResponseRecorder, method string) {
		t.Helper()
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal/v1/staff/picture-rules", nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatal(err)
		}
		in := &openapi3filter.ResponseValidationInput{RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
			Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}}, Status: rec.Code, Header: rec.Header(),
			Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())), Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true}}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s does not match the contract: %v\n%s", method, err, rec.Body.String())
		}
	}
	put := func(token, ifMatch string, body any, want int) *httptest.ResponseRecorder {
		t.Helper()
		var rec *httptest.ResponseRecorder
		if ifMatch == "" {
			rec = raw(token, "PUT", "/v1/staff/picture-rules", body)
		} else {
			rec = raw(token, "PUT", "/v1/staff/picture-rules", body, "If-Match", ifMatch)
		}
		if rec.Code != want {
			t.Fatalf("PUT as %q = %d, want %d: %s", token, rec.Code, want, rec.Body.String())
		}
		return rec
	}

	rec := raw("pr-admin", "GET", "/v1/staff/picture-rules", nil)
	if rec.Code != 200 {
		t.Fatalf("GET = %d: %s", rec.Code, rec.Body.String())
	}
	conforms(rec, "GET")
	if !strings.Contains(rec.Header().Get("Cache-Control"), "no-store") || rec.Header().Get("ETag") != `"3"` {
		t.Errorf("Cache-Control %q ETag %q", rec.Header().Get("Cache-Control"), rec.Header().Get("ETag"))
	}
	got := jsonOf(t, rec)
	if got["updatedBy"] != "Amara Nwosu" || got["canChange"] != true || got["relaxed"] != true || len(got["profiles"].([]any)) != 3 {
		t.Fatalf("view = %v", got)
	}
	if h := got["history"].([]any); h[0].(map[string]any)["changedBy"] != "Amara Nwosu" {
		t.Fatalf("history = %v", h)
	}
	if strings.Contains(rec.Body.String(), capChangerID) {
		t.Errorf("a person's id reached the console: %s", rec.Body.String())
	}
	if len(reads) != 1 || reads[0]["auth"] != "Bearer pr-admin" {
		t.Fatalf("catalog should see the staff member's own token: %v", reads)
	}
	if rec := raw("pr-mod", "GET", "/v1/staff/picture-rules", nil); rec.Code != 200 || jsonOf(t, rec)["canChange"] != false {
		t.Fatalf("a moderator may look: %d %s", rec.Code, rec.Body.String())
	}
	if rec := raw("pr-none", "GET", "/v1/staff/picture-rules", nil); rec.Code != 403 {
		t.Fatalf("no permission GET = %d", rec.Code)
	}

	rec = put("pr-admin", `"3"`, map[string]any{"profile": "STRICT", "overrides": map[string]any{"minImages": 2}, "reason": "  Product wants the standard "}, 200)
	conforms(rec, "PUT")
	if rec.Header().Get("ETag") != `"4"` {
		t.Errorf("PUT ETag %q", rec.Header().Get("ETag"))
	}
	if len(writes) != 1 || writes[0]["auth"] != "Bearer pr-admin" || writes[0]["ifMatch"] != `"3"` {
		t.Fatalf("relay = %v", writes)
	}
	sent := writes[0]["body"].(map[string]any)
	if sent["profile"] != "strict" || sent["reason"] != "Product wants the standard" || sent["overrides"].(map[string]any)["minImages"].(float64) != 2 {
		t.Fatalf("body sent to catalog = %v", sent)
	}

	before := len(writes)
	bad := jsonOf(t, put("pr-admin", `"4"`, map[string]any{"profile": "relaxed", "overrides": map[string]any{"minImages": "two", "colour": true, "checkAspect": "yes"}, "reason": "no"}, 422))
	fields, _ := bad["errors"].(map[string]any)
	for _, k := range []string{"profile", "minImages", "colour", "checkAspect", "reason"} {
		if fields[k] == nil {
			t.Errorf("no validation message for %s: %v", k, bad)
		}
	}
	put("pr-admin", `"4"`, map[string]any{"profile": "testing", "reason": strings.Repeat("x", 301)}, 422)
	if p := jsonOf(t, put("pr-admin", "", map[string]any{"profile": "testing", "reason": "No version"}, 428)); p["code"] != "VERSION_REQUIRED" {
		t.Fatalf("428 = %v", p)
	}
	put("pr-mod", `"3"`, map[string]any{"profile": "testing", "reason": "Not allowed"}, 403)
	put("pr-none", `"3"`, map[string]any{"profile": "testing", "reason": "Not allowed"}, 403)
	put("", `"3"`, map[string]any{"profile": "testing", "reason": "Signed out"}, 401)
	if len(writes) != before {
		t.Fatalf("a request the BFF refused reached catalog: %v", writes[before:])
	}
}

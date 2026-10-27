package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3filter"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestRunAlertStorefrontRoutes drives every storefront delivery-run operation (locations and their pages, guest and
// signed-in subscriptions with consent per channel, confirm and unsubscribe links, advertorials for the session's place
// and their view and click events) against the fake delivery-runs service and validates every answer, including the
// refusals, against gateway-bff.yaml.
func TestRunAlertStorefrontRoutes(t *testing.T) {
	router := loadRouter(t)
	bff, b := setup(t, testkit.Options{})
	validated := map[string]int{}
	b.onResponse = func(req *http.Request, rec *httptest.ResponseRecorder) {
		route, params, err := router.FindRoute(req)
		if err != nil {
			return // the identity redirect, which is not part of the contract
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", req.Method, req.URL.Path, rec.Code, rec.Body.Bytes())
			return
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s -> %d does not match the contract: %v\nbody: %s", req.Method, req.URL.Path, rec.Code, err, rec.Body.Bytes())
			return
		}
		validated[route.Operation.OperationID]++
	}

	// Locations and a location page.
	r := b.do("GET", "/v1/delivery-runs/locations", nil)
	mustStatus(t, r, 200)
	if items := r.json(t)["items"].([]any); len(items) != 1 || items[0].(map[string]any)["slug"] != testkit.RunLocationSlug {
		t.Fatalf("locations = %s", r.body)
	}
	r = b.do("GET", "/v1/delivery-runs/locations/"+testkit.RunLocationSlug, nil)
	mustStatus(t, r, 200)
	page := r.json(t)
	if page["location"].(map[string]any)["whatsappInviteUrl"] == nil || len(page["runs"].([]any)) != 1 || page["shareLinks"] == nil {
		t.Fatalf("location page = %s", r.body)
	}
	mustStatus(t, b.do("GET", "/v1/delivery-runs/locations/nowhere", nil), 404)
	mustStatus(t, b.do("GET", "/v1/delivery-runs/locations/Not%20A%20Slug", nil), 404)

	// Guest sign-up: validated here, accepted upstream with the source kept, then the confirmation link.
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": "x", "email": "nope"}), 422)
	r = b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": testkit.RunLocationID, "email": "nope"})
	mustStatus(t, r, 422)
	if e, _ := r.json(t)["errors"].(map[string]any); e["email"] == nil || e["locationId"] != nil {
		t.Fatalf("errors = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": testkit.RunLocationID, "email": "ada@example.com", "source": "Bad Source!"}), 422)
	r = b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": testkit.RunLocationID, "email": "ada@example.com", "source": "location-page"})
	mustStatus(t, r, 202)
	if r.json(t)["status"] != "CONFIRMATION_SENT" || bff.Upstreams.LastWaitlist["source"] != "location-page" || bff.Upstreams.LastWaitlist["locationId"] != testkit.RunLocationID {
		t.Fatalf("subscribe = %s %v", r.body, bff.Upstreams.LastWaitlist)
	}
	r = b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": testkit.RunLocationID, "email": "ada@example.com"})
	mustStatus(t, r, 202)
	if bff.Upstreams.LastWaitlist["source"] != "storefront" {
		t.Fatalf("default source = %v", bff.Upstreams.LastWaitlist)
	}

	r = b.do("POST", "/v1/delivery-runs/subscriptions/confirm", map[string]any{"token": testkit.RunSubscriptionTok})
	mustStatus(t, r, 200)
	if sub := r.json(t); sub["status"] != "ACTIVE" || sub["consents"].([]any)[0].(map[string]any)["channel"] != "EMAIL" {
		t.Fatalf("confirm = %s", r.body)
	}
	r = b.do("POST", "/v1/delivery-runs/subscriptions/confirm", map[string]any{"token": "stale"})
	mustStatus(t, r, 422)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions/confirm", map[string]any{"token": " "}), 422)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions/confirm", map[string]any{"token": strings.Repeat("x", 201)}), 422)

	r = b.do("POST", "/v1/delivery-runs/subscriptions/unsubscribe", map[string]any{"token": testkit.RunSubscriptionTok, "channels": []string{"email"}})
	mustStatus(t, r, 200)
	if got := bff.Upstreams.LastRunUnsubscribe["channels"]; !slices.Equal(anyStrings(got), []string{"EMAIL"}) {
		t.Fatalf("channels sent = %v", got)
	}
	if c := r.json(t)["consents"].([]any)[0].(map[string]any); c["granted"] != false || c["withdrawnAt"] == nil {
		t.Fatalf("withdrawal = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions/unsubscribe", map[string]any{"token": testkit.RunSubscriptionTok}), 200)
	if _, has := bff.Upstreams.LastRunUnsubscribe["channels"]; has {
		t.Fatalf("no channels means all: %v", bff.Upstreams.LastRunUnsubscribe)
	}
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions/unsubscribe", map[string]any{"token": testkit.RunSubscriptionTok, "channels": []string{"FAX"}}), 422)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions/unsubscribe", map[string]any{"token": "stale"}), 422)

	// The account screen needs a sign-in.
	mustStatus(t, b.do("GET", "/v1/account/run-subscriptions", nil), 401)
	mustStatus(t, b.do("POST", "/v1/account/run-subscriptions", map[string]any{"locationId": testkit.RunLocationID, "channels": []string{"EMAIL"}}), 401)
	mustStatus(t, b.do("DELETE", "/v1/account/run-subscriptions/"+testkit.RunLocationID, nil), 401)

	mustStatus(t, signIn(t, bff, b, "/"), 302)
	r = b.do("GET", "/v1/account/run-subscriptions", nil)
	mustStatus(t, r, 200)
	if items := r.json(t)["items"].([]any); len(items) != 1 || len(items[0].(map[string]any)["consents"].([]any)) != 2 {
		t.Fatalf("mine = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/run-subscriptions", map[string]any{"locationId": testkit.RunLocationID, "channels": []string{}}), 422)
	mustStatus(t, b.do("POST", "/v1/account/run-subscriptions", map[string]any{"locationId": testkit.RunLocationID, "channels": []string{"EMAIL", "TELEGRAM"}}), 422)
	mustStatus(t, b.do("POST", "/v1/account/run-subscriptions", map[string]any{"locationId": "nope", "channels": []string{"EMAIL"}}), 422)
	r = b.do("POST", "/v1/account/run-subscriptions", map[string]any{"locationId": testkit.RunLocationID, "channels": []string{"sms", "WHATSAPP", "SMS"}, "source": "account"})
	mustStatus(t, r, 200)
	sent := bff.Upstreams.LastRunSubscribeMine
	if !slices.Equal(anyStrings(sent["channels"]), []string{"SMS", "WHATSAPP"}) || sent["source"] != "account" {
		t.Fatalf("sent = %v", sent)
	}
	r = b.do("DELETE", "/v1/account/run-subscriptions/"+testkit.RunLocationID+"?channel=SMS&channel=whatsapp", nil)
	mustStatus(t, r, 200)
	if !slices.Equal(bff.Upstreams.LastRunUnsubscribeMine, []string{"SMS", "WHATSAPP"}) {
		t.Fatalf("withdrawn = %v", bff.Upstreams.LastRunUnsubscribeMine)
	}
	mustStatus(t, b.do("DELETE", "/v1/account/run-subscriptions/"+testkit.RunLocationID, nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/account/run-subscriptions/not-a-uuid", nil), 404)
	mustStatus(t, b.do("DELETE", "/v1/account/run-subscriptions/"+testkit.RunLocationID+"?channel=FAX", nil), 422)
	mustStatus(t, signOut(t, b), 204)

	// Advertorials: no place, no cards; the session's delivery location otherwise; an explicit place wins.
	r = b.do("GET", "/v1/delivery-runs/advertorials", nil)
	mustStatus(t, r, 200)
	if len(r.json(t)["items"].([]any)) != 0 {
		t.Fatalf("no place = %s", r.body)
	}
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "79401"}), 200)
	r = b.do("GET", "/v1/delivery-runs/advertorials?visitor=v_123&limit=2", nil)
	mustStatus(t, r, 200)
	items := r.json(t)["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["id"] != testkit.RunAdvertorialID || !strings.Contains(items[0].(map[string]any)["ctaUrl"].(string), "cr=adv-") {
		t.Fatalf("advertorials = %s", r.body)
	}
	if fp, _ := items[0].(map[string]any)["featuredProducts"].([]any); len(fp) != 2 || fp[0].(map[string]any)["atin"] != "ATSHEA0001" {
		t.Fatalf("featured products = %s", r.body)
	}
	if q := bff.Upstreams.LastAdvertorialQuery; q.Get("zip") != "79401" || q.Get("visitor") != "v_123" || q.Get("limit") != "2" || q.Get("region") == "" {
		t.Fatalf("upstream query = %v", q)
	}
	r = b.do("GET", "/v1/delivery-runs/advertorials?country=us&region=tx&city=Midland", nil)
	mustStatus(t, r, 200)
	if q := bff.Upstreams.LastAdvertorialQuery; q.Get("zip") != "" || q.Get("city") != "Midland" || len(r.json(t)["items"].([]any)) != 0 {
		t.Fatalf("explicit place = %v %s", q, r.body)
	}
	for _, bad := range []string{"limit=0", "limit=11", "limit=x", "visitor=a%20b", "zip=123", "city=Lubbock", "lat=1", "lat=95&lng=1", "country=USA&region=TX"} {
		mustStatus(t, b.do("GET", "/v1/delivery-runs/advertorials?"+bad, nil), 422)
	}

	// A visit that arrived with UTM tags is counted with the tags only; one without tags, or for a bad run, is not sent.
	const visitRunID = "9d4a1c7e-0000-4000-8000-0000000000d1"
	visits := "/v1/delivery-runs/locations/" + testkit.RunLocationSlug + "/visits"
	bff.Upstreams.LastRunVisit = nil
	mustStatus(t, b.do("POST", visits, map[string]any{"utmSource": " whatsapp ", "utmMedium": "social", "utmCampaign": "delivery-run-bastrop-tx-20261024", "runId": visitRunID}), 204)
	if got := bff.Upstreams.LastRunVisit; got["utmSource"] != "whatsapp" || got["utmMedium"] != "social" || got["runId"] != visitRunID || len(got) != 4 {
		t.Fatalf("visit relayed %v", got)
	}
	bff.Upstreams.LastRunVisit = nil
	mustStatus(t, b.do("POST", visits, map[string]any{"runId": visitRunID}), 204)
	mustStatus(t, b.do("POST", visits, nil), 204)
	if bff.Upstreams.LastRunVisit != nil {
		t.Fatalf("a visit without tags reached delivery-runs: %v", bff.Upstreams.LastRunVisit)
	}
	mustStatus(t, b.do("POST", visits, map[string]any{"utmSource": strings.Repeat("s", 81)}), 422)
	mustStatus(t, b.do("POST", visits, map[string]any{"utmSource": "x", "runId": "not-a-run"}), 422)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/locations/nowhere/visits", map[string]any{"utmSource": "x"}), 404)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/locations/Not%20A%20Slug/visits", map[string]any{"utmSource": "x"}), 404)

	// The tags a guest arrived with ride along on the sign-up.
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": testkit.RunLocationID, "email": "ada@example.com", "source": "location-page", "utmSource": "whatsapp", "utmCampaign": "c1"}), 202)
	if w := bff.Upstreams.LastWaitlist; w["utmSource"] != "whatsapp" || w["utmCampaign"] != "c1" || w["utmMedium"] != nil {
		t.Fatalf("sign-up relayed %v", w)
	}
	mustStatus(t, b.do("POST", "/v1/delivery-runs/subscriptions", map[string]any{"locationId": testkit.RunLocationID, "email": "ada@example.com", "utmMedium": strings.Repeat("m", 81)}), 422)

	events := "/v1/delivery-runs/advertorials/" + testkit.RunAdvertorialID + "/events"
	mustStatus(t, b.do("POST", events, map[string]any{"type": "view"}), 204)
	mustStatus(t, b.do("POST", events, map[string]any{"type": "CLICK"}), 204)
	mustStatus(t, b.do("POST", events, map[string]any{"type": "BUY"}), 422)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/advertorials/5c3a0e4b-0000-4000-8000-0000000000ff/events", map[string]any{"type": "VIEW"}), 404)
	mustStatus(t, b.do("POST", "/v1/delivery-runs/advertorials/not-a-uuid/events", map[string]any{"type": "VIEW"}), 404)
	if got := bff.Upstreams.AdvertorialEvents; !slices.Equal(got, []string{"VIEW", "CLICK"}) {
		t.Fatalf("events counted = %v", got)
	}

	for _, op := range []string{"listRunLocations", "getRunLocation", "subscribeRunAlerts", "confirmRunAlerts", "unsubscribeRunAlerts",
		"listMyRunSubscriptions", "subscribeMyRunAlerts", "unsubscribeMyRunAlerts", "listRunAdvertorials", "recordRunAdvertorialEvent", "countRunLocationVisit"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}

func anyStrings(v any) []string {
	var out []string
	list, _ := v.([]any)
	for _, e := range list {
		if s, ok := e.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func signOut(t *testing.T, b *browser) resp {
	t.Helper()
	r := b.do("POST", "/v1/auth/sign-out", nil)
	if r.status == 200 {
		r.status = http.StatusNoContent
	}
	return r
}

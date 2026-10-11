package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

func findAlert(t *testing.T, body map[string]any, topic, channel string) map[string]any {
	t.Helper()
	for _, x := range body["preferences"].([]any) {
		p := x.(map[string]any)
		if p["topic"] == topic && p["channel"] == channel {
			return p
		}
	}
	t.Fatalf("no %s/%s in %v", topic, channel, body)
	return nil
}

// TestAccountSettingsForSellerMembers covers the Seller Center's "My account": profile with memberships, job title,
// preferences, alert topics, leaving an organisation, trusted browsers, sign-in activity and passkey names.
func TestAccountSettingsForSellerMembers(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)
	u := bff.Upstreams

	mustStatus(t, b.do("GET", "/v1/account/profile", nil), 401)
	mustStatus(t, b.do("GET", "/v1/account/alerts", nil), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)

	r := b.do("GET", "/v1/account/profile", nil)
	mustStatus(t, r, 200)
	p := r.json(t)
	if p["kind"] != "seller" || p["initials"] != "AN" || p["emailManaged"] != false || p["jobTitle"] != nil {
		t.Fatalf("profile: %s", r.body)
	}
	ms := p["memberships"].([]any)
	if len(ms) != 2 {
		t.Fatalf("memberships: %v", ms)
	}
	owner, practitioner := ms[0].(map[string]any), ms[1].(map[string]any)
	if owner["owner"] != true || owner["active"] != true || owner["roleLabel"] != "Owner" || practitioner["owner"] != false {
		t.Fatalf("membership roles: %v", ms)
	}

	r = b.do("PATCH", "/v1/account/profile", map[string]any{"firstName": "  Ada  ", "jobTitle": "Head of  fulfilment"})
	mustStatus(t, r, 200)
	if r.json(t)["firstName"] != "Ada" || r.json(t)["jobTitle"] != "Head of fulfilment" {
		t.Fatalf("profile update: %s", r.body)
	}
	mustStatus(t, b.do("PATCH", "/v1/account/profile", map[string]any{"lastName": ""}), 422)
	mustStatus(t, b.do("PATCH", "/v1/account/profile", map[string]any{"phone": "12"}), 422)

	r = b.do("GET", "/v1/account/preferences", nil)
	mustStatus(t, r, 200)
	if r.json(t)["tableDensity"] != "COMFORTABLE" || r.json(t)["locale"] != nil {
		t.Fatalf("preferences: %s", r.body)
	}
	r = b.do("PUT", "/v1/account/preferences", map[string]any{"locale": "fr-CA", "timeZone": "America/Toronto", "dateFormat": "DMY", "numberFormat": "SPACE_COMMA", "tableDensity": "COMPACT"})
	mustStatus(t, r, 200)
	if r.json(t)["tableDensity"] != "COMPACT" || r.json(t)["locale"] != "fr-CA" {
		t.Fatalf("saved preferences: %s", r.body)
	}
	mustStatus(t, b.do("PUT", "/v1/account/preferences", map[string]any{"locale": nil, "timeZone": nil, "dateFormat": "SOMETIMES", "numberFormat": "AUTO", "tableDensity": "COMPACT"}), 422)
	r = b.do("PUT", "/v1/account/preferences", map[string]any{"locale": nil, "timeZone": "Mars/Olympus", "dateFormat": "AUTO", "numberFormat": "AUTO", "tableDensity": "COMPACT"})
	mustStatus(t, r, 422)

	r = b.do("GET", "/v1/account/alerts", nil)
	mustStatus(t, r, 200)
	alerts := r.json(t)
	if alerts["audience"] != "seller" || len(alerts["preferences"].([]any)) != 10 {
		t.Fatalf("alerts: %s", r.body)
	}
	if c := findAlert(t, alerts, "seller.compliance", "email"); c["locked"] != true {
		t.Fatalf("compliance email is locked: %v", c)
	}
	r = b.do("PUT", "/v1/account/alerts", map[string]any{"preferences": []map[string]any{{"topic": "seller.orders", "channel": "sms", "enabled": false}},
		"quietHours": map[string]any{"enabled": true, "start": "22:00", "end": "07:30"}})
	mustStatus(t, r, 200)
	if findAlert(t, r.json(t), "seller.orders", "sms")["enabled"] != false || r.json(t)["quietHours"].(map[string]any)["start"] != "22:00" {
		t.Fatalf("saved alerts: %s", r.body)
	}
	mustStatus(t, b.do("PUT", "/v1/account/alerts", map[string]any{"preferences": []map[string]any{{"topic": "seller.compliance", "channel": "email", "enabled": false}}}), 422)
	mustStatus(t, b.do("PUT", "/v1/account/alerts", map[string]any{"preferences": []map[string]any{{"topic": "staff.escalations", "channel": "sms", "enabled": false}}}), 422)

	// Owners can't leave; other members can.
	r = b.do("POST", "/v1/account/orgs/"+testkit.SellerOrgID+"/leave", nil)
	mustStatus(t, r, 409)
	mustStatus(t, b.do("POST", "/v1/account/orgs/"+testkit.ProviderOrgID+"/leave", nil), 204)
	mustStatus(t, b.do("POST", "/v1/account/orgs/org_nope/leave", nil), 404)

	// Security: trusted browsers, activity, passkey names, staff-aware MFA flags.
	r = b.do("GET", "/v1/account/security", nil)
	mustStatus(t, r, 200)
	sec := r.json(t)
	trusted := sec["trustedBrowsers"].([]any)
	if len(trusted) != 1 || trusted[0].(map[string]any)["label"] != "Chrome on macOS" {
		t.Fatalf("trusted browsers: %v", trusted)
	}
	if mfa := sec["mfa"].(map[string]any); mfa["mayRemoveTotp"] != true || mfa["mayRemoveSms"] != false {
		t.Fatalf("mfa flags: %v", mfa)
	}
	mustStatus(t, b.do("DELETE", "/v1/account/trusted-browsers/nope", nil), 404)
	mustStatus(t, b.do("DELETE", "/v1/account/trusted-browsers/"+trusted[0].(map[string]any)["id"].(string), nil), 204)
	mustStatus(t, b.do("DELETE", "/v1/account/trusted-browsers", nil), 204)
	r = b.do("GET", "/v1/account/sign-in-activity", nil)
	mustStatus(t, r, 200)
	items := r.json(t)["items"].([]any)
	first, second := items[0].(map[string]any), items[1].(map[string]any)
	if first["device"] != "Safari on iOS" || first["app"] != "AfroTransact on the web" || second["app"] != "Driver app" || second["ip"] != nil {
		t.Fatalf("activity: %v", items)
	}
	r = b.do("PATCH", "/v1/account/passkeys/cred-1", map[string]any{"label": "  Work laptop "})
	mustStatus(t, r, 200)
	if r.json(t)["label"] != "Work laptop" {
		t.Fatalf("rename: %s", r.body)
	}
	mustStatus(t, b.do("PATCH", "/v1/account/passkeys/cred-1", map[string]any{"label": " "}), 422)
	mustStatus(t, b.do("PATCH", "/v1/account/passkeys/cred-9", map[string]any{"label": "Old phone"}), 404)

	// Saving the notification switches keeps the language and time zone stored with them.
	before := u.NotificationPreferencesRow()
	mustStatus(t, b.do("PUT", "/v1/account/notification-preferences", map[string]any{"email": true, "sms": false, "push": true, "marketingOptIn": false}), 200)
	after := u.NotificationPreferencesRow()
	if after["locale"] != before["locale"] || after["timeZone"] != before["timeZone"] || after["sms"] != false {
		t.Fatalf("switches save must keep language and zone: before %v after %v", before, after)
	}

	for _, op := range []string{"getAccountProfile", "updateAccountProfile", "getAccountPreferences", "saveAccountPreferences", "getAlertPreferences",
		"saveAlertPreferences", "leaveOrganisation", "forgetTrustedBrowser", "forgetTrustedBrowsers", "getSignInActivity", "renamePasskey", "getSecurity"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}

// TestAccountSettingsForStaffAndCustomers: staff get staff topics, a managed company email and their platform roles;
// customers get no job title and no alerts.
func TestAccountSettingsForStaffAndCustomers(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validateAll(t, b)
	mustStatus(t, signIn(t, bff, b, "/"), 302)

	bff.Upstreams.SetAccountPrincipal("STAFF")
	r := b.do("GET", "/v1/account/profile", nil)
	mustStatus(t, r, 200)
	p := r.json(t)
	if p["kind"] != "staff" || p["emailManaged"] != true || len(p["roles"].([]any)) != 2 || len(p["memberships"].([]any)) != 0 {
		t.Fatalf("staff profile: %s", r.body)
	}
	r = b.do("GET", "/v1/account/alerts", nil)
	mustStatus(t, r, 200)
	if r.json(t)["audience"] != "staff" || len(r.json(t)["preferences"].([]any)) != 6 {
		t.Fatalf("staff alerts: %s", r.body)
	}
	mustStatus(t, b.do("PUT", "/v1/account/alerts", map[string]any{"preferences": []map[string]any{{"topic": "seller.orders", "channel": "sms", "enabled": false}}}), 422)
	r = b.do("GET", "/v1/account/security", nil)
	mustStatus(t, r, 200)
	if mfa := r.json(t)["mfa"].(map[string]any); mfa["required"] != true || mfa["mayDisable"] != false || mfa["mayRemoveTotp"] != false {
		t.Fatalf("staff keep two-step verification: %v", mfa)
	}

	bff.Upstreams.SetAccountPrincipal("CUSTOMER")
	r = b.do("GET", "/v1/account/profile", nil)
	mustStatus(t, r, 200)
	if r.json(t)["kind"] != "customer" || r.json(t)["jobTitle"] != nil {
		t.Fatalf("customer profile: %s", r.body)
	}
	mustStatus(t, b.do("PATCH", "/v1/account/profile", map[string]any{"jobTitle": "Chef"}), 422)
	mustStatus(t, b.do("GET", "/v1/account/alerts", nil), 403)
}

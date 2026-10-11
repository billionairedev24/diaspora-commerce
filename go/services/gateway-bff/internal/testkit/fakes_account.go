package testkit

import (
	"net/http"
	"strings"
)

// accountState is identity's and notifications' account settings for the signed-in test account.
type accountState struct {
	principalType string
	orgs          []map[string]any
	activeOrg     string
	firstName     string
	phone         string
	jobTitle      any
	display       map[string]any
	alerts        map[string]bool // topic/channel → enabled
	quiet         map[string]any
	trusted       []map[string]any
	left          []string
	passkeyLabel  string
}

func newAccountState() *accountState {
	return &accountState{principalType: "SELLER_MEMBER", firstName: "Adaeze", phone: "+17135550142",
		orgs: []map[string]any{
			{"id": SellerOrgID, "kind": "SELLER", "name": "Ade's Kitchen", "status": "APPROVED", "role": "seller.owner"},
			{"id": ProviderOrgID, "kind": "PROVIDER", "name": "Ama's Braiding Studio", "status": "APPROVED", "role": "provider.practitioner"},
		},
		activeOrg: SellerOrgID, jobTitle: nil,
		display: map[string]any{"locale": nil, "timeZone": nil, "dateFormat": "AUTO", "numberFormat": "AUTO", "tableDensity": "COMFORTABLE"},
		alerts:  map[string]bool{}, quiet: map[string]any{"enabled": false, "start": "21:00", "end": "07:00"},
		trusted: []map[string]any{{"id": "0b0b0b0b-0000-4000-8000-0000000000b1",
			"userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
			"createdAt": "2026-10-01T10:00:00Z", "expiresAt": "2026-10-31T10:00:00Z"}},
		passkeyLabel: "iPhone"}
}

// SetAccountPrincipal makes the signed-in test account staff ("STAFF", no organisations), a customer ("CUSTOMER", no
// organisations) or a Seller Center member ("SELLER_MEMBER", the default).
func (u *Upstreams) SetAccountPrincipal(principalType string) {
	v, ok := shopperStates.Load(u)
	if !ok {
		return
	}
	st, _ := v.(*shopperState)
	st.mu.Lock()
	defer st.mu.Unlock()
	st.account.principalType = principalType
	if principalType != "SELLER_MEMBER" {
		st.account.orgs = []map[string]any{}
		st.account.activeOrg = ""
	}
}

// NotificationPreferencesRow is identity's notification-preferences row as the fake holds it (locale included).
func (u *Upstreams) NotificationPreferencesRow() map[string]any {
	v, ok := shopperStates.Load(u)
	if !ok {
		return nil
	}
	st, _ := v.(*shopperState)
	st.mu.Lock()
	defer st.mu.Unlock()
	out := map[string]any{}
	for k, val := range st.prefs {
		out[k] = val
	}
	return out
}

var alertTopics = map[string][]string{
	"seller": {"seller.orders", "seller.inventory", "seller.payouts", "seller.compliance", "seller.claims"},
	"staff":  {"staff.assigned", "staff.escalations", "staff.approvals"},
}

func locked(topic, channel string) bool {
	return channel == "email" && (topic == "seller.compliance" || topic == "seller.claims" || topic == "staff.escalations")
}

func (u *Upstreams) shopperAccountRoutes(mux *http.ServeMux, st *shopperState, authed func(http.HandlerFunc) http.HandlerFunc) {
	a := st.account
	me := func(w http.ResponseWriter, r *http.Request) {
		roles := []string{"account.customer"}
		if a.principalType == "STAFF" {
			roles = []string{"platform.customer_support", "platform.ops_manager"}
		}
		writeJSON(w, 200, map[string]any{"id": subjectOf(r), "firstName": a.firstName, "lastName": "Nwosu", "email": map[bool]string{true: "ada.nwosu@afrotransact.com", false: "adaeze@example.com"}[a.principalType == "STAFF"],
			"emailVerified": true, "phone": a.phone, "phoneVerified": true, "principalType": a.principalType, "orgId": a.activeOrg,
			"roles": roles, "orgs": a.orgs, "landing": "shop"})
	}
	mux.HandleFunc("GET /v1/me", authed(func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		me(w, r)
	}))
	mux.HandleFunc("PATCH /v1/me", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		st.mu.Lock()
		defer st.mu.Unlock()
		if p, ok := b["phone"].(string); ok {
			if !strings.HasPrefix(p, "+") && len(p) < 10 {
				problem(w, 422, "identity.invalid-phone", "Check the phone number", map[string]any{"fieldErrors": map[string]string{"phone": "Enter a valid mobile number"}})
				return
			}
			a.phone = p
		}
		if f, ok := b["firstName"].(string); ok {
			a.firstName = f
		}
		me(w, r)
	}))
	mux.HandleFunc("GET /v1/me/work-profile", authed(func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, map[string]any{"jobTitle": a.jobTitle})
	}))
	mux.HandleFunc("PUT /v1/me/work-profile", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		st.mu.Lock()
		defer st.mu.Unlock()
		a.jobTitle = b["jobTitle"]
		if t, ok := a.jobTitle.(string); ok && strings.TrimSpace(t) == "" {
			a.jobTitle = nil
		}
		writeJSON(w, 200, map[string]any{"jobTitle": a.jobTitle})
	}))
	mux.HandleFunc("GET /v1/me/preferences", authed(func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, a.display)
	}))
	mux.HandleFunc("PUT /v1/me/preferences", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		st.mu.Lock()
		defer st.mu.Unlock()
		if tz, _ := b["timeZone"].(string); tz == "Mars/Olympus" {
			problem(w, 422, "identity.invalid-preferences", "Check the highlighted fields", map[string]any{"fieldErrors": map[string]string{"timeZone": "Use an IANA time zone such as America/Chicago"}})
			return
		}
		for _, k := range []string{"locale", "timeZone", "dateFormat", "numberFormat", "tableDensity"} {
			a.display[k] = b[k]
		}
		writeJSON(w, 200, a.display)
	}))
	mux.HandleFunc("POST /v1/me/orgs/{orgId}/leave", authed(func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		for i, o := range a.orgs {
			if o["id"] != r.PathValue("orgId") {
				continue
			}
			if strings.HasSuffix(o["role"].(string), ".owner") {
				problem(w, 409, "identity.owner-cannot-leave", "Owners can't leave their organisation; make someone else an owner first", nil)
				return
			}
			a.orgs = append(a.orgs[:i:i], a.orgs[i+1:]...)
			a.left = append(a.left, r.PathValue("orgId"))
			w.WriteHeader(204)
			return
		}
		problem(w, 404, "identity.member-not-found", "Not a member of this organisation", nil)
	}))
	mux.HandleFunc("GET /v1/me/trusted-browsers", authed(func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, a.trusted)
	}))
	mux.HandleFunc("DELETE /v1/me/trusted-browsers", authed(func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		a.trusted = []map[string]any{}
		w.WriteHeader(204)
	}))
	mux.HandleFunc("DELETE /v1/me/trusted-browsers/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		for i, b := range a.trusted {
			if b["id"] == r.PathValue("id") {
				a.trusted = append(a.trusted[:i:i], a.trusted[i+1:]...)
				w.WriteHeader(204)
				return
			}
		}
		problem(w, 404, "identity.trusted-browser-not-found", "That browser isn't trusted", nil)
	}))
	mux.HandleFunc("GET /v1/me/sign-in-activity", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("limit") != "20" {
			problem(w, 400, "identity.bad-limit", "limit", nil)
			return
		}
		writeJSON(w, 200, []map[string]any{
			{"at": "2026-10-10T10:00:00Z", "method": "MFA_TOTP", "outcome": "SUCCEEDED", "channel": "hosted", "clientId": "gateway-bff", "ip": "203.0.113.7",
				"userAgent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"},
			{"at": "2026-10-10T09:59:40Z", "method": "PASSWORD", "outcome": "MFA_REQUIRED", "channel": "hosted", "clientId": "driver-app", "ip": "",
				"userAgent": "okhttp/4.12.0"},
		})
	}))
	mux.HandleFunc("PATCH /v1/me/passkeys/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "cred-1" {
			problem(w, 404, "identity.passkey-not-found", "No such passkey", nil)
			return
		}
		b := readJSON(r)
		st.mu.Lock()
		defer st.mu.Unlock()
		a.passkeyLabel, _ = b["label"].(string)
		writeJSON(w, 200, map[string]any{"credentialId": "cred-1", "label": a.passkeyLabel, "createdAt": "2026-09-01T10:00:00Z"})
	}))

	// notifications' alert topics (recipients read only their own).
	alerts := func(w http.ResponseWriter, r *http.Request, audience string) {
		prefs := []map[string]any{}
		for _, topic := range alertTopics[audience] {
			for _, ch := range []string{"email", "sms"} {
				en, ok := a.alerts[topic+"/"+ch]
				if !ok || locked(topic, ch) {
					en = true
				}
				prefs = append(prefs, map[string]any{"topic": topic, "channel": ch, "enabled": en, "locked": locked(topic, ch)})
			}
		}
		writeJSON(w, 200, map[string]any{"recipientId": r.PathValue("id"), "audience": audience, "preferences": prefs, "quietHours": a.quiet})
	}
	mux.HandleFunc("GET /v1/preferences/{id}/alerts", authed(func(w http.ResponseWriter, r *http.Request) {
		if subjectOf(r) != r.PathValue("id") {
			problem(w, 403, "auth.missing-permission", "Only the recipient may read preferences", nil)
			return
		}
		audience := r.URL.Query().Get("audience")
		if alertTopics[audience] == nil {
			problem(w, 400, "notifications.invalid-request", "audience must be seller or staff", nil)
			return
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		alerts(w, r, audience)
	}))
	mux.HandleFunc("PUT /v1/preferences/{id}/alerts", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		audience, _ := b["audience"].(string)
		st.mu.Lock()
		defer st.mu.Unlock()
		prefs, _ := b["preferences"].([]any)
		for _, x := range prefs {
			p, _ := x.(map[string]any)
			topic, _ := p["topic"].(string)
			ch, _ := p["channel"].(string)
			en, _ := p["enabled"].(bool)
			if !en && locked(topic, ch) {
				problem(w, 400, "notifications.invalid-request", topic+" alerts by "+ch+" cannot be turned off", nil)
				return
			}
			a.alerts[topic+"/"+ch] = en
		}
		if q, ok := b["quietHours"].(map[string]any); ok {
			a.quiet = q
		}
		alerts(w, r, audience)
	}))
}

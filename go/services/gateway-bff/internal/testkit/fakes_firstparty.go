package testkit

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
)

// First-party sign-in fixture (identity S209): ada@example.com / Str0ngPassword! needs a second factor (TOTP or
// SMS, code 123456) unless the trusted-device token td-1 is presented; solo@example.com has no second factor;
// locked@example.com is locked for 120 s; a passkey assertion for challenge pk-1 signs in user-1.
const (
	FirstPartyPassword = "Str0ngPassword!"
	FirstPartyCode     = "123456"
	TrustedDeviceToken = "td-1"
)

func (u *Upstreams) firstPartyRoutes(mux *http.ServeMux) {
	authorized := func(w http.ResponseWriter, r *http.Request, body any) bool {
		if r.Header.Get("Authorization") != "Bearer svc-auth:first-party" {
			problem(w, 403, "identity.first-party-client-required", "first-party client token required", nil)
			return false
		}
		_ = json.NewDecoder(r.Body).Decode(body)
		return true
	}
	ticket := func(sub string) map[string]any {
		u.mu.Lock()
		defer u.mu.Unlock()
		t := "lt-" + newUUID()
		u.tickets[t] = sub
		return map[string]any{"status": "AUTHENTICATED", "loginTicket": t}
	}
	mux.HandleFunc("POST /v1/auth/password", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Email, Password, ClientIP, UserAgent, TrustedDeviceToken string
		}
		if !authorized(w, r, &b) {
			return
		}
		u.mu.Lock()
		u.LastClientIP = b.ClientIP
		u.mu.Unlock()
		switch {
		case b.ClientIP == "":
			problem(w, 400, "identity.invalid-input", "clientIp is required", nil)
		case b.Email == "locked@example.com":
			w.Header().Set("Retry-After", "120")
			problem(w, 429, "identity.sign-in-locked", "Too many failed sign-ins; try again later", map[string]any{"retryAfterSeconds": 120})
		case b.Password != FirstPartyPassword:
			problem(w, 401, "identity.invalid-credentials", "Wrong email or password", nil)
		case b.Email == "solo@example.com" || b.TrustedDeviceToken == TrustedDeviceToken:
			writeJSON(w, 200, ticket("user-1"))
		default:
			writeJSON(w, 200, map[string]any{"status": "MFA_REQUIRED", "challengeId": "ch-1",
				"factors": map[string]any{"totp": true, "sms": true, "smsHint": "+1 ••• ••• 0142", "recoveryCode": true}})
		}
	})
	mux.HandleFunc("POST /v1/auth/mfa/sms", func(w http.ResponseWriter, r *http.Request) {
		var b struct{ ChallengeID string }
		if authorized(w, r, &b) {
			writeJSON(w, 202, map[string]any{"sentTo": "+1 ••• ••• 0142", "expiresAt": "2026-10-10T12:10:00Z"})
		}
	})
	mux.HandleFunc("POST /v1/auth/mfa/verify", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			ChallengeID, Factor, Code string
			TrustDevice               bool
		}
		if !authorized(w, r, &b) {
			return
		}
		if b.ChallengeID != "ch-1" {
			problem(w, 401, "identity.challenge-invalid", "unknown challenge", nil)
			return
		}
		if b.Code != FirstPartyCode || (b.Factor != "TOTP" && b.Factor != "SMS") {
			problem(w, 401, "identity.invalid-code", "Wrong code", map[string]any{"attemptsLeft": 4})
			return
		}
		out := ticket("user-1")
		if b.TrustDevice {
			out["trustedDeviceToken"] = TrustedDeviceToken
		}
		writeJSON(w, 200, out)
	})
	mux.HandleFunc("POST /v1/auth/passkey/options", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		if authorized(w, r, &b) {
			writeJSON(w, 200, map[string]any{"challengeId": "pk-1", "expiresAt": "2026-10-10T12:05:00Z", "publicKey": map[string]any{
				"challenge": "Y2hhbGxlbmdl", "rpId": "afrotransact.com", "timeout": 60000, "userVerification": "required", "allowCredentials": []any{}}})
		}
	})
	mux.HandleFunc("POST /v1/auth/passkey/verify", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			ChallengeID string
			Credential  map[string]any
		}
		if !authorized(w, r, &b) {
			return
		}
		if b.ChallengeID != "pk-1" || b.Credential["type"] != "public-key" || b.Credential["response"] == nil {
			problem(w, 401, "identity.passkey-rejected", "assertion rejected", nil)
			return
		}
		writeJSON(w, 200, ticket("user-1"))
	})
	mux.HandleFunc("GET /oauth2/authorize", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		u.mu.Lock()
		sub, ok := u.tickets[q.Get("login_ticket")]
		delete(u.tickets, q.Get("login_ticket"))
		u.mu.Unlock()
		if !ok || q.Get("client_id") != u.ClientID || q.Get("code_challenge_method") != "S256" || !strings.Contains(q.Get("scope"), "openid") {
			writeJSON(w, 400, map[string]any{"error": "invalid_grant", "error_description": "login ticket rejected"})
			return
		}
		code := u.IssueCode(q.Get("code_challenge"), q.Get("nonce"), sub, q.Get("redirect_uri"))
		to := q.Get("redirect_uri") + "?" + url.Values{"code": {code}, "state": {q.Get("state")}}.Encode()
		http.Redirect(w, r, to, http.StatusFound) //nolint:gosec // test fake of identity's authorize endpoint
	})
}

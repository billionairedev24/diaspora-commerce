package testkit

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// Fake identity team directory, invitations (S236) and phone verification (S234).

// Invitation tokens the fake identity knows.
const (
	InviteTokenValid    = "tok-valid"
	InviteTokenExpired  = "tok-expired"
	InviteTokenMismatch = "tok-other-email"
	// TeamMemberID is the second member of every organisation; AlreadyMemberEmail is a member's address.
	TeamMemberID       = "6f1c2b9e-0d4a-4c1e-9b7a-2f3e4d5c6b7a"
	AlreadyMemberEmail = "kofi@example.com"
	// PhoneCode is the SMS code the fake identity accepts.
	PhoneCode = "123456"
)

type fakeInvitation struct {
	ID, Email, RoleKey, Status string
}

type teamState struct {
	invitations []*fakeInvitation
	removed     map[string]bool
}

func (u *Upstreams) teamRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/orgs/{orgId}/members", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		o, found := u.orgByID(r.PathValue("orgId"))
		if !found {
			problem(w, 403, "identity.forbidden", "Not a member", nil)
			return
		}
		items := []map[string]any{{"userId": sub, "name": "Adaeze Nwosu", "firstName": "Adaeze", "lastName": "Nwosu", "email": "adaeze@example.com",
			"roleKey": o.Role, "roleLabel": "Owner", "status": "ACTIVE", "joinedAt": "2026-04-10T12:00:00Z"}}
		if !u.team.removed[TeamMemberID] {
			items = append(items, map[string]any{"userId": TeamMemberID, "name": "Kofi Mensah", "firstName": "Kofi", "lastName": "Mensah",
				"email": AlreadyMemberEmail, "roleKey": strings.Split(o.Role, ".")[0] + ".finance", "roleLabel": "Finance", "status": "ACTIVE",
				"joinedAt": "2026-05-01T12:00:00Z"})
		}
		writeJSON(w, 200, map[string]any{"orgId": o.ID, "items": items})
	})
	mux.HandleFunc("GET /v1/orgs/{orgId}/invitations", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		items := []map[string]any{}
		for _, inv := range u.team.invitations {
			if inv.Status == "PENDING" {
				items = append(items, invitationJSON(inv))
			}
		}
		writeJSON(w, 200, map[string]any{"orgId": r.PathValue("orgId"), "items": items})
	})
	mux.HandleFunc("POST /v1/orgs/{orgId}/invitations", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		var b struct {
			Email   string `json:"email"`
			RoleKey string `json:"roleKey"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.Email == AlreadyMemberEmail {
			problem(w, 409, "identity.already-member", "Already a member", nil)
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		inv := &fakeInvitation{ID: newUUID(), Email: b.Email, RoleKey: b.RoleKey, Status: "PENDING"}
		u.team.invitations = append(u.team.invitations, inv)
		writeJSON(w, 201, invitationJSON(inv))
	})
	mux.HandleFunc("DELETE /v1/orgs/{orgId}/invitations/{id}", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		for _, inv := range u.team.invitations {
			if inv.ID == r.PathValue("id") && inv.Status == "PENDING" {
				inv.Status = "REVOKED"
				w.WriteHeader(204)
				return
			}
		}
		problem(w, 404, "identity.invitation-not-found", "No such invitation", nil)
	})
	mux.HandleFunc("PUT /v1/orgs/{orgId}/members/{userId}", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		if r.PathValue("userId") == sub {
			problem(w, 403, "identity.own-role", "You can't change your own role", nil)
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("DELETE /v1/orgs/{orgId}/members/{userId}", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		if r.PathValue("userId") == sub {
			problem(w, 422, "identity.last-owner", "The last owner can't be removed", nil)
			return
		}
		u.mu.Lock()
		u.team.removed[r.PathValue("userId")] = true
		u.mu.Unlock()
		w.WriteHeader(204)
	})
	mux.HandleFunc("POST /v1/invitations/lookup", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Token string `json:"token"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		status := "PENDING"
		switch b.Token {
		case InviteTokenValid, InviteTokenMismatch:
		case InviteTokenExpired:
			status = "EXPIRED"
		default:
			problem(w, 404, "identity.invitation-not-found", "Unknown invitation", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"orgName": "Ade's Kitchen", "orgKind": "SELLER", "email": "adaeze@example.com", "roleKey": "seller.catalog",
			"roleLabel": "Catalog", "status": status, "expiresAt": time.Now().Add(72 * time.Hour).UTC().Format(time.RFC3339)})
	})
	mux.HandleFunc("POST /v1/invitations/accept", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		var b struct {
			Token string `json:"token"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		switch b.Token {
		case InviteTokenValid:
			u.mu.Lock()
			u.ActiveOrgID = SellerOrgID
			u.mu.Unlock()
			writeJSON(w, 200, map[string]any{"invitationId": newUUID(), "orgId": SellerOrgID, "orgName": "Ade's Kitchen", "orgKind": "SELLER",
				"roleKey": "seller.catalog", "userId": sub, "alreadyMember": false})
		case InviteTokenExpired:
			problem(w, 422, "identity.invitation-expired", "Invitation expired", nil)
		case InviteTokenMismatch:
			problem(w, 422, "identity.invitation-email-mismatch", "Different email", nil)
		default:
			problem(w, 404, "identity.invitation-not-found", "Unknown invitation", nil)
		}
	})
}

func invitationJSON(inv *fakeInvitation) map[string]any {
	return map[string]any{"id": inv.ID, "orgId": SellerOrgID, "orgName": "Ade's Kitchen", "orgKind": "SELLER", "email": inv.Email, "roleKey": inv.RoleKey,
		"roleLabel": "Fulfilment", "status": inv.Status, "createdAt": "2026-10-01T12:00:00Z", "expiresAt": "2026-10-08T12:00:00Z"}
}

func (u *Upstreams) phoneRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/me/phone/verification", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		verified := u.PhoneVerified
		u.mu.Unlock()
		if verified {
			problem(w, 409, "identity.phone-already-verified", "Already verified", nil)
			return
		}
		writeJSON(w, 202, map[string]any{"sentTo": "•••• 0142", "expiresAt": time.Now().Add(10 * time.Minute).UTC().Format(time.RFC3339)})
	})
	mux.HandleFunc("POST /v1/me/phone/verification/confirm", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		var b struct {
			Code string `json:"code"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.Code != PhoneCode {
			problem(w, 422, "identity.invalid-code", "Wrong code", nil)
			return
		}
		u.mu.Lock()
		u.PhoneVerified = true
		u.mu.Unlock()
		writeJSON(w, 200, map[string]any{"phone": "+17135550142", "phoneVerified": true, "verifiedAt": time.Now().UTC().Format(time.RFC3339)})
	})
}

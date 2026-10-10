package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// S236: the team view comes from identity's member directory plus pending invitations, and identity's team errors
// reach Seller Center with their own codes.
func TestTeamUsesIdentityDirectoryAndInvitations(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{})
	team := b.do("GET", "/v1/seller/team", nil).json(t)
	members := team["members"].([]any)
	if len(members) != 2 {
		t.Fatalf("members = %v", members)
	}
	kofi := members[1].(map[string]any)
	if kofi["name"] != "Kofi Mensah" || kofi["email"] != testkit.AlreadyMemberEmail || kofi["status"] != "ACTIVE" || kofi["joinedAt"] == nil {
		t.Fatalf("directory row = %v", kofi)
	}
	if me := members[0].(map[string]any); me["isYou"] != true || me["name"] != "Adaeze Nwosu" {
		t.Fatalf("own row = %v", me)
	}

	inv := b.do("POST", "/v1/seller/team/invitations", map[string]any{"email": " Kemi@Example.com ", "roleKey": "seller.fulfilment"})
	mustStatus(t, inv, 201)
	row := inv.json(t)
	if row["status"] != "INVITED" || row["email"] != "kemi@example.com" || row["invitationId"] == nil {
		t.Fatalf("invited row = %v", row)
	}
	team = b.do("GET", "/v1/seller/team", nil).json(t)
	if n := len(team["members"].([]any)); n != 3 {
		t.Fatalf("team with invitation has %d rows", n)
	}
	if last := team["members"].([]any)[2].(map[string]any); last["status"] != "INVITED" || last["expiresAt"] == nil {
		t.Fatalf("INVITED row = %v", last)
	}
	after := b.do("DELETE", "/v1/seller/team/invitations/"+row["invitationId"].(string), nil)
	mustStatus(t, after, 200)
	if n := len(after.json(t)["members"].([]any)); n != 2 {
		t.Fatalf("after revoke %d rows", n)
	}

	cases := []struct {
		method, path string
		body         map[string]any
		status       int
		code         string
	}{
		{"POST", "/v1/seller/team/invitations", map[string]any{"email": testkit.AlreadyMemberEmail, "roleKey": "seller.catalog"}, 409, "ALREADY_MEMBER"},
		{"PUT", "/v1/seller/team/members/" + testkit.TeamMemberID, map[string]any{"roleKey": "seller.nope"}, 422, "INVALID_ROLE"},
		{"DELETE", "/v1/seller/team/members/" + testkit.TeamMemberID, nil, 200, ""},
	}
	for _, c := range cases {
		r := b.do(c.method, c.path, c.body)
		if r.status != c.status || (c.code != "" && r.json(t)["code"] != c.code) {
			t.Fatalf("%s %s = %d %s", c.method, c.path, r.status, r.body)
		}
	}
	if n := len(b.do("GET", "/v1/seller/team", nil).json(t)["members"].([]any)); n != 1 {
		t.Fatalf("after remove %d rows", n)
	}
}

func TestTeamErrorMappingFromIdentity(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{})
	session := b.do("GET", "/v1/seller/session", nil).json(t)
	me := session["user"].(map[string]any)["id"].(string)
	// identity refuses removing the last owner and changing your own role.
	r := b.do("DELETE", "/v1/seller/team/members/"+me, nil)
	if r.status != 422 || r.json(t)["code"] != "LAST_OWNER" {
		t.Fatalf("last owner = %d %s", r.status, r.body)
	}
	r = b.do("PUT", "/v1/seller/team/members/"+me, map[string]any{"roleKey": "seller.catalog"})
	if r.status != 403 || r.json(t)["code"] != "OWN_ROLE" {
		t.Fatalf("own role = %d %s", r.status, r.body)
	}
}

func TestInvitationAcceptPage(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	b.origin = testkit.SellerCenterOrigin
	// Lookup is public.
	r := b.do("POST", "/v1/seller/invitations/lookup", map[string]any{"token": testkit.InviteTokenValid})
	if r.status != 200 || r.json(t)["orgName"] != "Ade's Kitchen" || r.json(t)["roleLabel"] == "" {
		t.Fatalf("lookup = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/invitations/lookup", map[string]any{"token": "unknown"}), 404)
	// Accepting needs a session.
	mustStatus(t, b.do("POST", "/v1/seller/invitations/accept", map[string]any{"token": testkit.InviteTokenValid}), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	for token, code := range map[string]string{testkit.InviteTokenExpired: "INVITATION_EXPIRED", testkit.InviteTokenMismatch: "INVITATION_EMAIL_MISMATCH"} {
		if r := b.do("POST", "/v1/seller/invitations/accept", map[string]any{"token": token}); r.status != 422 || r.json(t)["code"] != code {
			t.Fatalf("accept %s = %d %s", token, r.status, r.body)
		}
	}
	r = b.do("POST", "/v1/seller/invitations/accept", map[string]any{"token": testkit.InviteTokenValid})
	if r.status != 200 || r.json(t)["orgId"] != testkit.SellerOrgID || r.json(t)["alreadyMember"] != false {
		t.Fatalf("accept = %d %s", r.status, r.body)
	}
}

func TestSignUpWithInvitationSkipsTheEmailCode(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/sign-up", map[string]any{"firstName": "Kemi", "lastName": "Ade", "email": "kemi@example.com", "phone": "+17135550142",
		"password": "Str0ngPassword!", "termsAccepted": true, "smsConsent": false, "invitationToken": testkit.InviteTokenValid})
	if r.status != 201 || r.json(t)["status"] != "authenticated" || r.json(t)["redirectTo"] == nil {
		t.Fatalf("sign-up = %d %s", r.status, r.body)
	}
	if got := bff.Upstreams.LastSignUp["invitationToken"]; got != testkit.InviteTokenValid {
		t.Fatalf("identity got invitationToken %v", got)
	}
}

func TestPhoneVerification(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	mustStatus(t, b.do("POST", "/v1/account/phone/verification", nil), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	user := b.do("GET", "/v1/me", nil).json(t)["user"].(map[string]any)
	if user["phone"] != "•••• 0142" || user["phoneVerified"] != false {
		t.Fatalf("me = %v", user)
	}
	if r := b.do("POST", "/v1/account/phone/verification", nil); r.status != 202 || r.json(t)["sentTo"] != "•••• 0142" {
		t.Fatalf("start = %d %s", r.status, r.body)
	}
	if r := b.do("POST", "/v1/account/phone/verification/confirm", map[string]any{"code": "000000"}); r.status != 422 || r.json(t)["code"] != "INVALID_CODE" {
		t.Fatalf("wrong code = %d %s", r.status, r.body)
	}
	if r := b.do("POST", "/v1/account/phone/verification/confirm", map[string]any{"code": testkit.PhoneCode}); r.status != 200 || r.json(t)["phoneVerified"] != true {
		t.Fatalf("confirm = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/phone/verification", nil), 409)
}

package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

const nativeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" // 43 chars, S256

func nativeBody(extra map[string]any) map[string]any {
	b := map[string]any{"app": "customer", "codeChallenge": nativeChallenge, "redirectUri": "com.afrotransact.customer:/oauth2redirect"}
	for k, v := range extra {
		b[k] = v
	}
	return b
}

func TestNativePasswordSignInReturnsTheAuthorizationCode(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "adaeze@example.com", "password": "Passw0rd!"}))
	mustStatus(t, r, 200)
	out := r.json(t)
	if out["status"] != "AUTHENTICATED" || !strings.HasPrefix(out["code"].(string), "code-") {
		t.Fatalf("result = %v", out)
	}
	if _, leaked := out["loginTicket"]; leaked {
		t.Fatal("the login ticket must stay between the BFF and identity")
	}
}

func TestNativeSignInForTheDriverApp(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"app": "driver", "email": "adaeze@example.com",
		"password": "Passw0rd!", "redirectUri": "com.afrotransact.driver:/oauth2redirect"}))
	mustStatus(t, r, 200)
	if r.json(t)["status"] != "AUTHENTICATED" {
		t.Fatal("driver sign-in failed")
	}
}

func TestNativeWrongPasswordIs401OnTheForm(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "adaeze@example.com", "password": "nope"}))
	mustStatus(t, r, 401)
	if got := problemCode(t, r); got != "INVALID_CREDENTIALS" {
		t.Fatalf("code = %s", got)
	}
}

func TestNativeSecondFactorStep(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "mfa@example.com", "password": "Passw0rd!"}))
	mustStatus(t, r, 200)
	step := r.json(t)
	factors := step["factors"].(map[string]any)
	if step["status"] != "MFA_REQUIRED" || step["challengeId"] != "chal-1" || factors["smsHint"] != "•••• 0142" || step["code"] != nil {
		t.Fatalf("step = %v", step)
	}
	mustStatus(t, b.do("POST", "/v1/auth/native/mfa/sms", nativeBody(map[string]any{"challengeId": "chal-1"})), 202)

	bad := b.do("POST", "/v1/auth/native/mfa/verify", nativeBody(map[string]any{"challengeId": "chal-1", "factor": "sms", "code": "000000"}))
	mustStatus(t, bad, 422)
	if got := problemCode(t, bad); got != "INVALID_CODE" {
		t.Fatalf("code = %s", got)
	}
	ok := b.do("POST", "/v1/auth/native/mfa/verify", nativeBody(map[string]any{"challengeId": "chal-1", "factor": "sms", "code": "123456",
		"trustDevice": true}))
	mustStatus(t, ok, 200)
	out := ok.json(t)
	if out["status"] != "AUTHENTICATED" || out["code"] == "" || out["trustedDeviceToken"] != "trusted-1" {
		t.Fatalf("verified = %v", out)
	}
}

func TestNativeSignInValidatesTheRequest(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"app": "staff", "email": "a@b.co", "password": "x"})), 422)
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"codeChallenge": "short", "email": "a@b.co", "password": "x"})), 422)
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "", "password": ""})), 422)
}

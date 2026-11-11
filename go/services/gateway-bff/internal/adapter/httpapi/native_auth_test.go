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
	if step["status"] != "MFA_REQUIRED" || step["challengeId"] != "chal-1" || factors["emailOtp"] != true || factors["sms"] != false || step["code"] != nil {
		t.Fatalf("step = %v", step)
	}
	// "send me an email code instead"
	mustStatus(t, b.do("POST", "/v1/auth/native/mfa/email", nativeBody(map[string]any{"challengeId": "chal-1"})), 202)

	bad := b.do("POST", "/v1/auth/native/mfa/verify", nativeBody(map[string]any{"challengeId": "chal-1", "factor": "email_otp", "code": "000000"}))
	mustStatus(t, bad, 422)
	if got := problemCode(t, bad); got != "INVALID_CODE" {
		t.Fatalf("code = %s", got)
	}
	ok := b.do("POST", "/v1/auth/native/mfa/verify", nativeBody(map[string]any{"challengeId": "chal-1", "factor": "email_otp", "code": "123456",
		"trustDevice": true}))
	mustStatus(t, ok, 200)
	out := ok.json(t)
	if out["status"] != "AUTHENTICATED" || out["code"] == "" || out["trustedDeviceToken"] != "trusted-1" {
		t.Fatalf("verified = %v", out)
	}
}

func TestNativePlainAccountSignsInWithPasswordOnlyAndIsSuggestedToSecureIt(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "adaeze@example.com", "password": "Passw0rd!"}))
	mustStatus(t, r, 200)
	out := r.json(t)
	if out["status"] != "AUTHENTICATED" || out["mfaSetupSuggested"] != true || out["challengeId"] != nil {
		t.Fatalf("result = %v", out)
	}
}

func TestNativeMandatoryMfaWithoutAFactorForcesEnrolment(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "staff@example.com", "password": "Passw0rd!"}))
	mustStatus(t, r, 200)
	step := r.json(t)
	factors := step["factors"].(map[string]any)
	if step["status"] != "MFA_ENROLMENT_REQUIRED" || step["challengeId"] != "chal-enrol" || step["code"] != nil || len(factors["enrolMethods"].([]any)) != 2 {
		t.Fatalf("step = %v", step)
	}
	setup := b.do("POST", "/v1/auth/native/mfa/enrol/totp", nativeBody(map[string]any{"challengeId": "chal-enrol"}))
	mustStatus(t, setup, 200)
	if !strings.HasPrefix(setup.json(t)["otpauthUri"].(string), "otpauth://totp/") {
		t.Fatalf("setup = %v", setup.json(t))
	}
	bad := b.do("POST", "/v1/auth/native/mfa/enrol/totp/confirm", nativeBody(map[string]any{"challengeId": "chal-enrol", "code": "000000"}))
	mustStatus(t, bad, 422)
	done := b.do("POST", "/v1/auth/native/mfa/enrol/totp/confirm", nativeBody(map[string]any{"challengeId": "chal-enrol", "code": "123456"}))
	mustStatus(t, done, 200)
	out := done.json(t)
	if out["status"] != "AUTHENTICATED" || !strings.HasPrefix(out["code"].(string), "code-") || len(out["recoveryCodes"].([]any)) != 2 {
		t.Fatalf("enrolled = %v", out)
	}
	// email codes work as the other method
	mustStatus(t, b.do("POST", "/v1/auth/native/mfa/enrol/email", nativeBody(map[string]any{"challengeId": "chal-enrol"})), 202)
	mail := b.do("POST", "/v1/auth/native/mfa/enrol/email/confirm", nativeBody(map[string]any{"challengeId": "chal-enrol", "code": "123456"}))
	mustStatus(t, mail, 200)
	if mail.json(t)["status"] != "AUTHENTICATED" {
		t.Fatalf("email enrol = %v", mail.json(t))
	}
}

func TestNativeSignInValidatesTheRequest(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"app": "staff", "email": "a@b.co", "password": "x"})), 422)
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"codeChallenge": "short", "email": "a@b.co", "password": "x"})), 422)
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "", "password": ""})), 422)
}

func TestNativeSignInIsThrottledPerEmail(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	var last resp
	for i := 0; i < 12; i++ {
		last = b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "victim@example.com", "password": "guess"}))
	}
	mustStatus(t, last, 429)
	// Another account is unaffected.
	mustStatus(t, b.do("POST", "/v1/auth/native/password", nativeBody(map[string]any{"email": "adaeze@example.com", "password": "Passw0rd!"})), 200)
}

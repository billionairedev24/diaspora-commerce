package httpapi_test

import (
	"net/url"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// signIn runs the whole authorization-code + PKCE flow: BFF → identity authorize (simulated) → BFF callback.
func signIn(t *testing.T, bff *testkit.BFF, b *browser, returnTo string) resp {
	t.Helper()
	r := b.do("GET", "/v1/auth/login?returnTo="+url.QueryEscape(returnTo), nil)
	mustStatus(t, r, 302)
	loc, err := url.Parse(r.header.Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	q := loc.Query()
	if loc.Path != "/oauth2/authorize" || q.Get("response_type") != "code" || q.Get("client_id") != "gateway-bff" ||
		q.Get("code_challenge_method") != "S256" || len(q.Get("code_challenge")) != 43 || q.Get("state") == "" || q.Get("nonce") == "" ||
		q.Get("redirect_uri") != testkit.RedirectURI || !strings.Contains(q.Get("scope"), "openid") {
		t.Fatalf("authorize redirect = %s", loc)
	}
	code := bff.Upstreams.IssueCode(q.Get("code_challenge"), q.Get("nonce"), "user-1", testkit.RedirectURI)
	return b.do("GET", "/login/oauth2/code/afrotransact?code="+url.QueryEscape(code)+"&state="+url.QueryEscape(q.Get("state")), nil)
}

func TestSignInRotatesSessionMergesCartRefreshesAndSignsOut(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	// Guest state before sign-in: a cart line and a deliver-to ZIP.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 2}), 200)
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}), 200)
	guestCookie := b.cookie
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != false || me["user"] != nil {
		t.Fatalf("guest me = %v", me)
	}

	r := signIn(t, bff, b, "/cart?x=1")
	mustStatus(t, r, 302)
	if loc := r.header.Get("Location"); loc != testkit.WebOrigin+"/cart?x=1" {
		t.Fatalf("callback redirect = %s", loc)
	}
	if b.cookie == "" || b.cookie == guestCookie {
		t.Fatal("sign-in must rotate the session id")
	}
	if keys := bff.Store.(*testkit.MemStore).SessionKeys(); len(keys) != 1 {
		t.Fatalf("old session not deleted: %v", keys)
	}
	me := b.do("GET", "/v1/me", nil).json(t)
	user := me["user"].(map[string]any)
	if me["authenticated"] != true || user["initials"] != "AN" || user["email"] != "adaeze@example.com" || me["counts"] == nil {
		t.Fatalf("me = %v", me)
	}
	// Guest cart merged into the customer's cart; ZIP carried over.
	cart := b.do("GET", "/v1/cart", nil).json(t)
	if ls := lines(t, cart); len(ls) != 1 || num(ls[0], "quantity") != 2 {
		t.Fatalf("merged cart = %v", cart)
	}
	if loc := b.do("GET", "/v1/delivery-location", nil).json(t); loc["zip"] != "10456" {
		t.Fatalf("zip after sign-in = %v", loc)
	}

	// Near expiry the BFF refreshes with the (single-use) refresh token.
	before := bff.Upstreams.CallCount("POST /oauth2/token")
	bff.Clock.T = time.Now().Add(bff.Upstreams.AccessTTL - 5*time.Second)
	mustStatus(t, b.do("GET", "/v1/me", nil), 200)
	if after := bff.Upstreams.CallCount("POST /oauth2/token"); after != before+1 {
		t.Fatalf("token calls %d → %d; want one refresh", before, after)
	}
	bff.Clock.T = time.Time{}
	mustStatus(t, b.do("GET", "/v1/me", nil), 200)
	if after := bff.Upstreams.CallCount("POST /oauth2/token"); after != before+1 {
		t.Fatal("a fresh token must not be refreshed again")
	}

	// Sign-out revokes the refresh token and clears the cookie.
	r = b.do("POST", "/v1/auth/sign-out", nil)
	mustStatus(t, r, 204)
	if ck := r.header.Get("Set-Cookie"); !strings.Contains(ck, "Max-Age=0") {
		t.Fatalf("cookie not cleared: %q", ck)
	}
	if rev := bff.Upstreams.Revoked(); len(rev) < 1 || !strings.HasPrefix(rev[0], "rt-") {
		t.Fatalf("revoked = %v", rev)
	}
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != false {
		t.Fatalf("after sign-out = %v", me)
	}
}

func TestRefreshRejectedSignsOut(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}), 200)
	// Another device rotated the refresh token: identity answers invalid_grant and the customer is signed out,
	// keeping the guest state (deliver-to ZIP) on the same session.
	bff.Upstreams.ForgetRefreshTokens()
	bff.Clock.T = time.Now().Add(time.Hour)
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != false {
		t.Fatalf("me after invalid_grant = %v", me)
	}
	if loc := b.do("GET", "/v1/delivery-location", nil).json(t); loc["zip"] != "10456" {
		t.Fatalf("guest state lost: %v", loc)
	}
}

func TestIdentityDownWithExpiredTokenDoesNotCrash(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	bff.Upstreams.Close() // every upstream (identity included) is gone
	bff.Clock.T = time.Now().Add(time.Hour)
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != false {
		t.Fatalf("me with identity down and expired token = %v", me)
	}
	r := b.do("GET", "/v1/search?q=x", nil)
	mustStatus(t, r, 503)
	if c := problemCode(t, r); c != "UPSTREAM_UNAVAILABLE" {
		t.Fatalf("code = %s", c)
	}
}

func TestCallbackRejectsUnknownStateAndForeignReturnTo(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	r := b.do("GET", "/v1/auth/oidc/callback?code=x&state=forged", nil)
	mustStatus(t, r, 302)
	if loc := r.header.Get("Location"); loc != testkit.WebOrigin+"/signin?error=expired" {
		t.Fatalf("location = %s", loc)
	}
	r = signIn(t, bff, b, "https://evil.example/steal")
	if loc := r.header.Get("Location"); loc != testkit.WebOrigin+"/" {
		t.Fatalf("absolute returnTo must be dropped: %s", loc)
	}
	r = b.do("GET", "/v1/auth/login?returnTo=//evil.example", nil)
	if strings.Contains(r.header.Get("Location"), "evil") {
		t.Fatal("protocol-relative returnTo leaked")
	}
	// Identity reported an error.
	r = b.do("GET", "/v1/auth/login", nil)
	state, _ := url.Parse(r.header.Get("Location"))
	r = b.do("GET", "/v1/auth/oidc/callback?error=access_denied&state="+state.Query().Get("state"), nil)
	if loc := r.header.Get("Location"); loc != testkit.WebOrigin+"/signin?error=access_denied" {
		t.Fatalf("location = %s", loc)
	}
}

func TestSocialProviders(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("GET", "/v1/auth/oidc/google/authorize?returnTo=/p/ATSHEA0001", nil)
	mustStatus(t, r, 302)
	loc, _ := url.Parse(r.header.Get("Location"))
	if loc.Query().Get("idp_hint") != "google" {
		t.Fatalf("location = %s", loc)
	}
	for _, p := range []string{"apple", "myspace"} {
		r = b.do("GET", "/v1/auth/oidc/"+p+"/authorize", nil)
		mustStatus(t, r, 404)
		if c := problemCode(t, r); c != "PROVIDER_DISABLED" {
			t.Fatalf("%s: code = %s", p, c)
		}
	}
}

func TestAppBearerTokens(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	b.origin = ""
	tok := bff.Upstreams.Token(jwt.MapClaims{"sub": "user-9", "pt": "CUSTOMER"})
	b.headers["Authorization"] = "Bearer " + tok
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != true {
		t.Fatalf("me = %v", me)
	}
	// Bearer requests skip the Origin check and keep app state per subject.
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}), 200)
	if loc := b.do("GET", "/v1/delivery-location", nil).json(t); loc["zip"] != "10456" {
		t.Fatalf("app zip = %v", loc)
	}
	if b.cookie != "" {
		t.Fatal("apps get no cookie")
	}
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 1}), 200)
	b.headers["Authorization"] = "Bearer not-a-jwt"
	r := b.do("GET", "/v1/me", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "UNAUTHENTICATED" {
		t.Fatalf("code = %s", c)
	}
	// Anonymous app: the cart service issues a guest cart id, echoed as X-Guest-Cart.
	delete(b.headers, "Authorization")
	r = b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 1})
	mustStatus(t, r, 200)
	if b.cookie == "" {
		t.Fatal("anonymous app without bearer uses the cookie session")
	}
}

func TestSignUpAndFirstPartyCredentials(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	form := map[string]any{"firstName": "Ada", "lastName": "Obi", "email": "ada@example.com", "phone": "+17135550142",
		"password": "Str0ngPassword!", "termsAccepted": true, "smsConsent": false, "returnTo": "/cart"}
	r := b.do("POST", "/v1/auth/sign-up", form)
	mustStatus(t, r, 201)
	res := r.json(t)
	if res["status"] != "verification_required" || res["destination"] != "a••@example.com" || num(res, "resendAfterSeconds") != 30 {
		t.Fatalf("sign-up = %v", res)
	}
	ch := res["challengeId"].(string)
	r = b.do("POST", "/v1/auth/email-verification/verify", map[string]any{"challengeId": ch, "code": "000000"})
	mustStatus(t, r, 422)
	if c := problemCode(t, r); c != "INVALID_CODE" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, b.do("POST", "/v1/auth/email-verification/resend", map[string]any{"challengeId": ch}), 202)
	r = b.do("POST", "/v1/auth/email-verification/verify", map[string]any{"challengeId": ch, "code": "123456"})
	mustStatus(t, r, 200)
	if v := r.json(t); v["status"] != "authenticated" || v["redirectTo"] != "/api/bff/v1/auth/login?returnTo=%2Fcart" {
		t.Fatalf("verify = %v", v)
	}
	r = b.do("POST", "/v1/auth/email-verification/resend", map[string]any{"challengeId": ch})
	mustStatus(t, r, 410)
	if c := problemCode(t, r); c != "CHALLENGE_EXPIRED" {
		t.Fatalf("code = %s", c)
	}
	form["email"] = "taken@example.com"
	r = b.do("POST", "/v1/auth/sign-up", form)
	mustStatus(t, r, 409)
	if c := problemCode(t, r); c != "EMAIL_TAKEN" {
		t.Fatalf("code = %s", c)
	}
	form["password"] = "short"
	if c := problemCode(t, b.do("POST", "/v1/auth/sign-up", form)); c != "WEAK_PASSWORD" {
		t.Fatalf("code = %s", c)
	}
	// Password, MFA and passkey steps are validated, then run on identity's first-party API (auth_firstparty_test.go).
	mustStatus(t, b.do("POST", "/v1/auth/sign-in", map[string]any{"email": "bad"}), 422)
	r = b.do("POST", "/v1/auth/sign-in", map[string]any{"email": "ada@example.com", "password": "x"})
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "INVALID_CREDENTIALS" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, b.do("POST", "/v1/auth/mfa/verify", map[string]any{"challengeId": "c", "code": "12"}), 422)
	mustStatus(t, b.do("POST", "/v1/auth/passkey/options", map[string]any{}), 200)
}

func TestAccountAddresses(t *testing.T) {
	bff, b := setup(t, testkit.Options{Payments: true})
	r := b.do("GET", "/v1/account/addresses", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "UNAUTHENTICATED" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	in := map[string]any{"name": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "zip": "77042"}
	r = b.do("POST", "/v1/account/addresses", in)
	mustStatus(t, r, 201)
	if a := r.json(t); a["verified"] != true || a["isDefault"] != true || a["name"] != "Adaeze Nwosu" {
		t.Fatalf("created = %v", a)
	}
	in["line1"] = "1 Nowhere Lane"
	r = b.do("POST", "/v1/account/addresses", in)
	mustStatus(t, r, 422)
	if r.json(t)["errors"].(map[string]any)["line1"] == nil {
		t.Fatal("undeliverable address must report line1")
	}
	in["acceptUnverified"] = true
	r = b.do("POST", "/v1/account/addresses", in)
	mustStatus(t, r, 201)
	if a := r.json(t); a["verified"] != false {
		t.Fatalf("accepted unverified = %v", a)
	}
	in["state"] = "Texas"
	mustStatus(t, b.do("POST", "/v1/account/addresses", in), 422)
	page := b.do("GET", "/v1/account/addresses", nil).json(t)
	if len(page["items"].([]any)) != 2 || page["nextCursor"] != nil {
		t.Fatalf("addresses = %v", page)
	}
	r = b.do("DELETE", "/v1/account/addresses/nope", nil)
	mustStatus(t, r, 404)
	if c := problemCode(t, r); c != "ADDRESS_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
	if me := b.do("GET", "/v1/me", nil).json(t); num(me["counts"].(map[string]any), "addresses") != 2 || num(me["counts"].(map[string]any), "paymentMethods") != 1 {
		t.Fatalf("counts = %v", me["counts"])
	}
	// Saved methods come from payments' /v1/customers/me routes (testkit shopper fakes: a card and a bank account).
	pms := b.do("GET", "/v1/account/payment-methods", nil).json(t)
	if len(pms["items"].([]any)) != 2 {
		t.Fatalf("payment methods = %v", pms)
	}
	mustStatus(t, b.do("POST", "/v1/account/payment-methods/setup-intents", nil), 201)
	// Orders (orders service) and returns (tax-returns) are served.
	for _, p := range []string{"/v1/account/orders", "/v1/account/returns"} {
		mustStatus(t, b.do("GET", p, nil), 200)
	}
	// Checkout summary composes cart + addresses + payment methods.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 2}), 200)
	sum := b.do("GET", "/v1/checkout/summary", nil).json(t)
	if num(sum, "subtotalMinor") != 2500 || num(sum, "shippingMinor") != 599 || num(sum, "totalMinor") != 3099 || len(sum["addresses"].([]any)) != 2 {
		t.Fatalf("summary = %v", sum)
	}
	r = b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "a", "paymentMethodId": "pm_1", "deliverySelections": []any{map[string]any{"packageId": "org_kente:SELLER", "option": "carrier"}}})
	mustStatus(t, r, 422) // missing Idempotency-Key
	b.headers["Idempotency-Key"] = "0123456789abcdef0123"
	r = b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "a", "paymentMethodId": "pm_1", "deliverySelections": []any{map[string]any{"packageId": "org_kente:SELLER", "option": "carrier"}}})
	mustStatus(t, r, 201) // orders' checkout saga (testkit shopper fakes) — the client secret always comes back
	if r.json(t)["paymentClientSecret"] == nil {
		t.Fatalf("placement = %s", r.body)
	}
}

func TestRateLimitOnAuthEndpoints(t *testing.T) {
	u := testkit.NewUpstreams(testkit.Options{})
	t.Cleanup(u.Close)
	bff := testkit.NewBFF(u, testkit.Config{AuthPerMinute: 2})
	b := newBrowser(t, bff.Handler)
	var codes []int
	for range 3 {
		codes = append(codes, b.do("GET", "/v1/auth/login", nil).status)
	}
	if !slices.Equal(codes, []int{302, 302, 429}) {
		t.Fatalf("statuses = %v", codes)
	}
	r := b.do("GET", "/v1/auth/login", nil)
	if r.header.Get("Retry-After") == "" || problemCode(t, r) != "RATE_LIMITED" {
		t.Fatalf("429 = %v %s", r.header, r.body)
	}
}

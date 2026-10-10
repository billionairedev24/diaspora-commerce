package app

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SignInInput is the storefront's email + password form. TrustedDevice is the at_trusted_device cookie (if any).
type SignInInput struct {
	Email         string
	Password      string
	ReturnTo      string
	UserAgent     string
	TrustedDevice string
}

// VerifyMFAInput is the two-step verification form. Method is "totp", "sms" or "recovery" (default: the factor the
// challenge offered first, or SMS after a code was texted).
type VerifyMFAInput struct {
	ChallengeID string
	Code        string
	Method      string
	TrustDevice bool
	UserAgent   string
}

// FirstPartyResult is a sign-in step's answer plus the trusted-device token to keep in an HttpOnly cookie (30 days)
// when the shopper ticked "trust this device".
type FirstPartyResult struct {
	Result              domain.AuthResult
	TrustedDevice       string
	TrustedDeviceExpiry time.Time
}

// mfaChallengeTTL matches identity's 10-minute challenges.
const mfaChallengeTTL = 10 * time.Minute

func (a *authServiceImpl) firstParty() (port.FirstPartyClient, error) {
	if a.o.FirstParty == nil {
		p := domain.UpstreamUnavailable("identity")
		p.Detail = "Sign in on the AfroTransact sign-in page."
		return nil, p.WithCause(errors.New("first-party sign-in is not configured"))
	}
	return a.o.FirstParty, nil
}

func clientContext(c *domain.Caller, ua string) port.ClientContext {
	return port.ClientContext{IP: c.ClientIP, UserAgent: ua}
}

func (a *authServiceImpl) SignIn(ctx context.Context, c *domain.Caller, in SignInInput) (FirstPartyResult, error) {
	fp, err := a.firstParty()
	if err != nil {
		return FirstPartyResult{}, err
	}
	email := strings.ToLower(strings.TrimSpace(in.Email))
	step, err := fp.Password(ctx, clientContext(c, in.UserAgent), email, in.Password, in.TrustedDevice)
	if err != nil {
		return FirstPartyResult{}, mapFirstPartyErr(err)
	}
	return a.next(ctx, c, step, port.Challenge{Email: email, ReturnTo: domain.SafeReturnTo(in.ReturnTo)})
}

// next turns an identity step into the contract's AuthResult: MFA_REQUIRED keeps the challenge (and where to return)
// for the code form; AUTHENTICATED redeems the login ticket and signs the browser in.
func (a *authServiceImpl) next(ctx context.Context, c *domain.Caller, step port.FirstPartyStep, ch port.Challenge) (FirstPartyResult, error) {
	switch step.Status {
	case port.FirstPartyMFARequired:
		methods := []string{}
		switch {
		case step.Factors.TOTP:
			ch.Factor = "TOTP"
			methods = append(methods, "totp")
			if step.Factors.SMS {
				methods = append(methods, "sms")
			}
		case step.Factors.SMS:
			ch.Factor = "SMS"
			methods = append(methods, "sms")
		default:
			ch.Factor = "RECOVERY_CODE"
		}
		ch.Destination = step.Factors.SMSHint
		if ch.Factor == "SMS" {
			// SMS is the only factor: text the code right away.
			if sentTo, err := a.o.FirstParty.SendSMSCode(ctx, clientContext(c, ""), step.ChallengeID); err == nil && sentTo != "" {
				ch.Destination = sentTo
			} else if err != nil {
				a.o.Log.InfoContext(ctx, "could not text the sign-in code", "error", err)
			}
		}
		if err := a.ephemeral.PutChallenge(ctx, "mfa:"+step.ChallengeID, ch, mfaChallengeTTL); err != nil {
			return FirstPartyResult{}, domain.UpstreamUnavailable("valkey").WithCause(err)
		}
		dest := ch.Destination
		if dest == "" {
			dest = maskEmail(ch.Email)
		}
		return FirstPartyResult{Result: domain.AuthResult{Status: "mfa_required", ChallengeID: step.ChallengeID, Methods: methods,
			Destination: dest}}, nil
	case port.FirstPartyAuthenticated:
		if err := a.redeem(ctx, c, step.LoginTicket, ch.ReturnTo); err != nil {
			return FirstPartyResult{}, err
		}
		out := FirstPartyResult{Result: domain.AuthResult{Status: "authenticated", RedirectTo: domain.SafeReturnTo(ch.ReturnTo)},
			TrustedDevice: step.TrustedDeviceToken}
		if step.TrustedDeviceToken != "" {
			out.TrustedDeviceExpiry = a.o.Clock.now().Add(30 * 24 * time.Hour)
			if step.TrustedDeviceExpiresAt != nil {
				out.TrustedDeviceExpiry = *step.TrustedDeviceExpiresAt
			}
		}
		return out, nil
	}
	return FirstPartyResult{}, domain.UpstreamError("identity", http.StatusOK).WithCause(errors.New("unknown sign-in status " + step.Status))
}

// redeem turns the login ticket into tokens on the normal authorization-code flow (state, nonce, PKCE S256),
// server-side and without following redirects, then completes the session like the hosted callback.
func (a *authServiceImpl) redeem(ctx context.Context, c *domain.Caller, ticket, returnTo string) error {
	verifier, state, nonce := randomID(48), randomID(32), randomID(24)
	sum := sha256.Sum256([]byte(verifier))
	q := url.Values{"response_type": {"code"}, "client_id": {a.o.ClientID}, "redirect_uri": {a.o.RedirectURI},
		"scope": {strings.Join(a.o.Scopes, " ")}, "state": {state}, "nonce": {nonce},
		"code_challenge": {base64.RawURLEncoding.EncodeToString(sum[:])}, "code_challenge_method": {"S256"},
		"login_ticket": {ticket}}
	code, gotState, err := a.o.FirstParty.RedeemTicket(ctx, q)
	if err != nil {
		if errors.Is(err, port.ErrInvalidGrant) {
			return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Sign-in expired", "Your sign-in timed out. Please sign in again.").WithCause(err)
		}
		return mapErr("identity", err)
	}
	if gotState != state {
		return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Sign-in expired", "Please sign in again.")
	}
	req := port.AuthRequest{Verifier: verifier, Nonce: nonce, ReturnTo: domain.SafeReturnTo(returnTo)}
	if c.Session != nil && !c.App {
		req.SessionID = c.Session.ID
	}
	if err := a.complete(ctx, c, code, req); err != nil {
		p := domain.UpstreamUnavailable("identity")
		p.Detail = "We couldn't finish signing you in. Please try again."
		return p.WithCause(err)
	}
	return nil
}

func (a *authServiceImpl) mfaChallenge(ctx context.Context, id string) (*port.Challenge, error) {
	ch, err := a.ephemeral.GetChallenge(ctx, "mfa:"+id)
	if err != nil {
		return nil, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	if ch == nil {
		return nil, domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Verification expired", "That code has expired. Sign in again.")
	}
	return ch, nil
}

func (a *authServiceImpl) SendMFACode(ctx context.Context, c *domain.Caller, challengeID string) (domain.AuthResult, error) {
	fp, err := a.firstParty()
	if err != nil {
		return domain.AuthResult{}, err
	}
	ch, err := a.mfaChallenge(ctx, challengeID)
	if err != nil {
		return domain.AuthResult{}, err
	}
	sentTo, err := fp.SendSMSCode(ctx, clientContext(c, ""), challengeID)
	if err != nil {
		return domain.AuthResult{}, mapFirstPartyErr(err)
	}
	ch.Factor = "SMS"
	ch.Destination = firstNonEmpty(sentTo, ch.Destination)
	if err := a.ephemeral.PutChallenge(ctx, "mfa:"+challengeID, *ch, mfaChallengeTTL); err != nil {
		return domain.AuthResult{}, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	return domain.AuthResult{Status: "mfa_required", ChallengeID: challengeID, Methods: []string{"sms"}, Destination: ch.Destination,
		ResendAfterSeconds: ptr(resendAfterSeconds)}, nil
}

var mfaFactors = map[string]string{"totp": "TOTP", "sms": "SMS", "recovery": "RECOVERY_CODE"}

func (a *authServiceImpl) VerifyMFA(ctx context.Context, c *domain.Caller, in VerifyMFAInput) (FirstPartyResult, error) {
	fp, err := a.firstParty()
	if err != nil {
		return FirstPartyResult{}, err
	}
	ch, err := a.mfaChallenge(ctx, in.ChallengeID)
	if err != nil {
		return FirstPartyResult{}, err
	}
	factor := firstNonEmpty(mfaFactors[strings.ToLower(in.Method)], ch.Factor, "TOTP")
	step, err := fp.VerifyMFA(ctx, clientContext(c, in.UserAgent), in.ChallengeID, factor, in.Code, in.TrustDevice)
	if err != nil {
		return FirstPartyResult{}, mapFirstPartyErr(err)
	}
	if step.Status == port.FirstPartyAuthenticated {
		_ = a.ephemeral.DeleteChallenge(ctx, "mfa:"+in.ChallengeID)
	}
	return a.next(ctx, c, step, *ch)
}

func (a *authServiceImpl) PasskeyOptions(ctx context.Context, c *domain.Caller, ua string) (domain.PasskeyOptions, error) {
	fp, err := a.firstParty()
	if err != nil {
		return domain.PasskeyOptions{}, err
	}
	pk, err := fp.PasskeyOptions(ctx, clientContext(c, ua))
	if err != nil {
		return domain.PasskeyOptions{}, mapFirstPartyErr(err)
	}
	out := domain.PasskeyOptions{ChallengeID: pk.ChallengeID, Challenge: pk.PublicKey.Challenge, RPID: pk.PublicKey.RPID,
		Timeout: pk.PublicKey.Timeout, UserVerification: firstNonEmpty(pk.PublicKey.UserVerification, "required"),
		AllowCredentials: []domain.PasskeyCredential{}}
	for _, cr := range pk.PublicKey.AllowCredentials {
		out.AllowCredentials = append(out.AllowCredentials, domain.PasskeyCredential{ID: cr.ID, Type: firstNonEmpty(cr.Type, "public-key")})
	}
	if out.Timeout <= 0 {
		out.Timeout = 60000
	}
	return out, nil
}

func (a *authServiceImpl) PasskeyVerify(ctx context.Context, c *domain.Caller, challengeID string, credential json.RawMessage, ua string) (FirstPartyResult, error) {
	fp, err := a.firstParty()
	if err != nil {
		return FirstPartyResult{}, err
	}
	step, err := fp.PasskeyVerify(ctx, clientContext(c, ua), challengeID, credential)
	if err != nil {
		return FirstPartyResult{}, mapFirstPartyErr(err)
	}
	// Passkeys need no second factor; the redirect goes home (the passkey button has no returnTo).
	return a.next(ctx, c, step, port.Challenge{ReturnTo: "/"})
}

// mapFirstPartyErr maps identity's first-party problem codes onto the contract's; lockouts keep Retry-After.
func mapFirstPartyErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("identity", err)
	}
	switch p.Code {
	case "identity.invalid-credentials":
		return domain.NewProblem(http.StatusUnauthorized, domain.CodeInvalidCredentials, "Incorrect email or password",
			"That email and password don't match. Try again or reset your password.").WithCause(err)
	case "identity.passkey-rejected":
		return domain.NewProblem(http.StatusUnauthorized, domain.CodeInvalidCredentials, "Passkey not accepted",
			"We couldn't verify that passkey. Try again or sign in with your password.").WithCause(err)
	case "identity.invalid-code":
		return domain.FieldProblem(domain.CodeInvalidCode, "Incorrect code", "code", "That code is incorrect.").WithCause(err)
	case "identity.challenge-invalid":
		return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Verification expired", "That code has expired. Sign in again.").WithCause(err)
	case "identity.email-not-verified":
		return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Verify your email",
			"Confirm your email address first — we sent you a code when you signed up.").WithCause(err)
	case "identity.account-disabled":
		return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Account disabled", "This account can't sign in. Contact support.").WithCause(err)
	case "identity.sign-in-locked", "identity.too-many-codes", "identity.resend-too-soon":
		pr := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many attempts",
			firstNonEmpty(p.Detail, "Too many attempts. Please wait and try again."))
		pr.RetryAfter = max(1, p.RetryAfter)
		return pr.WithCause(err)
	case "identity.first-party-client-required":
		return domain.UpstreamUnavailable("identity").WithCause(err)
	}
	if p.Status == http.StatusTooManyRequests {
		pr := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many attempts", "Please wait and try again.")
		pr.RetryAfter = max(1, p.RetryAfter)
		return pr.WithCause(err)
	}
	return mapErr("identity", err)
}

package app

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// NativeAuthService signs a person in inside the native apps (customer and driver): the app shows its own email and
// password form and posts the credentials here, the BFF checks them with identity's first-party sign-in API (with
// the second-factor step when the account has one) and redeems the resulting one-time login ticket on the app's own
// authorization-code + PKCE request. The app receives the authorization code and exchanges it at identity's token
// endpoint itself, so it ends up with its own access and refresh tokens, exactly as after the hosted page, without
// the system browser and without a second sign-in.
type NativeAuthService interface {
	// Password checks the credentials; the result is a second-factor step or the authorization code.
	Password(ctx context.Context, in NativePassword) (NativeResult, error)
	// SendSMSCode texts a sign-in code for a second-factor step.
	SendSMSCode(ctx context.Context, in NativeSMS) (port.NativeSMSSent, error)
	// VerifyFactor checks the second factor; the result carries the authorization code.
	VerifyFactor(ctx context.Context, in NativeFactor) (NativeResult, error)
}

// NativeApp is one native app's identity client.
type NativeApp struct {
	// ClientID is the public OAuth client registered at identity (customer-app, driver-app).
	ClientID string
	// Scope requested at /oauth2/authorize; must be within the client's registered scopes.
	Scope string
}

// NativeAuthOptions configure native sign-in.
type NativeAuthOptions struct {
	// Apps maps the app key the app sends ("customer", "driver") to its identity client.
	Apps map[string]NativeApp
}

// NativeRequest is what every step carries: the app, its PKCE challenge and redirect URI (so the step that completes
// the sign-in can obtain the code), and where the call came from.
type NativeRequest struct {
	App           string
	CodeChallenge string
	RedirectURI   string
	IP            string
	UserAgent     string
}

// NativePassword is step one.
type NativePassword struct {
	NativeRequest
	Email, Password string
	// TrustedDeviceToken skips the second factor when the phone was trusted by an earlier sign-in.
	TrustedDeviceToken string
}

// NativeSMS asks for a text-message code.
type NativeSMS struct {
	NativeRequest
	ChallengeID string
}

// NativeFactor is the second-factor code.
type NativeFactor struct {
	NativeRequest
	ChallengeID string
	// Factor is TOTP, SMS or RECOVERY_CODE.
	Factor      string
	Code        string
	TrustDevice bool
}

// NativeResult is MFA_REQUIRED (continue with ChallengeID and a factor) or AUTHENTICATED (Code is the authorization
// code the app exchanges at identity with its PKCE verifier).
type NativeResult struct {
	Status             string              `json:"status"`
	ChallengeID        string              `json:"challengeId,omitempty"`
	ChallengeExpiresAt string              `json:"challengeExpiresAt,omitempty"`
	Factors            *port.NativeFactors `json:"factors,omitempty"`
	Code               string              `json:"code,omitempty"`
	TrustedDeviceToken string              `json:"trustedDeviceToken,omitempty"`
}

// DefaultNativeApps are the two native apps and their public clients at identity (scopes as registered there).
func DefaultNativeApps() map[string]NativeApp {
	return map[string]NativeApp{
		"customer": {ClientID: "customer-app", Scope: "openid profile email phone offline_access"},
		"driver":   {ClientID: "driver-app", Scope: "openid profile email offline_access"},
	}
}

type nativeAuthImpl struct {
	client port.NativeSignInClient
	o      NativeAuthOptions
}

var _ NativeAuthService = (*nativeAuthImpl)(nil)

// NewNativeAuthService returns the NativeAuthService.
func NewNativeAuthService(client port.NativeSignInClient, o NativeAuthOptions) NativeAuthService {
	if len(o.Apps) == 0 {
		o.Apps = DefaultNativeApps()
	}
	return &nativeAuthImpl{client: client, o: o}
}

const (
	statusMFA           = "MFA_REQUIRED"
	statusAuthenticated = "AUTHENTICATED"
	maxChallengeLen     = 43 // S256 code challenge: 43 base64url characters
)

func (n *nativeAuthImpl) resolve(r NativeRequest, needPKCE bool) (NativeApp, port.NativeWho, error) {
	app, ok := n.o.Apps[strings.ToLower(strings.TrimSpace(r.App))]
	if !ok {
		return NativeApp{}, port.NativeWho{}, domain.FieldProblem(domain.CodeValidationFailed, "Unknown app", "app", "Unknown app.")
	}
	if needPKCE {
		fields := map[string]string{}
		if len(r.CodeChallenge) != maxChallengeLen {
			fields["codeChallenge"] = "A PKCE S256 code challenge is required."
		}
		if strings.TrimSpace(r.RedirectURI) == "" {
			fields["redirectUri"] = "The app's redirect URI is required."
		}
		if len(fields) > 0 {
			return NativeApp{}, port.NativeWho{}, domain.ValidationProblem(fields)
		}
	}
	return app, port.NativeWho{IP: r.IP, UserAgent: r.UserAgent, AppClientID: app.ClientID}, nil
}

func (n *nativeAuthImpl) Password(ctx context.Context, in NativePassword) (NativeResult, error) {
	fields := map[string]string{}
	if strings.TrimSpace(in.Email) == "" {
		fields["email"] = "Enter your email address."
	}
	if in.Password == "" {
		fields["password"] = "Enter your password."
	}
	if len(fields) > 0 {
		return NativeResult{}, domain.ValidationProblem(fields)
	}
	app, who, err := n.resolve(in.NativeRequest, true)
	if err != nil {
		return NativeResult{}, err
	}
	step, err := n.client.Password(ctx, strings.TrimSpace(in.Email), in.Password, in.TrustedDeviceToken, who)
	if err != nil {
		return NativeResult{}, mapNativeErr(err)
	}
	return n.finish(ctx, app, in.NativeRequest, step)
}

func (n *nativeAuthImpl) SendSMSCode(ctx context.Context, in NativeSMS) (port.NativeSMSSent, error) {
	_, who, err := n.resolve(in.NativeRequest, false)
	if err != nil {
		return port.NativeSMSSent{}, err
	}
	sent, err := n.client.SendSMSCode(ctx, in.ChallengeID, who)
	return sent, mapNativeErr(err)
}

func (n *nativeAuthImpl) VerifyFactor(ctx context.Context, in NativeFactor) (NativeResult, error) {
	app, who, err := n.resolve(in.NativeRequest, true)
	if err != nil {
		return NativeResult{}, err
	}
	if strings.TrimSpace(in.Code) == "" {
		return NativeResult{}, domain.FieldProblem(domain.CodeInvalidCode, "Enter the code", "code", "Enter the code.")
	}
	step, err := n.client.VerifyFactor(ctx, in.ChallengeID, strings.ToUpper(strings.TrimSpace(in.Factor)), strings.TrimSpace(in.Code),
		in.TrustDevice, who)
	if err != nil {
		return NativeResult{}, mapNativeErr(err)
	}
	return n.finish(ctx, app, in.NativeRequest, step)
}

// finish turns identity's step into the app's: an MFA challenge passes through, a login ticket is redeemed for the
// authorization code.
func (n *nativeAuthImpl) finish(ctx context.Context, app NativeApp, r NativeRequest, step port.NativeStep) (NativeResult, error) {
	if step.Status == statusMFA {
		return NativeResult{Status: statusMFA, ChallengeID: step.ChallengeID, ChallengeExpiresAt: step.ChallengeExpiresAt,
			Factors: step.Factors}, nil
	}
	if step.Status != statusAuthenticated || step.LoginTicket == "" {
		return NativeResult{}, domain.UpstreamError("identity", http.StatusBadGateway)
	}
	code, err := n.client.Authorize(ctx, port.NativeAuthorize{ClientID: app.ClientID, RedirectURI: r.RedirectURI, Scope: app.Scope,
		CodeChallenge: r.CodeChallenge, State: "native", Ticket: step.LoginTicket})
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && (p.Code == "invalid_request" || p.Code == "invalid_redirect_uri") {
			return NativeResult{}, domain.FieldProblem(domain.CodeValidationFailed, "Sign-in not allowed", "redirectUri", "This app's redirect address is not registered.").WithCause(err)
		}
		return NativeResult{}, mapNativeErr(err)
	}
	return NativeResult{Status: statusAuthenticated, Code: code, TrustedDeviceToken: step.TrustedDeviceToken}, nil
}

// mapNativeErr turns identity's sign-in problems into the contract's. A wrong password is 401 INVALID_CREDENTIALS
// (the app shows it on the form: no token was sent, so it is not a lost session).
func mapNativeErr(err error) error {
	if err == nil {
		return nil
	}
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("identity", err)
	}
	switch p.Code {
	case "identity.invalid-credentials":
		return domain.NewProblem(http.StatusUnauthorized, domain.CodeInvalidCredentials, "Wrong email or password",
			"The email or password is incorrect.").WithCause(err)
	case "identity.sign-in-locked":
		pr := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many attempts",
			"Too many sign-in attempts. Try again later, or reset your password.")
		pr.RetryAfter = max(1, p.RetryAfter)
		return pr.WithCause(err)
	case "identity.email-not-verified":
		return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Confirm your email",
			"Confirm your email address before signing in. We sent you a link.").WithCause(err)
	case "identity.account-disabled":
		return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Account disabled",
			"This account is disabled. Contact support.").WithCause(err)
	case "identity.challenge-invalid":
		return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Start again",
			"This sign-in expired. Enter your email and password again.").WithCause(err)
	case "identity.native-client-not-allowed":
		return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Not allowed", "This app cannot sign in here.").WithCause(err)
	case "invalid_grant":
		return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Start again",
			"This sign-in expired. Enter your email and password again.").WithCause(err)
	}
	if errors.Is(err, port.ErrInvalidGrant) {
		return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Start again", "This sign-in expired.").WithCause(err)
	}
	return mapIdentityErr(err)
}

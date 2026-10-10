package port

import (
	"context"
	"encoding/json"
	"net/url"
	"time"
)

// First-party sign-in (identity S209): the storefront's own password, MFA and passkey forms. The BFF calls identity's
// /v1/auth/* with its client-credentials token (gateway-bff, scope auth:first-party) and always passes the end
// user's IP and user agent. A completed sign-in yields a one-time login ticket the BFF redeems on the normal
// authorization-code flow.

// FirstPartyFactors are the second factors an MFA challenge accepts.
type FirstPartyFactors struct {
	TOTP         bool   `json:"totp"`
	SMS          bool   `json:"sms"`
	SMSHint      string `json:"smsHint"`
	RecoveryCode bool   `json:"recoveryCode"`
}

// FirstPartyStep is identity's answer to a sign-in step: MFA_REQUIRED (challenge + factors) or AUTHENTICATED (login
// ticket, and a trusted-device token when the shopper asked to trust the device).
type FirstPartyStep struct {
	Status                 string            `json:"status"`
	ChallengeID            string            `json:"challengeId"`
	Factors                FirstPartyFactors `json:"factors"`
	LoginTicket            string            `json:"loginTicket"`
	TrustedDeviceToken     string            `json:"trustedDeviceToken"`
	TrustedDeviceExpiresAt *time.Time        `json:"trustedDeviceExpiresAt"`
}

// Sign-in step statuses.
const (
	FirstPartyMFARequired   = "MFA_REQUIRED"
	FirstPartyAuthenticated = "AUTHENTICATED"
)

// PasskeyChallenge is identity's WebAuthn assertion request (PublicKeyCredentialRequestOptions, base64url fields).
type PasskeyChallenge struct {
	ChallengeID string    `json:"challengeId"`
	ExpiresAt   time.Time `json:"expiresAt"`
	PublicKey   struct {
		Challenge        string `json:"challenge"`
		RPID             string `json:"rpId"`
		Timeout          int    `json:"timeout"`
		UserVerification string `json:"userVerification"`
		AllowCredentials []struct {
			ID   string `json:"id"`
			Type string `json:"type"`
		} `json:"allowCredentials"`
	} `json:"publicKey"`
}

// ClientContext is the end user's network identity, required by identity's lockouts.
type ClientContext struct {
	IP        string
	UserAgent string
}

// FirstPartyClient calls identity's first-party sign-in API. 4xx answers are *UpstreamProblem with identity's codes
// (identity.invalid-credentials, identity.invalid-code, identity.challenge-invalid, identity.passkey-rejected,
// identity.email-not-verified, identity.account-disabled, identity.sign-in-locked with RetryAfter …).
type FirstPartyClient interface {
	Password(ctx context.Context, cc ClientContext, email, password, trustedDeviceToken string) (FirstPartyStep, error)
	SendSMSCode(ctx context.Context, cc ClientContext, challengeID string) (sentTo string, err error)
	VerifyMFA(ctx context.Context, cc ClientContext, challengeID, factor, code string, trustDevice bool) (FirstPartyStep, error)
	PasskeyOptions(ctx context.Context, cc ClientContext) (PasskeyChallenge, error)
	PasskeyVerify(ctx context.Context, cc ClientContext, challengeID string, credential json.RawMessage) (FirstPartyStep, error)
	// RedeemTicket calls GET /oauth2/authorize with the ticket and PKCE parameters without following redirects and
	// returns the code and state of the 302. ErrInvalidGrant when the ticket is unknown, used or expired.
	RedeemTicket(ctx context.Context, authorize url.Values) (code, state string, err error)
}

// ServiceTokens issues the BFF's own client-credentials access tokens (gateway-bff) for calls made on behalf of
// anonymous shoppers (e.g. the careers résumé upload). scope "" asks for the client's default scopes.
type ServiceTokens interface {
	Token(ctx context.Context, scope string) (string, error)
}

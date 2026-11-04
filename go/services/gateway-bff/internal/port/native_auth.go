package port

import "context"

// ---------------------------------------------------------------- identity first-party sign-in for the native apps

// NativeWho is the person's phone as identity should see it (throttling, audit) and the native app the login ticket
// is for.
type NativeWho struct {
	IP        string
	UserAgent string
	// AppClientID is identity's public client of the app: customer-app or driver-app.
	AppClientID string
}

// NativeFactors are the second factors the account has (identity's SecondFactorOptions).
type NativeFactors struct {
	TOTP         bool   `json:"totp"`
	SMS          bool   `json:"sms"`
	SMSHint      string `json:"smsHint,omitempty"`
	RecoveryCode bool   `json:"recoveryCode"`
}

// NativeStep is identity's SignInStepResponse: MFA_REQUIRED (continue with ChallengeID) or AUTHENTICATED (redeem
// LoginTicket once, within a minute).
type NativeStep struct {
	Status             string         `json:"status"`
	ChallengeID        string         `json:"challengeId"`
	ChallengeExpiresAt string         `json:"challengeExpiresAt"`
	Factors            *NativeFactors `json:"factors"`
	LoginTicket        string         `json:"loginTicket"`
	TrustedDeviceToken string         `json:"trustedDeviceToken"`
}

// NativeSMSSent is identity's SmsCodeResponse.
type NativeSMSSent struct {
	SentTo    string `json:"sentTo"`
	ExpiresAt string `json:"expiresAt"`
}

// NativeAuthorize redeems a login ticket on the app's own authorization-code + PKCE request.
type NativeAuthorize struct {
	ClientID      string
	RedirectURI   string
	Scope         string
	CodeChallenge string
	State         string
	Ticket        string
}

// NativeSignInClient calls identity's first-party sign-in API with the BFF's client-credentials token (scope
// auth:first-party) and redeems the resulting login ticket on /oauth2/authorize for the app's public client.
type NativeSignInClient interface {
	Password(ctx context.Context, email, password, trustedDeviceToken string, who NativeWho) (NativeStep, error)
	SendSMSCode(ctx context.Context, challengeID string, who NativeWho) (NativeSMSSent, error)
	VerifyFactor(ctx context.Context, challengeID, factor, code string, trustDevice bool, who NativeWho) (NativeStep, error)
	// Authorize returns the authorization code identity issued to the app's redirect URI.
	Authorize(ctx context.Context, in NativeAuthorize) (string, error)
}

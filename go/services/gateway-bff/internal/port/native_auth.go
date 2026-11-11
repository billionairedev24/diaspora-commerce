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
	TOTP bool `json:"totp"`
	// EmailOTP is true when the person turned email codes on; EmailHint is the masked address the code goes to.
	EmailOTP  bool   `json:"emailOtp"`
	EmailHint string `json:"emailHint,omitempty"`
	// SMS is only ever true while identity's SMS feature flag is on (off by default).
	SMS          bool   `json:"sms"`
	SMSHint      string `json:"smsHint,omitempty"`
	RecoveryCode bool   `json:"recoveryCode"`
	// EnrolMethods (MFA_ENROLMENT_REQUIRED only) are the methods the person may set up: TOTP, EMAIL_OTP.
	EnrolMethods []string `json:"enrolMethods,omitempty"`
}

// NativeStep is identity's SignInStepResponse: MFA_REQUIRED (the person enrolled a factor: continue with ChallengeID),
// MFA_ENROLMENT_REQUIRED (MFA is mandatory for them and nothing is set up: no code was sent, enrol with ChallengeID)
// or AUTHENTICATED (redeem LoginTicket once, within a minute).
type NativeStep struct {
	Status             string         `json:"status"`
	ChallengeID        string         `json:"challengeId"`
	ChallengeExpiresAt string         `json:"challengeExpiresAt"`
	Factors            *NativeFactors `json:"factors"`
	LoginTicket        string         `json:"loginTicket"`
	TrustedDeviceToken string         `json:"trustedDeviceToken"`
	// MFASetupSuggested asks the app to show a gentle, dismissable "Secure your account" prompt (never blocking).
	MFASetupSuggested bool `json:"mfaSetupSuggested"`
}

// NativeTotpSetup is the authenticator secret and otpauth URI to render as a QR code.
type NativeTotpSetup struct {
	Secret     string `json:"secret"`
	OtpauthURI string `json:"otpauthUri"`
}

// NativeEnrolment is identity's EnrolmentCompleted: the forced enrolment finished; Step carries the login ticket.
type NativeEnrolment struct {
	RecoveryCodes []string   `json:"recoveryCodes"`
	Step          NativeStep `json:"step"`
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
	// SendEmailCode emails a sign-in code (identity only sends it to people who turned email codes on).
	SendEmailCode(ctx context.Context, challengeID string, who NativeWho) (NativeSMSSent, error)
	// EnrolTOTP, ConfirmEnrolTOTP, EnrolEmail and ConfirmEnrolEmail are the forced enrolment of an
	// MFA_ENROLMENT_REQUIRED challenge; confirming completes the sign-in (the step carries the login ticket).
	EnrolTOTP(ctx context.Context, challengeID string, who NativeWho) (NativeTotpSetup, error)
	ConfirmEnrolTOTP(ctx context.Context, challengeID, code string, who NativeWho) (NativeEnrolment, error)
	EnrolEmail(ctx context.Context, challengeID string, who NativeWho) (NativeSMSSent, error)
	ConfirmEnrolEmail(ctx context.Context, challengeID, code string, who NativeWho) (NativeEnrolment, error)
	// Authorize returns the authorization code identity issued to the app's redirect URI.
	Authorize(ctx context.Context, in NativeAuthorize) (string, error)
}

package app

import (
	"context"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SecurityService is Login & security over identity /v1/me for every app (storefront, Seller Center, staff console):
// password, authenticator app and SMS codes, recovery codes, passkeys, trusted browsers, signed-in sessions, recent
// sign-in activity, push devices, linked accounts and notification switches.
type SecurityService interface {
	Overview(ctx context.Context, c *domain.Caller) (domain.SecurityOverview, error)
	ChangePassword(ctx context.Context, c *domain.Caller, in domain.PasswordChange) error
	StartTOTP(ctx context.Context, c *domain.Caller) (domain.TotpSetup, error)
	ConfirmTOTP(ctx context.Context, c *domain.Caller, code string) (domain.RecoveryCodes, error)
	DisableTOTP(ctx context.Context, c *domain.Caller) error
	StartSMS(ctx context.Context, c *domain.Caller) (string, error)
	ConfirmSMS(ctx context.Context, c *domain.Caller, code string) (domain.RecoveryCodes, error)
	DisableSMS(ctx context.Context, c *domain.Caller) error
	RecoveryCodes(ctx context.Context, c *domain.Caller) (domain.RecoveryCodes, error)
	RemovePasskey(ctx context.Context, c *domain.Caller, id string) error
	RenamePasskey(ctx context.Context, c *domain.Caller, id, label string) (domain.Passkey, error)
	ForgetTrustedBrowser(ctx context.Context, c *domain.Caller, id string) error
	ForgetTrustedBrowsers(ctx context.Context, c *domain.Caller) error
	// SignInActivity is the account's 20 most recent sign-ins (every app), newest first.
	SignInActivity(ctx context.Context, c *domain.Caller) ([]domain.SignInEvent, error)
	RevokeSession(ctx context.Context, c *domain.Caller, id string) error
	RevokeOtherSessions(ctx context.Context, c *domain.Caller) (int, error)
	RemoveDevice(ctx context.Context, c *domain.Caller, id string) error
	Unlink(ctx context.Context, c *domain.Caller, provider string) (domain.LinkedAccounts, error)
	Preferences(ctx context.Context, c *domain.Caller) (domain.NotificationPreferences, error)
	SavePreferences(ctx context.Context, c *domain.Caller, p domain.NotificationPreferences) (domain.NotificationPreferences, error)
}

type securityServiceImpl struct{ d ShopperDeps }

var _ SecurityService = (*securityServiceImpl)(nil)

// NewSecurityService returns the SecurityService.
func NewSecurityService(d ShopperDeps) SecurityService { return &securityServiceImpl{d: d} }

var sixDigits = regexp.MustCompile(`^\d{6}$`)

// securityErr maps identity's refusals: wrong code (422 on code), last sign-in method (409), staff can't disable MFA
// (403), wrong current password (422 on currentPassword).
func securityErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("identity", err)
	}
	code := strings.ToLower(p.Code)
	switch {
	case strings.Contains(code, "invalid-code") || strings.Contains(code, "code-invalid"):
		return domain.FieldProblem(domain.CodeInvalidCode, "Incorrect code", "code", firstNonEmpty(p.Detail, "That code is incorrect or expired.")).WithCause(err)
	case strings.Contains(code, "current-password") || strings.Contains(code, "invalid-password"):
		return domain.FieldProblem(domain.CodeValidationFailed, "Check your password", "currentPassword",
			firstNonEmpty(p.Detail, "Your current password is incorrect.")).WithCause(err)
	case strings.Contains(code, "weak-password") || strings.Contains(code, "password-policy"):
		return domain.FieldProblem(domain.CodeWeakPassword, "Password too weak", "newPassword", firstNonEmpty(p.Detail, "Choose a stronger password.")).WithCause(err)
	case strings.Contains(code, "staff-mfa-required"):
		return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Two-step verification is required",
			firstNonEmpty(p.Detail, "Two-step verification is mandatory for staff accounts: turn on another method first.")).WithCause(err)
	case strings.Contains(code, "trusted-browser-not-found"):
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That browser is no longer trusted.").WithCause(err)
	case strings.Contains(code, "last-sign-in-method") || strings.Contains(code, "last-method"):
		return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Keep one way to sign in",
			firstNonEmpty(p.Detail, "Add another sign-in method before removing this one.")).WithCause(err)
	case p.Status == http.StatusUnprocessableEntity || p.Status == http.StatusBadRequest:
		if len(p.Fields) > 0 {
			return domain.ValidationProblem(p.Fields).WithCause(err)
		}
		return domain.FieldProblem(domain.CodeValidationFailed, "Check the details", "_", firstNonEmpty(p.Detail, "That didn't work.")).WithCause(err)
	}
	return mapIdentityErr(err)
}

func isoOrNil(s string) *string { return optStr(s) }

func (s *securityServiceImpl) Overview(ctx context.Context, c *domain.Caller) (domain.SecurityOverview, error) {
	if err := requireCustomer(c); err != nil {
		return domain.SecurityOverview{}, err
	}
	var (
		mfa      port.UpMfa
		passkeys []port.UpPasskey
		sessions []port.UpSession
		devices  []port.UpDevice
		linked   port.UpLinkedAccounts
		trusted  []port.UpTrustedBrowser
	)
	errs := make([]error, 6)
	var wg sync.WaitGroup
	wg.Go(func() { mfa, errs[0] = s.d.Security.MFA(ctx, c.BearerToken) })
	wg.Go(func() { passkeys, errs[1] = s.d.Security.Passkeys(ctx, c.BearerToken) })
	wg.Go(func() { sessions, errs[2] = s.d.Security.Sessions(ctx, c.BearerToken) })
	wg.Go(func() { devices, errs[3] = s.d.Security.Devices(ctx, c.BearerToken) })
	wg.Go(func() { linked, errs[4] = s.d.Security.Linked(ctx, c.BearerToken) })
	wg.Go(func() { trusted, errs[5] = s.d.Security.TrustedBrowsers(ctx, c.BearerToken) })
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			return domain.SecurityOverview{}, securityErr(err)
		}
	}
	out := domain.SecurityOverview{
		MFA: domain.MfaStatus{TOTP: mfa.TOTP, SMS: mfa.SMS, SMSPhone: optStr(mfa.SMSPhone), RecoveryCodesLeft: mfa.RecoveryCodesLeft,
			Required: mfa.Required, MayDisable: mfa.MayDisable, MayRemoveTOTP: mfa.TOTP && mfa.MayRemoveTOTP, MayRemoveSMS: mfa.SMS && mfa.MayRemoveSMS},
		Passkeys: []domain.Passkey{}, TrustedBrowsers: []domain.TrustedBrowser{}, Sessions: []domain.SignedInSession{}, Devices: []domain.PushDevice{},
		Linked:                 domain.LinkedAccounts{Accounts: []domain.LinkedAccount{}, HasPassword: linked.HasPassword},
		PasskeyRegistrationURL: strings.TrimSuffix(s.d.IdentityPublicURL, "/") + "/webauthn/register",
	}
	for _, p := range passkeys {
		out.Passkeys = append(out.Passkeys, toPasskey(p))
	}
	for _, b := range trusted {
		out.TrustedBrowsers = append(out.TrustedBrowsers, domain.TrustedBrowser{ID: b.ID, Label: domain.DescribeUserAgent(b.UserAgent),
			CreatedAt: isoOrNil(b.CreatedAt), ExpiresAt: isoOrNil(b.ExpiresAt)})
	}
	for _, x := range sessions {
		out.Sessions = append(out.Sessions, domain.SignedInSession{ID: x.ID, ClientName: sessionLabel(x),
			SignedInAt: isoOrNil(x.SignedInAt), LastActiveAt: isoOrNil(x.LastActiveAt), Current: x.Current})
	}
	for _, d := range devices {
		out.Devices = append(out.Devices, domain.PushDevice{ID: d.ID, Platform: d.Platform, DeviceName: firstNonEmpty(d.DeviceName, d.Platform),
			LastSeenAt: isoOrNil(d.LastSeenAt)})
	}
	for _, a := range linked.Accounts {
		out.Linked.Accounts = append(out.Linked.Accounts, domain.LinkedAccount{Provider: a.Provider, Email: optStr(a.Email), CanUnlink: a.CanUnlink})
	}
	return out, nil
}

func (s *securityServiceImpl) ChangePassword(ctx context.Context, c *domain.Caller, in domain.PasswordChange) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	errs := map[string]string{}
	if l := len(in.NewPassword); l < 12 || l > 128 {
		errs["newPassword"] = "Use 12 to 128 characters"
	}
	if len(in.CurrentPassword) > 128 {
		errs["currentPassword"] = "Too long"
	}
	if len(errs) > 0 {
		return domain.ValidationProblem(errs)
	}
	if err := s.d.Security.ChangePassword(ctx, c.BearerToken, in); err != nil {
		return securityErr(err)
	}
	return nil
}

func checkCode(code string) error {
	if !sixDigits.MatchString(strings.TrimSpace(code)) {
		return domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "code", "Enter the 6-digit code")
	}
	return nil
}

func codes(rc domain.RecoveryCodes) domain.RecoveryCodes {
	rc.RecoveryCodes = orEmpty(rc.RecoveryCodes)
	return rc
}

func (s *securityServiceImpl) StartTOTP(ctx context.Context, c *domain.Caller) (domain.TotpSetup, error) {
	if err := requireCustomer(c); err != nil {
		return domain.TotpSetup{}, err
	}
	t, err := s.d.Security.StartTOTP(ctx, c.BearerToken)
	return t, wrapSec(err)
}

func (s *securityServiceImpl) ConfirmTOTP(ctx context.Context, c *domain.Caller, code string) (domain.RecoveryCodes, error) {
	if err := requireCustomer(c); err != nil {
		return domain.RecoveryCodes{}, err
	}
	if err := checkCode(code); err != nil {
		return domain.RecoveryCodes{}, err
	}
	rc, err := s.d.Security.ConfirmTOTP(ctx, c.BearerToken, strings.TrimSpace(code))
	return codes(rc), wrapSec(err)
}

func (s *securityServiceImpl) DisableTOTP(ctx context.Context, c *domain.Caller) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.DisableTOTP(ctx, c.BearerToken))
}

func (s *securityServiceImpl) StartSMS(ctx context.Context, c *domain.Caller) (string, error) {
	if err := requireCustomer(c); err != nil {
		return "", err
	}
	at, err := s.d.Security.StartSMS(ctx, c.BearerToken)
	return at, wrapSec(err)
}

func (s *securityServiceImpl) ConfirmSMS(ctx context.Context, c *domain.Caller, code string) (domain.RecoveryCodes, error) {
	if err := requireCustomer(c); err != nil {
		return domain.RecoveryCodes{}, err
	}
	if err := checkCode(code); err != nil {
		return domain.RecoveryCodes{}, err
	}
	rc, err := s.d.Security.ConfirmSMS(ctx, c.BearerToken, strings.TrimSpace(code))
	return codes(rc), wrapSec(err)
}

func (s *securityServiceImpl) DisableSMS(ctx context.Context, c *domain.Caller) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.DisableSMS(ctx, c.BearerToken))
}

func (s *securityServiceImpl) RecoveryCodes(ctx context.Context, c *domain.Caller) (domain.RecoveryCodes, error) {
	if err := requireCustomer(c); err != nil {
		return domain.RecoveryCodes{}, err
	}
	rc, err := s.d.Security.RecoveryCodes(ctx, c.BearerToken)
	return codes(rc), wrapSec(err)
}

func (s *securityServiceImpl) RemovePasskey(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.RemovePasskey(ctx, c.BearerToken, id))
}

// sessionLabel names the app behind a session for people ("AfroTransact on the web" for the BFF's web sessions).
func sessionLabel(x port.UpSession) string {
	if x.ClientID != "" {
		return domain.ClientLabel(x.ClientID, x.ClientName)
	}
	return firstNonEmpty(x.ClientName, "AfroTransact")
}

func toPasskey(p port.UpPasskey) domain.Passkey {
	return domain.Passkey{CredentialID: p.CredentialID, Label: firstNonEmpty(p.Label, "Passkey"),
		CreatedAt: isoOrNil(p.CreatedAt), LastUsedAt: isoOrNil(p.LastUsedAt)}
}

func (s *securityServiceImpl) RenamePasskey(ctx context.Context, c *domain.Caller, id, label string) (domain.Passkey, error) {
	if err := requireCustomer(c); err != nil {
		return domain.Passkey{}, err
	}
	label = strings.TrimSpace(label)
	if label == "" || utf8.RuneCountInString(label) > 64 {
		return domain.Passkey{}, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "label", "Use 1 to 64 characters")
	}
	p, err := s.d.Security.RenamePasskey(ctx, c.BearerToken, id, label)
	if err != nil {
		return domain.Passkey{}, securityErr(err)
	}
	return toPasskey(p), nil
}

func (s *securityServiceImpl) ForgetTrustedBrowser(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.ForgetTrustedBrowser(ctx, c.BearerToken, id))
}

func (s *securityServiceImpl) ForgetTrustedBrowsers(ctx context.Context, c *domain.Caller) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.ForgetTrustedBrowsers(ctx, c.BearerToken))
}

// signInActivityLimit is how many recent sign-ins the security pages list.
const signInActivityLimit = 20

func (s *securityServiceImpl) SignInActivity(ctx context.Context, c *domain.Caller) ([]domain.SignInEvent, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	rows, err := s.d.Security.SignInActivity(ctx, c.BearerToken, signInActivityLimit)
	if err != nil {
		return nil, securityErr(err)
	}
	out := make([]domain.SignInEvent, 0, len(rows))
	for _, r := range rows {
		out = append(out, domain.SignInEvent{At: r.At, Method: r.Method, Outcome: r.Outcome,
			App: domain.ClientLabel(r.ClientID, "AfroTransact"), Device: domain.DescribeUserAgent(r.UserAgent), IP: optStr(r.IP)})
	}
	return out, nil
}

func (s *securityServiceImpl) RevokeSession(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.RevokeSession(ctx, c.BearerToken, id))
}

func (s *securityServiceImpl) RevokeOtherSessions(ctx context.Context, c *domain.Caller) (int, error) {
	if err := requireCustomer(c); err != nil {
		return 0, err
	}
	n, err := s.d.Security.RevokeOtherSessions(ctx, c.BearerToken)
	return n, wrapSec(err)
}

func (s *securityServiceImpl) RemoveDevice(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return wrapSec(s.d.Security.RemoveDevice(ctx, c.BearerToken, id))
}

func (s *securityServiceImpl) Unlink(ctx context.Context, c *domain.Caller, provider string) (domain.LinkedAccounts, error) {
	if err := requireCustomer(c); err != nil {
		return domain.LinkedAccounts{}, err
	}
	l, err := s.d.Security.Unlink(ctx, c.BearerToken, provider)
	if err != nil {
		return domain.LinkedAccounts{}, securityErr(err)
	}
	out := domain.LinkedAccounts{Accounts: []domain.LinkedAccount{}, HasPassword: l.HasPassword}
	for _, a := range l.Accounts {
		out.Accounts = append(out.Accounts, domain.LinkedAccount{Provider: a.Provider, Email: optStr(a.Email), CanUnlink: a.CanUnlink})
	}
	return out, nil
}

func (s *securityServiceImpl) Preferences(ctx context.Context, c *domain.Caller) (domain.NotificationPreferences, error) {
	if err := requireCustomer(c); err != nil {
		return domain.NotificationPreferences{}, err
	}
	p, err := s.d.Security.Preferences(ctx, c.BearerToken)
	return p, wrapSec(err)
}

func (s *securityServiceImpl) SavePreferences(ctx context.Context, c *domain.Caller, p domain.NotificationPreferences) (domain.NotificationPreferences, error) {
	if err := requireCustomer(c); err != nil {
		return domain.NotificationPreferences{}, err
	}
	out, err := s.d.Security.SavePreferences(ctx, c.BearerToken, p)
	return out, wrapSec(err)
}

func wrapSec(err error) error {
	if err == nil {
		return nil
	}
	return securityErr(err)
}

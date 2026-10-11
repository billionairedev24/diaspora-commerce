package app

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AccountSettingsDeps are the upstreams of the account settings pages.
type AccountSettingsDeps struct {
	Identity port.AccountSettingsClient
	// Alerts is notifications' alert topics (nil: the alerts routes answer 503).
	Alerts port.AlertPreferencesClient
}

// AccountSettingsService serves the settings pages of every app: the profile (with organisation memberships and
// platform roles, read-only), display preferences, back-office alert topics and leaving an organisation. Customers,
// Seller Center members and staff get the features of their kind (AccountProfile.kind).
type AccountSettingsService interface {
	Profile(ctx context.Context, c *domain.Caller) (domain.AccountProfile, error)
	UpdateProfile(ctx context.Context, c *domain.Caller, in domain.AccountProfileInput) (domain.AccountProfile, error)
	Preferences(ctx context.Context, c *domain.Caller) (domain.AccountPreferences, error)
	SavePreferences(ctx context.Context, c *domain.Caller, in domain.AccountPreferences) (domain.AccountPreferences, error)
	Alerts(ctx context.Context, c *domain.Caller) (domain.AlertPreferences, error)
	SaveAlerts(ctx context.Context, c *domain.Caller, in domain.AlertPreferencesInput) (domain.AlertPreferences, error)
	LeaveOrg(ctx context.Context, c *domain.Caller, orgID string) error
}

type accountSettingsImpl struct{ d AccountSettingsDeps }

var _ AccountSettingsService = (*accountSettingsImpl)(nil)

// NewAccountSettingsService returns the AccountSettingsService.
func NewAccountSettingsService(d AccountSettingsDeps) AccountSettingsService {
	return &accountSettingsImpl{d: d}
}

const (
	maxNameLength     = 80
	maxJobTitleLength = 80
	maxPhoneLength    = 32
)

func identityProblem(err error) error {
	if err == nil {
		return nil
	}
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case strings.Contains(p.Code, "owner-cannot-leave"):
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Owners can't leave",
				firstNonEmpty(p.Detail, "Make someone else an owner first.")).WithCause(err)
		case strings.Contains(p.Code, "member-not-found"):
			return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found",
				"You're not a member of that organisation.").WithCause(err)
		}
	}
	return mapIdentityErr(err)
}

func (s *accountSettingsImpl) profile(ctx context.Context, c *domain.Caller) (port.UpProfile, string, error) {
	if err := requireCustomer(c); err != nil {
		return port.UpProfile{}, "", err
	}
	p, err := s.d.Identity.Profile(ctx, c.BearerToken)
	if err != nil {
		return port.UpProfile{}, "", identityProblem(err)
	}
	return p, domain.AccountKind(p.PrincipalType, len(p.Orgs)), nil
}

func (s *accountSettingsImpl) Profile(ctx context.Context, c *domain.Caller) (domain.AccountProfile, error) {
	p, kind, err := s.profile(ctx, c)
	if err != nil {
		return domain.AccountProfile{}, err
	}
	var work port.UpWorkProfile
	if kind != domain.AccountCustomer {
		if work, err = s.d.Identity.WorkProfile(ctx, c.BearerToken); err != nil {
			return domain.AccountProfile{}, identityProblem(err)
		}
	}
	return toAccountProfile(p, kind, work), nil
}

func toAccountProfile(p port.UpProfile, kind string, work port.UpWorkProfile) domain.AccountProfile {
	out := domain.AccountProfile{ID: p.ID, Kind: kind, FirstName: p.FirstName, LastName: p.LastName, Email: p.Email,
		EmailVerified: p.EmailVerified, EmailManaged: kind == domain.AccountStaff && domain.IsStaffEmail(p.Email),
		Initials: domain.Profile{FirstName: p.FirstName, LastName: p.LastName}.Initials(), Phone: optStr(p.Phone),
		PhoneVerified: p.PhoneVerified, JobTitle: work.JobTitle, Memberships: []domain.AccountMembership{}, Roles: []domain.StaffRole{}}
	if kind == domain.AccountCustomer {
		out.JobTitle = nil
	}
	for _, o := range p.Orgs {
		k := strings.ToUpper(o.Kind)
		if k != domain.OrgKindSeller && k != domain.OrgKindProvider && k != "BOTH" {
			continue
		}
		out.Memberships = append(out.Memberships, domain.AccountMembership{OrgID: o.ID, Name: o.Name, Kind: k, Status: orgStatus(o.Status),
			RoleKey: o.Role, RoleLabel: domain.RoleLabel(o.Role), Owner: strings.HasSuffix(o.Role, ".owner"), Active: o.ID == p.OrgID})
	}
	if kind == domain.AccountStaff {
		for _, r := range p.Roles {
			if strings.HasPrefix(r, "platform.") {
				out.Roles = append(out.Roles, domain.StaffRole{Key: r, Label: domain.PlatformRoleLabel(r)})
			}
		}
	}
	return out
}

func trimmed(v *string) *string {
	if v == nil {
		return nil
	}
	t := strings.Join(strings.Fields(*v), " ")
	return &t
}

func (s *accountSettingsImpl) UpdateProfile(ctx context.Context, c *domain.Caller, in domain.AccountProfileInput) (domain.AccountProfile, error) {
	p, kind, err := s.profile(ctx, c)
	if err != nil {
		return domain.AccountProfile{}, err
	}
	in.FirstName, in.LastName, in.Phone, in.JobTitle = trimmed(in.FirstName), trimmed(in.LastName), trimmed(in.Phone), trimmed(in.JobTitle)
	errs := map[string]string{}
	for field, v := range map[string]*string{"firstName": in.FirstName, "lastName": in.LastName} {
		if v != nil && (*v == "" || utf8.RuneCountInString(*v) > maxNameLength) {
			errs[field] = "Use 1 to 80 characters"
		}
	}
	if in.Phone != nil && (*in.Phone == "" || len(*in.Phone) > maxPhoneLength) {
		errs["phone"] = "Enter a mobile number"
	}
	if in.JobTitle != nil {
		switch {
		case kind == domain.AccountCustomer:
			errs["jobTitle"] = "Job titles are for Seller Center and staff accounts"
		case utf8.RuneCountInString(*in.JobTitle) > maxJobTitleLength:
			errs["jobTitle"] = "Use at most 80 characters"
		}
	}
	if len(errs) > 0 {
		return domain.AccountProfile{}, domain.ValidationProblem(errs)
	}
	if in.Phone != nil && *in.Phone == p.Phone {
		in.Phone = nil // unchanged: keep it verified
	}
	if in.FirstName != nil || in.LastName != nil || in.Phone != nil {
		if p, err = s.d.Identity.UpdateProfile(ctx, c.BearerToken, port.UpProfileUpdate{FirstName: in.FirstName, LastName: in.LastName, Phone: in.Phone}); err != nil {
			return domain.AccountProfile{}, identityProblem(err)
		}
	}
	var work port.UpWorkProfile
	if kind != domain.AccountCustomer {
		if in.JobTitle != nil {
			work, err = s.d.Identity.SaveWorkProfile(ctx, c.BearerToken, port.UpWorkProfile{JobTitle: in.JobTitle})
		} else {
			work, err = s.d.Identity.WorkProfile(ctx, c.BearerToken)
		}
		if err != nil {
			return domain.AccountProfile{}, identityProblem(err)
		}
	}
	return toAccountProfile(p, kind, work), nil
}

func toAccountPreferences(p port.UpUserPreferences) domain.AccountPreferences {
	return domain.AccountPreferences{Locale: optStr(deref(p.Locale, "")), TimeZone: optStr(deref(p.TimeZone, "")),
		DateFormat: firstNonEmpty(p.DateFormat, "AUTO"), NumberFormat: firstNonEmpty(p.NumberFormat, "AUTO"),
		TableDensity: firstNonEmpty(p.TableDensity, "COMFORTABLE")}
}

func (s *accountSettingsImpl) Preferences(ctx context.Context, c *domain.Caller) (domain.AccountPreferences, error) {
	if err := requireCustomer(c); err != nil {
		return domain.AccountPreferences{}, err
	}
	p, err := s.d.Identity.Preferences(ctx, c.BearerToken)
	if err != nil {
		return domain.AccountPreferences{}, identityProblem(err)
	}
	return toAccountPreferences(p), nil
}

func (s *accountSettingsImpl) SavePreferences(ctx context.Context, c *domain.Caller, in domain.AccountPreferences) (domain.AccountPreferences, error) {
	if err := requireCustomer(c); err != nil {
		return domain.AccountPreferences{}, err
	}
	errs := map[string]string{}
	if !slices.Contains(domain.DateFormats, in.DateFormat) {
		errs["dateFormat"] = "Choose a date format"
	}
	if !slices.Contains(domain.NumberFormats, in.NumberFormat) {
		errs["numberFormat"] = "Choose a number format"
	}
	if !slices.Contains(domain.TableDensities, in.TableDensity) {
		errs["tableDensity"] = "Choose a table density"
	}
	if len(deref(in.Locale, "")) > 35 {
		errs["locale"] = "Choose a language"
	}
	if len(deref(in.TimeZone, "")) > 64 {
		errs["timeZone"] = "Choose a time zone"
	}
	if len(errs) > 0 {
		return domain.AccountPreferences{}, domain.ValidationProblem(errs)
	}
	out, err := s.d.Identity.SavePreferences(ctx, c.BearerToken, port.UpUserPreferences{Locale: in.Locale, TimeZone: in.TimeZone,
		DateFormat: in.DateFormat, NumberFormat: in.NumberFormat, TableDensity: in.TableDensity})
	if err != nil {
		return domain.AccountPreferences{}, identityProblem(err)
	}
	return toAccountPreferences(out), nil
}

// audience is the back office whose alert topics apply; customers have none.
func (s *accountSettingsImpl) audience(ctx context.Context, c *domain.Caller) (port.UpProfile, string, error) {
	if s.d.Alerts == nil {
		return port.UpProfile{}, "", domain.UpstreamUnavailable("notifications")
	}
	p, kind, err := s.profile(ctx, c)
	if err != nil {
		return port.UpProfile{}, "", err
	}
	switch kind {
	case domain.AccountStaff:
		return p, "staff", nil
	case domain.AccountSeller:
		return p, "seller", nil
	}
	return port.UpProfile{}, "", domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden",
		"Alerts are for Seller Center and staff accounts.")
}

func toAlertPreferences(up port.UpAlertPreferences, audience string) domain.AlertPreferences {
	out := domain.AlertPreferences{Audience: firstNonEmpty(up.Audience, audience), Preferences: []domain.AlertPreference{},
		QuietHours: domain.QuietHours{Start: "21:00", End: "07:00"}}
	for _, p := range up.Preferences {
		out.Preferences = append(out.Preferences, domain.AlertPreference(p))
	}
	if up.QuietHours != nil {
		out.QuietHours = domain.QuietHours(*up.QuietHours)
	}
	return out
}

func (s *accountSettingsImpl) Alerts(ctx context.Context, c *domain.Caller) (domain.AlertPreferences, error) {
	p, audience, err := s.audience(ctx, c)
	if err != nil {
		return domain.AlertPreferences{}, err
	}
	up, err := s.d.Alerts.Alerts(ctx, c.BearerToken, p.ID, audience)
	if err != nil {
		return domain.AlertPreferences{}, mapErr("notifications", err)
	}
	return toAlertPreferences(up, audience), nil
}

func (s *accountSettingsImpl) SaveAlerts(ctx context.Context, c *domain.Caller, in domain.AlertPreferencesInput) (domain.AlertPreferences, error) {
	p, audience, err := s.audience(ctx, c)
	if err != nil {
		return domain.AlertPreferences{}, err
	}
	body := port.UpAlertPreferences{Audience: audience, Preferences: make([]port.UpAlertPreference, 0, len(in.Preferences))}
	for _, pr := range in.Preferences {
		if !strings.HasPrefix(pr.Topic, audience+".") {
			return domain.AlertPreferences{}, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "preferences",
				"Unknown alert "+pr.Topic)
		}
		body.Preferences = append(body.Preferences, port.UpAlertPreference{Topic: pr.Topic, Channel: pr.Channel, Enabled: pr.Enabled})
	}
	if in.QuietHours != nil {
		q := port.UpQuietHours(*in.QuietHours)
		body.QuietHours = &q
	}
	up, err := s.d.Alerts.SaveAlerts(ctx, c.BearerToken, p.ID, body)
	if err != nil {
		return domain.AlertPreferences{}, mapErr("notifications", err)
	}
	return toAlertPreferences(up, audience), nil
}

func (s *accountSettingsImpl) LeaveOrg(ctx context.Context, c *domain.Caller, orgID string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	return identityProblem(s.d.Identity.LeaveOrg(ctx, c.BearerToken, orgID))
}

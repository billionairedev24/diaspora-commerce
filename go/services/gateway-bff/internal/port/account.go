package port

import (
	"context"
)

// ---------------------------------------------------------------- identity account settings (contracts/openapi/identity.yaml)

// UpProfile is identity's GET /v1/me with what the account settings pages show.
type UpProfile struct {
	ID            string      `json:"id"`
	FirstName     string      `json:"firstName"`
	LastName      string      `json:"lastName"`
	Email         string      `json:"email"`
	EmailVerified bool        `json:"emailVerified"`
	Phone         string      `json:"phone"`
	PhoneVerified bool        `json:"phoneVerified"`
	PrincipalType string      `json:"principalType"`
	OrgID         string      `json:"orgId"`
	Roles         []string    `json:"roles"`
	Orgs          []MemberOrg `json:"orgs"`
}

// UpProfileUpdate is identity's UpdateProfileRequest (nil fields are left unchanged).
type UpProfileUpdate struct {
	FirstName *string `json:"firstName,omitempty"`
	LastName  *string `json:"lastName,omitempty"`
	Phone     *string `json:"phone,omitempty"`
}

// UpWorkProfile is identity's WorkProfile.
type UpWorkProfile struct {
	JobTitle *string `json:"jobTitle"`
}

// UpUserPreferences is identity's UserPreferences / UserPreferencesRequest.
type UpUserPreferences struct {
	Locale       *string `json:"locale"`
	TimeZone     *string `json:"timeZone"`
	DateFormat   string  `json:"dateFormat"`
	NumberFormat string  `json:"numberFormat"`
	TableDensity string  `json:"tableDensity"`
}

// AccountSettingsClient reads and changes the signed-in person's profile, job title, display preferences and
// organisation memberships at identity, with the person's own token.
type AccountSettingsClient interface {
	Profile(ctx context.Context, bearer string) (UpProfile, error)
	UpdateProfile(ctx context.Context, bearer string, in UpProfileUpdate) (UpProfile, error)
	WorkProfile(ctx context.Context, bearer string) (UpWorkProfile, error)
	SaveWorkProfile(ctx context.Context, bearer string, in UpWorkProfile) (UpWorkProfile, error)
	Preferences(ctx context.Context, bearer string) (UpUserPreferences, error)
	SavePreferences(ctx context.Context, bearer string, in UpUserPreferences) (UpUserPreferences, error)
	LeaveOrg(ctx context.Context, bearer, orgID string) error
}

// ---------------------------------------------------------------- notifications alert topics (contracts/openapi/notifications.yaml)

// UpAlertPreference is notifications' AlertPreference.
type UpAlertPreference struct {
	Topic   string `json:"topic"`
	Channel string `json:"channel"`
	Enabled bool   `json:"enabled"`
	Locked  bool   `json:"locked,omitempty"`
}

// UpQuietHours is notifications' QuietHours.
type UpQuietHours struct {
	Enabled bool   `json:"enabled"`
	Start   string `json:"start"`
	End     string `json:"end"`
}

// UpAlertPreferences is notifications' AlertPreferences (also the PUT body, with Audience set).
type UpAlertPreferences struct {
	Audience    string              `json:"audience"`
	Preferences []UpAlertPreference `json:"preferences"`
	QuietHours  *UpQuietHours       `json:"quietHours,omitempty"`
}

// AlertPreferencesClient reads and changes a back-office member's alert topics with the member's own token
// (notifications only lets recipients see their own).
type AlertPreferencesClient interface {
	Alerts(ctx context.Context, bearer, userID, audience string) (UpAlertPreferences, error)
	SaveAlerts(ctx context.Context, bearer, userID string, in UpAlertPreferences) (UpAlertPreferences, error)
}

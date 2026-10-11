package upstreams

import (
	"context"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- identity account settings

type accountSettingsImpl struct{ c httpclient.Client }

var _ port.AccountSettingsClient = (*accountSettingsImpl)(nil)

// NewAccountSettings returns the identity client for the profile, work profile, display preferences and leaving an
// organisation (/v1/me…, the person's own token).
func NewAccountSettings(c httpclient.Client) port.AccountSettingsClient {
	return &accountSettingsImpl{c: c}
}

func (a *accountSettingsImpl) do(ctx context.Context, bearer, method, path string, body, out any) error {
	_, err := call(ctx, a.c, httpclient.Request{Method: method, Path: path, Bearer: bearer, JSON: body}, out)
	return err
}

func (a *accountSettingsImpl) Profile(ctx context.Context, bearer string) (port.UpProfile, error) {
	var out port.UpProfile
	return out, a.do(ctx, bearer, http.MethodGet, "/v1/me", nil, &out)
}

func (a *accountSettingsImpl) UpdateProfile(ctx context.Context, bearer string, in port.UpProfileUpdate) (port.UpProfile, error) {
	var out port.UpProfile
	return out, a.do(ctx, bearer, http.MethodPatch, "/v1/me", in, &out)
}

func (a *accountSettingsImpl) WorkProfile(ctx context.Context, bearer string) (port.UpWorkProfile, error) {
	var out port.UpWorkProfile
	return out, a.do(ctx, bearer, http.MethodGet, "/v1/me/work-profile", nil, &out)
}

func (a *accountSettingsImpl) SaveWorkProfile(ctx context.Context, bearer string, in port.UpWorkProfile) (port.UpWorkProfile, error) {
	var out port.UpWorkProfile
	return out, a.do(ctx, bearer, http.MethodPut, "/v1/me/work-profile", in, &out)
}

func (a *accountSettingsImpl) Preferences(ctx context.Context, bearer string) (port.UpUserPreferences, error) {
	var out port.UpUserPreferences
	return out, a.do(ctx, bearer, http.MethodGet, "/v1/me/preferences", nil, &out)
}

func (a *accountSettingsImpl) SavePreferences(ctx context.Context, bearer string, in port.UpUserPreferences) (port.UpUserPreferences, error) {
	var out port.UpUserPreferences
	return out, a.do(ctx, bearer, http.MethodPut, "/v1/me/preferences", in, &out)
}

func (a *accountSettingsImpl) LeaveOrg(ctx context.Context, bearer, orgID string) error {
	return a.do(ctx, bearer, http.MethodPost, "/v1/me/orgs/"+esc(orgID)+"/leave", nil, nil)
}

// ---------------------------------------------------------------- notifications alert topics

type alertPreferencesImpl struct{ c httpclient.Client }

var _ port.AlertPreferencesClient = (*alertPreferencesImpl)(nil)

// NewAlertPreferences returns the notifications client for a member's alert topics and quiet hours.
func NewAlertPreferences(c httpclient.Client) port.AlertPreferencesClient {
	return &alertPreferencesImpl{c: c}
}

func (n *alertPreferencesImpl) Alerts(ctx context.Context, bearer, userID, audience string) (port.UpAlertPreferences, error) {
	var out port.UpAlertPreferences
	_, err := call(ctx, n.c, httpclient.Request{Path: "/v1/preferences/" + esc(userID) + "/alerts", Bearer: bearer,
		Query: url.Values{"audience": {audience}}}, &out)
	return out, err
}

func (n *alertPreferencesImpl) SaveAlerts(ctx context.Context, bearer, userID string, in port.UpAlertPreferences) (port.UpAlertPreferences, error) {
	var out port.UpAlertPreferences
	_, err := call(ctx, n.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/preferences/" + esc(userID) + "/alerts",
		Bearer: bearer, JSON: in}, &out)
	return out, err
}

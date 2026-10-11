package httpapi

import (
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// registerAccountSettings mounts the settings routes shared by the storefront, Seller Center and staff console:
// profile, display preferences, back-office alert topics and leaving an organisation.
func (h *handlersImpl) registerAccountSettings(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/account/profile", h.accountProfile)
	route("PATCH /v1/account/profile", h.updateAccountProfile)
	route("GET /v1/account/preferences", h.accountPreferences)
	route("PUT /v1/account/preferences", h.saveAccountPreferences)
	route("GET /v1/account/alerts", h.alertPreferences)
	route("PUT /v1/account/alerts", h.saveAlertPreferences)
	route("POST /v1/account/orgs/{orgId}/leave", h.leaveOrganisation)
}

func (h *handlersImpl) accountProfile(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Settings.Profile(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) updateAccountProfile(w http.ResponseWriter, r *http.Request) {
	var b domain.AccountProfileInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Settings.UpdateProfile(r.Context(), callerFrom(r.Context()), b)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) accountPreferences(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Settings.Preferences(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) saveAccountPreferences(w http.ResponseWriter, r *http.Request) {
	var b domain.AccountPreferences
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Settings.SavePreferences(r.Context(), callerFrom(r.Context()), b)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) alertPreferences(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Settings.Alerts(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) saveAlertPreferences(w http.ResponseWriter, r *http.Request) {
	var b domain.AlertPreferencesInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Settings.SaveAlerts(r.Context(), callerFrom(r.Context()), b)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) leaveOrganisation(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Settings.LeaveOrg(r.Context(), callerFrom(r.Context()), r.PathValue("orgId")))
}

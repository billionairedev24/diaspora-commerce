package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

// registerStaffInvitations mounts the staff invitation routes: three for Settings > Staff accounts (signed-in staff) and the
// invitee's two pages, which need no session and are rate limited like sign-in (the token is in the body, never the URL).
func (h *handlersImpl) registerStaffInvitations(route func(pattern string, fn http.HandlerFunc), limited func(http.HandlerFunc) http.HandlerFunc) {
	route("GET /v1/staff/access/invitations", h.staffInvitationsOpen)
	route("POST /v1/staff/access/invitations", h.staffInvite)
	route("POST /v1/staff/access/invitations/{id}/resend", h.staffInvitationResend)
	route("POST /v1/staff/access/invitations/{id}/revoke", h.staffInvitationRevoke)
	route("POST /v1/staff/invitations/preview", limited(h.staffInvitationPreview))
	route("POST /v1/staff/invitations/accept", limited(h.staffInvitationAccept))
}

func (h *handlersImpl) staffInvitationsOpen(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	v, err := h.s.Staff.Invitations.Open(r.Context(), callerFrom(r.Context()), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffInvite(w http.ResponseWriter, r *http.Request) {
	var b struct {
		FirstName string `json:"firstName"`
		LastName  string `json:"lastName"`
		Email     string `json:"email"`
		RoleKey   string `json:"roleKey"`
		Reason    string `json:"reason"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Invitations.Invite(r.Context(), callerFrom(r.Context()), staffInviteOf(b.FirstName, b.LastName, b.Email, b.RoleKey, b.Reason))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffInvitationResend(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Invitations.Resend(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffInvitationRevoke(w http.ResponseWriter, r *http.Request) {
	reason, err := accessReason(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	err = h.s.Staff.Invitations.Revoke(r.Context(), callerFrom(r.Context()), r.PathValue("id"), reason)
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) staffInvitationPreview(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Token string `json:"token"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Invitations.Preview(r.Context(), b.Token)
	w.Header().Set("Cache-Control", "no-store")
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffInvitationAccept(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Token           string `json:"token"`
		Password        string `json:"password"`
		ConfirmPassword string `json:"confirmPassword"`
		Phone           string `json:"phone"`
		PhoneRegion     string `json:"phoneRegion"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Invitations.Accept(r.Context(), staffAcceptOf(b.Token, b.Password, b.ConfirmPassword, b.Phone, b.PhoneRegion))
	w.Header().Set("Cache-Control", "no-store")
	h.reply(w, r, http.StatusCreated, v, err)
}

func staffInviteOf(first, last, email, role, reason string) app.StaffInvite {
	return app.StaffInvite{FirstName: first, LastName: last, Email: email, RoleKey: role, Reason: reason}
}

func staffAcceptOf(token, password, confirm, phone, region string) app.StaffInviteAccept {
	return app.StaffInviteAccept{Token: token, Password: password, ConfirmPassword: confirm, Phone: phone, PhoneRegion: region}
}

func (h *handlersImpl) staffAISpendToday(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.AISpend.Today(r.Context(), callerFrom(r.Context()))
	w.Header().Set("Cache-Control", "no-store")
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAISpendCap(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.AISpend.Cap(r.Context(), callerFrom(r.Context()))
	w.Header().Set("Cache-Control", "no-store")
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSetAISpendCap(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.AISpend.SetCap(r.Context(), callerFrom(r.Context()), r.Header.Get("If-Match"), b)
	w.Header().Set("Cache-Control", "no-store")
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPictureRules(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.PictureRules.Get(r.Context(), callerFrom(r.Context()))
	w.Header().Set("Cache-Control", "no-store")
	setVersionETag(w, versionOfRules(v))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSetPictureRules(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.PictureRules.Set(r.Context(), callerFrom(r.Context()), r.Header.Get("If-Match"), b)
	w.Header().Set("Cache-Control", "no-store")
	setVersionETag(w, versionOfRules(v))
	h.reply(w, r, http.StatusOK, v, err)
}

// versionOfRules lifts the stored version of the rules document to the top, where setVersionETag reads it.
func versionOfRules(v map[string]any) map[string]any {
	rules, _ := v["rules"].(map[string]any)
	if rules == nil {
		return v
	}
	return map[string]any{"version": rules["version"]}
}

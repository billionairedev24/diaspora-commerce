package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

func (h *handlersImpl) registerStaffAccess(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/access/permissions", h.staffAccessPermissions)
	route("GET /v1/staff/access/roles", h.staffAccessRoles)
	route("GET /v1/staff/access/roles/{role}/members", h.staffAccessRoleMembers)
	route("PUT /v1/staff/access/roles/{role}/permissions/{permission}", h.staffAccessGrantRole)
	route("DELETE /v1/staff/access/roles/{role}/permissions/{permission}", h.staffAccessRevokeRole)
	route("GET /v1/staff/access/people", h.staffAccessPeople)
	route("GET /v1/staff/access/people/{id}", h.staffAccessPerson)
	route("PUT /v1/staff/access/people/{id}/permissions/{permission}", h.staffAccessGrantPerson)
	route("DELETE /v1/staff/access/people/{id}/permissions/{permission}", h.staffAccessRevokePerson)
	route("GET /v1/staff/access/log", h.staffAccessLog)
}

func (h *handlersImpl) staffAccessPermissions(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Access.Permissions(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAccessRoles(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Access.Roles(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("portal"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAccessRoleMembers(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	v, err := h.s.Staff.Access.RoleMembers(r.Context(), callerFrom(r.Context()), r.PathValue("role"), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

// accessReason reads the change's reason from the body ({"reason": "..."}).
func accessReason(r *http.Request) (string, error) {
	var b struct {
		Reason string `json:"reason"`
	}
	if err := decode(r, &b, true); err != nil {
		return "", err
	}
	return b.Reason, nil
}

func (h *handlersImpl) staffAccessRoleChange(w http.ResponseWriter, r *http.Request, grant bool) {
	reason, err := accessReason(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Access.ChangeRolePermission(r.Context(), callerFrom(r.Context()), r.PathValue("role"), r.PathValue("permission"), grant, reason)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAccessGrantRole(w http.ResponseWriter, r *http.Request) {
	h.staffAccessRoleChange(w, r, true)
}
func (h *handlersImpl) staffAccessRevokeRole(w http.ResponseWriter, r *http.Request) {
	h.staffAccessRoleChange(w, r, false)
}

func (h *handlersImpl) staffAccessPeople(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	v, err := h.s.Staff.Access.People(r.Context(), callerFrom(r.Context()), q.Get("q"), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAccessPerson(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Access.Person(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAccessPersonChange(w http.ResponseWriter, r *http.Request, grant bool) {
	reason, err := accessReason(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Access.ChangePersonPermission(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.PathValue("permission"), grant, reason)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAccessGrantPerson(w http.ResponseWriter, r *http.Request) {
	h.staffAccessPersonChange(w, r, true)
}
func (h *handlersImpl) staffAccessRevokePerson(w http.ResponseWriter, r *http.Request) {
	h.staffAccessPersonChange(w, r, false)
}

func (h *handlersImpl) staffAccessLog(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	v, err := h.s.Staff.Access.Log(r.Context(), callerFrom(r.Context()), app.StaffAccessLogFilter{UserID: q.Get("userId"), Role: q.Get("role"), Permission: q.Get("permission"), Page: page, Size: size})
	h.reply(w, r, http.StatusOK, v, err)
}

package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Department requests (gateway-bff-staff.yaml, tag Department requests): sellers' requests to sell in another catalog
// department, the decision on each, and an organisation's departments.

func (h *handlersImpl) registerStaffDepartments(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/department-requests", h.staffDepartmentRequests)
	route("GET /v1/staff/department-requests/{id}", h.staffDepartmentRequest)
	route("POST /v1/staff/department-requests/{id}/approve", h.staffApproveDepartmentRequest)
	route("POST /v1/staff/department-requests/{id}/reject", h.staffRejectDepartmentRequest)
	route("GET /v1/staff/organisations/{orgId}/departments", h.staffOrgDepartments)
	route("PUT /v1/staff/organisations/{orgId}/departments", h.staffSetOrgDepartments)
}

func (h *handlersImpl) staffDepartmentRequests(w http.ResponseWriter, r *http.Request) {
	v := r.URL.Query()
	q := app.StaffDepartmentQuery{Status: v.Get("status"), Department: v.Get("department"), Organisation: v.Get("organisation"),
		From: v.Get("from"), To: v.Get("to"), Sort: v["sort"]}
	for key, dst := range map[string]*int{"page": &q.Page, "size": &q.Size} {
		if raw := v.Get(key); raw != "" {
			n, err := strconv.Atoi(raw)
			if err != nil {
				h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{key: "Not a number"}))
				return
			}
			*dst = n
		}
	}
	out, err := h.s.Staff.Departments.List(r.Context(), callerFrom(r.Context()), q)
	h.reply(w, r, http.StatusOK, out, err)
}

func (h *handlersImpl) staffDepartmentRequest(w http.ResponseWriter, r *http.Request) {
	out, err := h.s.Staff.Departments.Get(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, out, err)
}

func (h *handlersImpl) staffApproveDepartmentRequest(w http.ResponseWriter, r *http.Request) {
	// The note is optional: an empty body approves without one.
	b := map[string]any{}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	note, _ := b["note"].(string)
	out, err := h.s.Staff.Departments.Approve(r.Context(), callerFrom(r.Context()), r.PathValue("id"), note)
	h.reply(w, r, http.StatusOK, out, err)
}

func (h *handlersImpl) staffRejectDepartmentRequest(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	reason, _ := b["reason"].(string)
	out, err := h.s.Staff.Departments.Reject(r.Context(), callerFrom(r.Context()), r.PathValue("id"), reason)
	h.reply(w, r, http.StatusOK, out, err)
}

func (h *handlersImpl) staffOrgDepartments(w http.ResponseWriter, r *http.Request) {
	out, err := h.s.Staff.Departments.OrgDepartments(r.Context(), callerFrom(r.Context()), r.PathValue("orgId"))
	h.reply(w, r, http.StatusOK, out, err)
}

func (h *handlersImpl) staffSetOrgDepartments(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	reason, _ := b["reason"].(string)
	list, ok := b["departments"].([]any)
	if !ok {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"departments": "Choose departments from the list."}))
		return
	}
	codes := make([]string, 0, len(list))
	for _, it := range list {
		if s, ok := it.(string); ok {
			codes = append(codes, s)
		}
	}
	out, err := h.s.Staff.Departments.SetOrgDepartments(r.Context(), callerFrom(r.Context()), r.PathValue("orgId"), codes, reason)
	h.reply(w, r, http.StatusOK, out, err)
}

package testkit

import (
	"encoding/json"
	"net/http"
)

// Department scope fixtures: the fake sellerservice records Groceries and Fashion for the test seller; catalog says
// whether a department sells sized goods. A request for a department that asks for evidence names what is missing.
func (u *Upstreams) scopeRoutes(mux *http.ServeMux) {
	pending := []map[string]any{}
	mux.HandleFunc("GET /v1/seller/catalog-scope", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, map[string]any{"exempt": false, "hasSizing": true,
			"departments": []map[string]any{
				{"id": "d0000000-0000-4000-8000-000000000001", "code": "groceries", "name": "Groceries", "hasSizing": false},
				{"id": "d0000000-0000-4000-8000-000000000002", "code": "fashion", "name": "Fashion", "hasSizing": true},
			},
			"requests": pending})
	})
	mux.HandleFunc("GET /v1/departments", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{
			{"code": "groceries", "name": "Groceries", "requires": []string{"FOOD_PERMIT"}},
			{"code": "beauty", "name": "Beauty", "requires": []string{"BEAUTY_LABELLING"}},
			{"code": "fashion", "name": "Fashion", "requires": []string{}},
		})
	})
	mux.HandleFunc("POST /v1/orgs/{orgId}/department-requests", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			DepartmentCode string `json:"departmentCode"`
			Note           string `json:"note"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		switch in.DepartmentCode {
		case "groceries", "fashion":
			problem(w, 409, "seller.department-already-allowed", "Your store can already sell in this department", nil)
			return
		case "hardware":
			problem(w, 422, "seller.invalid-department", "Choose a department from the list", nil)
			return
		}
		needs := []string{}
		if in.DepartmentCode == "beauty" {
			needs = append(needs, "BEAUTY_LABELLING")
		}
		req := map[string]any{"id": "e0000000-0000-4000-8000-000000000001", "orgId": r.PathValue("orgId"),
			"departmentCode": in.DepartmentCode, "departmentName": "Beauty", "note": in.Note, "status": "PENDING",
			"needs": needs, "createdAt": "2026-11-03T10:00:00Z"}
		u.mu.Lock()
		pending = []map[string]any{{"departmentCode": in.DepartmentCode, "departmentName": "Beauty", "status": "PENDING",
			"needs": needs, "createdAt": "2026-11-03T10:00:00Z"}}
		u.mu.Unlock()
		writeJSON(w, 201, req)
	})
}

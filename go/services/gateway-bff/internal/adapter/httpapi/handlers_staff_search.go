package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Search analytics (gateway-bff-staff.yaml, tag Search analytics): reports and synonym rules of the search service.

func (h *handlersImpl) registerStaffSearch(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/search/markets", h.staffSearchMarkets)
	route("GET /v1/staff/search/analytics/top-queries", h.staffSearchReport("top"))
	route("GET /v1/staff/search/analytics/zero-results", h.staffSearchReport("zero"))
	route("GET /v1/staff/search/analytics/ctr", h.staffSearchCTR)
	route("GET /v1/staff/search/synonyms", h.staffSearchSynonyms)
	route("POST /v1/staff/search/synonyms", h.staffSearchAddSynonym)
	route("DELETE /v1/staff/search/synonyms/{ruleId}", h.staffSearchDeleteSynonym)
}

func (h *handlersImpl) staffSearchMarkets(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Search.Markets(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

// searchQuery reads the report parameters (page is 1-based; size defaults to 25).
func searchQuery(r *http.Request) (app.StaffSearchQuery, error) {
	v := r.URL.Query()
	q := app.StaffSearchQuery{Market: v.Get("market"), From: v.Get("from"), To: v.Get("to"), Q: v.Get("q"), Sort: v["sort"]}
	for key, dst := range map[string]*int{"page": &q.Page, "size": &q.Size} {
		if raw := v.Get(key); raw != "" {
			n, err := strconv.Atoi(raw)
			if err != nil {
				return q, domain.ValidationProblem(map[string]string{key: "Not a number"})
			}
			*dst = n
		}
	}
	return q, nil
}

func (h *handlersImpl) staffSearchReport(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		q, err := searchQuery(r)
		if err != nil {
			h.reply(w, r, 0, nil, err)
			return
		}
		var rep domain.StaffQueryReport
		if kind == "zero" {
			rep, err = h.s.Staff.Search.ZeroResults(r.Context(), callerFrom(r.Context()), q)
		} else {
			rep, err = h.s.Staff.Search.TopQueries(r.Context(), callerFrom(r.Context()), q)
		}
		h.reply(w, r, http.StatusOK, rep, err)
	}
}

func (h *handlersImpl) staffSearchCTR(w http.ResponseWriter, r *http.Request) {
	q, err := searchQuery(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Search.ClickThrough(r.Context(), callerFrom(r.Context()), q)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSearchSynonyms(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Search.Synonyms(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSearchAddSynonym(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Terms []string `json:"terms"`
	}
	if err := decode(r, &body, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Search.AddSynonym(r.Context(), callerFrom(r.Context()), body.Terms)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffSearchDeleteSynonym(w http.ResponseWriter, r *http.Request) {
	err := h.s.Staff.Search.DeleteSynonym(r.Context(), callerFrom(r.Context()), r.PathValue("ruleId"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

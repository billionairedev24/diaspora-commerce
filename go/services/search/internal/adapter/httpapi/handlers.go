// Package httpapi is the inbound HTTP adapter for search (contract: contracts/openapi/search.yaml).
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// Permissions checked by the staff endpoints (granted by identity roles; the staff console holds them).
const (
	PermAnalyticsView = "search:analytics.view"
	PermSynonymsEdit  = "search:synonyms.edit"
)

// Handlers exposes the search use cases over HTTP.
type Handlers interface {
	// Register mounts the routes; wrap authenticates (optional bearer token).
	Register(mux *http.ServeMux, wrap func(http.Handler) http.Handler)
}

// Deps are the use cases behind the routes. Clicks, Analytics and Synonyms are optional (routes answer 503 without).
type Deps struct {
	Searcher  app.ProductSearcher
	Suggester app.Suggester
	Clicks    app.ClickRecorder
	Analytics app.AnalyticsReporter
	Synonyms  app.SynonymCurator
}

type handlersImpl struct {
	d Deps
}

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(d Deps) Handlers { return &handlersImpl{d: d} }

func (h *handlersImpl) Register(mux *http.ServeMux, wrap func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/search", wrap(http.HandlerFunc(h.search)))
	mux.Handle("GET /v1/suggest", wrap(http.HandlerFunc(h.suggest)))
	mux.Handle("POST /v1/search/clicks", wrap(http.HandlerFunc(h.click)))
	mux.Handle("GET /v1/search/analytics/top-queries", wrap(auth.Require(PermAnalyticsView, h.topQueries)))
	mux.Handle("GET /v1/search/analytics/zero-results", wrap(auth.Require(PermAnalyticsView, h.zeroResults)))
	mux.Handle("GET /v1/search/analytics/ctr", wrap(auth.Require(PermAnalyticsView, h.ctr)))
	mux.Handle("GET /v1/search/synonyms", wrap(requireAny(h.listSynonyms, PermAnalyticsView, PermSynonymsEdit)))
	mux.Handle("PUT /v1/search/synonyms/{ruleId}", wrap(auth.Require(PermSynonymsEdit, h.putSynonym)))
	mux.Handle("DELETE /v1/search/synonyms/{ruleId}", wrap(auth.Require(PermSynonymsEdit, h.deleteSynonym)))
}

func (h *handlersImpl) search(w http.ResponseWriter, r *http.Request) {
	v := r.URL.Query()
	q := domain.Query{
		Market:      v.Get("market"),
		Text:        v.Get("q"),
		Department:  v.Get("department"),
		Fulfilment:  strings.ToUpper(v.Get("fulfilment")),
		SellerKind:  strings.ToUpper(v.Get("sellerKind")),
		SellerID:    strings.TrimSpace(v.Get("sellerId")),
		InStockOnly: v.Get("inStock") == "true",
		Sort:        domain.Sort(v.Get("sort")),
		Ads:         v.Get("ads") == "true",
		Track:       v.Get("track") != "false",
		CustomerID:  customerID(r),
	}
	if c := v.Get("community"); c != "" {
		q.Communities = strings.Split(c, ",")
	}
	var err error
	parse := func(name string, dst any) {
		raw := v.Get(name)
		if raw == "" || err != nil {
			return
		}
		switch d := dst.(type) {
		case *int:
			*d, err = strconv.Atoi(raw)
		case *int64:
			*d, err = strconv.ParseInt(raw, 10, 64)
		case *float64:
			*d, err = strconv.ParseFloat(raw, 64)
		}
		if err != nil {
			err = problem.New(http.StatusBadRequest, "search.invalid-parameter", name+" is not a number")
		}
	}
	parse("page", &q.Page)
	parse("size", &q.Size)
	parse("minRating", &q.MinRating)
	parse("minPrice", &q.MinPrice)
	parse("maxPrice", &q.MaxPrice)
	parse("maxDays", &q.MaxDays)
	parse("lat", &q.NearLat)
	parse("lon", &q.NearLon)
	parse("withinMiles", &q.WithinMiles)
	if err != nil {
		problem.Write(w, r, err)
		return
	}
	page, err := h.d.Searcher.Search(r.Context(), q)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	if q.Ads {
		// Ads carry per-search click tokens: never share a page between shoppers.
		w.Header().Set("Cache-Control", "private, no-store")
	}
	writeJSON(w, http.StatusOK, page)
}

func (h *handlersImpl) suggest(w http.ResponseWriter, r *http.Request) {
	out, err := h.d.Suggester.Suggest(r.Context(), r.URL.Query().Get("market"), r.URL.Query().Get("q"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=30")
	writeJSON(w, http.StatusOK, out)
}

type clickRequest struct {
	SearchID   string `json:"searchId"`
	Market     string `json:"market"`
	Query      string `json:"q"`
	ATIN       string `json:"atin"`
	Position   int    `json:"position"`
	AdToken    string `json:"adToken"`
	CustomerID string `json:"customerId"`
}

func (h *handlersImpl) click(w http.ResponseWriter, r *http.Request) {
	if h.d.Clicks == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "search.clicks-disabled", "Click tracking is off"))
		return
	}
	var body clickRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 16<<10)).Decode(&body); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-click", "Body must be a click JSON object"))
		return
	}
	c := domain.Click{SearchID: body.SearchID, Market: body.Market, Query: body.Query, ATIN: body.ATIN,
		Position: body.Position, AdToken: body.AdToken, CustomerID: customerID(r)}
	// Only a service (the BFF with its client-credentials token) may report clicks on behalf of a shopper.
	if a, ok := auth.FromContext(r.Context()); ok && a.Type == "SERVICE" && body.CustomerID != "" {
		c.CustomerID = body.CustomerID
	}
	res, err := h.d.Clicks.Record(r.Context(), c)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusAccepted, res)
}

func (h *handlersImpl) topQueries(w http.ResponseWriter, r *http.Request) {
	h.queryReport(w, r, func(ctx context.Context, rng domain.AnalyticsRange) (domain.QueryReport, error) {
		return h.d.Analytics.TopQueries(ctx, rng)
	})
}

func (h *handlersImpl) zeroResults(w http.ResponseWriter, r *http.Request) {
	h.queryReport(w, r, func(ctx context.Context, rng domain.AnalyticsRange) (domain.QueryReport, error) {
		return h.d.Analytics.ZeroResultQueries(ctx, rng)
	})
}

func (h *handlersImpl) queryReport(w http.ResponseWriter, r *http.Request,
	run func(ctx context.Context, rng domain.AnalyticsRange) (domain.QueryReport, error),
) {
	if h.d.Analytics == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "search.analytics-disabled", "Analytics are off"))
		return
	}
	rng, err := parseRange(r)
	if err != nil {
		problem.Write(w, r, err)
		return
	}
	out, err := run(r.Context(), rng)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=60")
	writeJSON(w, http.StatusOK, out)
}

func (h *handlersImpl) ctr(w http.ResponseWriter, r *http.Request) {
	if h.d.Analytics == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "search.analytics-disabled", "Analytics are off"))
		return
	}
	rng, err := parseRange(r)
	if err != nil {
		problem.Write(w, r, err)
		return
	}
	out, err := h.d.Analytics.ClickThrough(r.Context(), rng)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=60")
	writeJSON(w, http.StatusOK, out)
}

func (h *handlersImpl) listSynonyms(w http.ResponseWriter, r *http.Request) {
	if h.d.Synonyms == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "search.synonyms-disabled", "Synonym curation is off"))
		return
	}
	rules, err := h.d.Synonyms.List(r.Context())
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"rules": rules})
}

func (h *handlersImpl) putSynonym(w http.ResponseWriter, r *http.Request) {
	if h.d.Synonyms == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "search.synonyms-disabled", "Synonym curation is off"))
		return
	}
	var body struct {
		Synonyms string `json:"synonyms"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&body); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-synonym", "Body must be {\"synonyms\": \"a, b\"}"))
		return
	}
	rule, err := h.d.Synonyms.Put(r.Context(), domain.SynonymRule{ID: r.PathValue("ruleId"), Synonyms: body.Synonyms})
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, rule)
}

func (h *handlersImpl) deleteSynonym(w http.ResponseWriter, r *http.Request) {
	if h.d.Synonyms == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "search.synonyms-disabled", "Synonym curation is off"))
		return
	}
	if err := h.d.Synonyms.Delete(r.Context(), r.PathValue("ruleId")); err != nil {
		writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func parseRange(r *http.Request) (domain.AnalyticsRange, error) {
	v := r.URL.Query()
	rng := domain.AnalyticsRange{Market: v.Get("market")}
	for name, dst := range map[string]*time.Time{"from": &rng.From, "to": &rng.To} {
		raw := v.Get(name)
		if raw == "" {
			continue
		}
		t, err := time.Parse(time.DateOnly, raw)
		if err != nil {
			return rng, problem.New(http.StatusBadRequest, "search.invalid-parameter", name+" must be YYYY-MM-DD")
		}
		*dst = t
	}
	if raw := v.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil {
			return rng, problem.New(http.StatusBadRequest, "search.invalid-parameter", "limit is not a number")
		}
		rng.Limit = n
	}
	return rng, nil
}

// customerID is the signed-in shopper's subject (customers only), used for ad click attribution.
func customerID(r *http.Request) string {
	if a, ok := auth.FromContext(r.Context()); ok && a.Type == "CUSTOMER" {
		return a.Subject
	}
	return ""
}

func requireAny(next http.HandlerFunc, perms ...string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		a, ok := auth.FromContext(r.Context())
		if !ok {
			problem.Write(w, r, problem.New(http.StatusUnauthorized, "auth.unauthenticated", "Bearer token required"))
			return
		}
		for _, p := range perms {
			if a.Can(p) {
				next(w, r)
				return
			}
		}
		problem.Write(w, r, problem.New(http.StatusForbidden, "auth.missing-permission", "Missing permission "+perms[0]))
	}
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalidQuery):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-query", err.Error()))
	case errors.Is(err, domain.ErrUnknownMarket):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.unknown-market", err.Error()))
	case errors.Is(err, domain.ErrInvalidClick):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-click", err.Error()))
	case errors.Is(err, app.ErrInvalidAdToken):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-ad-token",
			"The ad token is invalid or does not belong to this result"))
	case errors.Is(err, domain.ErrInvalidRange):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-range", err.Error()))
	case errors.Is(err, domain.ErrInvalidSynonym):
		problem.Write(w, r, problem.New(http.StatusUnprocessableEntity, "search.invalid-synonym", err.Error()))
	case errors.Is(err, app.ErrSynonymNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "search.synonym-not-found", "No such synonym rule"))
	default:
		problem.Write(w, r, problem.New(http.StatusBadGateway, "search.unavailable", "Search is temporarily unavailable"))
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

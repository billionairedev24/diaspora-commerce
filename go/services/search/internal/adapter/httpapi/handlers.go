// Package httpapi is the inbound HTTP adapter for search (contract: contracts/openapi/search.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// Handlers exposes the search use cases over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, wrap func(http.Handler) http.Handler)
}

type handlersImpl struct {
	searcher  app.ProductSearcher
	suggester app.Suggester
}

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(searcher app.ProductSearcher, suggester app.Suggester) Handlers {
	return &handlersImpl{searcher: searcher, suggester: suggester}
}

func (h *handlersImpl) Register(mux *http.ServeMux, wrap func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/search", wrap(http.HandlerFunc(h.search)))
	mux.Handle("GET /v1/suggest", wrap(http.HandlerFunc(h.suggest)))
}

func (h *handlersImpl) search(w http.ResponseWriter, r *http.Request) {
	v := r.URL.Query()
	q := domain.Query{
		Market:      v.Get("market"),
		Text:        v.Get("q"),
		Department:  v.Get("department"),
		Fulfilment:  strings.ToUpper(v.Get("fulfilment")),
		SellerKind:  strings.ToUpper(v.Get("sellerKind")),
		InStockOnly: v.Get("inStock") == "true",
		Sort:        domain.Sort(v.Get("sort")),
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
	page, err := h.searcher.Search(r.Context(), q)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, page)
}

func (h *handlersImpl) suggest(w http.ResponseWriter, r *http.Request) {
	out, err := h.suggester.Suggest(r.Context(), r.URL.Query().Get("market"), r.URL.Query().Get("q"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=30")
	writeJSON(w, out)
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, domain.ErrInvalidQuery) {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "search.invalid-query", err.Error()))
		return
	}
	problem.Write(w, r, problem.New(http.StatusBadGateway, "search.unavailable", "Search is temporarily unavailable"))
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

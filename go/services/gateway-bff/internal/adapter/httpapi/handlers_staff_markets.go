package httpapi

import (
	"net/http"
)

func (h *handlersImpl) registerStaffMarkets(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/markets/countries", h.staffMarketCountries)
	route("GET /v1/staff/markets/countries/{code}", h.staffMarketCountry)
	route("PATCH /v1/staff/markets/countries/{code}", h.staffUpdateMarketCountry)
	route("POST /v1/staff/markets/countries/{code}/regions", h.staffAddMarketRegion)
	route("PATCH /v1/staff/markets/countries/{code}/regions/{region}", h.staffUpdateMarketRegion)
	route("POST /v1/staff/markets/countries/{code}/regions/{region}/cities", h.staffAddMarketCities)
	route("PATCH /v1/staff/markets/countries/{code}/regions/{region}/cities", h.staffSetMarketCities)
	route("DELETE /v1/staff/markets/countries/{code}/regions/{region}/cities/{city}", h.staffRemoveMarketCity)
	route("POST /v1/staff/markets/publications", h.staffPublishMarkets)
	route("GET /v1/staff/places", h.staffPlaces)
}

func (h *handlersImpl) staffMarketCountries(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Markets.Countries(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffMarketCountry(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Markets.Country(r.Context(), callerFrom(r.Context()), r.PathValue("code"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdateMarketCountry(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Markets.UpdateCountry(r.Context(), callerFrom(r.Context()), r.PathValue("code"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAddMarketRegion(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Markets.AddRegion(r.Context(), callerFrom(r.Context()), r.PathValue("code"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffUpdateMarketRegion(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Markets.UpdateRegion(r.Context(), callerFrom(r.Context()), r.PathValue("code"), r.PathValue("region"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAddMarketCities(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Markets.AddCities(r.Context(), callerFrom(r.Context()), r.PathValue("code"), r.PathValue("region"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSetMarketCities(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Markets.SetCities(r.Context(), callerFrom(r.Context()), r.PathValue("code"), r.PathValue("region"), r.Header.Get("If-Match"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRemoveMarketCity(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Markets.RemoveCity(r.Context(), callerFrom(r.Context()), r.PathValue("code"), r.PathValue("region"), r.PathValue("city"), r.Header.Get("If-Match"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPublishMarkets(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Markets.Publish(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffPlaces(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Markets.Places(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

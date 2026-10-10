// Package httpapi is the inbound HTTP adapter for carrier-gateway (contract: contracts/openapi/carrier-gateway.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// Services are the use cases the HTTP API exposes.
type Services struct {
	Rates     app.RateService
	Labels    app.LabelService
	Tracking  app.TrackingService
	Pickups   app.PickupService
	Addresses app.AddressService
}

// Handlers exposes carrier-gateway over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, optionalAuth, requiredAuth func(http.Handler) http.Handler)
}

type handlersImpl struct{ svc Services }

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(svc Services) Handlers { return &handlersImpl{svc: svc} }

func (h *handlersImpl) Register(mux *http.ServeMux, optionalAuth, requiredAuth func(http.Handler) http.Handler) {
	req := func(perm string, fn http.HandlerFunc) http.Handler { return requiredAuth(auth.Require(perm, fn)) }
	mux.Handle("POST /v1/rates", req(app.PermRate, h.rates))
	mux.Handle("POST /v1/labels", req(app.PermLabel, h.buyLabel))
	mux.Handle("GET /v1/labels/{id}", req(app.PermLabel, h.getLabel))
	mux.Handle("GET /v1/labels/{id}/document", req(app.PermLabel, h.labelDocument))
	mux.Handle("POST /v1/labels/{id}/void", req(app.PermVoid, h.voidLabel))
	mux.Handle("GET /v1/tracking/{carrier}/{trackingNumber}", req(app.PermRate, h.track))
	mux.Handle("POST /v1/tracking", req(app.PermLabel, h.registerTracking))
	mux.Handle("POST /v1/pickups", req(app.PermLabel, h.schedulePickup))
	mux.Handle("POST /v1/addresses/validate", req(app.PermRate, h.validateAddress))
	mux.Handle("GET /v1/pickup-locations", optionalAuth(http.HandlerFunc(h.listLocations)))
	mux.Handle("GET /v1/pickup-locations/{id}", optionalAuth(http.HandlerFunc(h.getLocation)))
	mux.Handle("POST /v1/pickup-locations", requiredAuth(http.HandlerFunc(h.saveLocation)))
	mux.Handle("PUT /v1/pickup-locations/{id}", requiredAuth(http.HandlerFunc(h.saveLocation)))
	mux.Handle("POST /v1/pickup-locations/{id}/ready", requiredAuth(http.HandlerFunc(h.markReady)))
	// Webhooks authenticate with the carrier's signature or credential, not a bearer token.
	mux.HandleFunc("POST /v1/webhooks/{carrier}", h.webhook)
}

// ShipmentDTO is the wire form of a shipment (shipDate as YYYY-MM-DD).
type ShipmentDTO struct {
	Reference  string          `json:"reference"`
	OriginNode string          `json:"originNode"`
	From       domain.Address  `json:"from"`
	To         domain.Address  `json:"to"`
	Parcels    []domain.Parcel `json:"parcels"`
	ShipDate   string          `json:"shipDate"`
	Customs    *domain.Customs `json:"customs"`
}

func (d ShipmentDTO) toDomain() (domain.Shipment, error) {
	s := domain.Shipment{Reference: d.Reference, OriginNode: d.OriginNode, From: d.From, To: d.To, Parcels: d.Parcels, Customs: d.Customs}
	if d.ShipDate != "" {
		t, err := time.Parse(time.DateOnly, d.ShipDate)
		if err != nil {
			return domain.Shipment{}, problem.New(http.StatusBadRequest, "carrier-gateway.invalid-request", "shipDate must be YYYY-MM-DD")
		}
		s.ShipDate = t
	}
	return s, nil
}

func (h *handlersImpl) rates(w http.ResponseWriter, r *http.Request) {
	var b struct {
		CartID   string               `json:"cartId"`
		Carriers []domain.CarrierCode `json:"carriers"`
		Shipment ShipmentDTO          `json:"shipment"`
	}
	if !decode(w, r, &b) {
		return
	}
	s, err := b.Shipment.toDomain()
	if err != nil {
		writeErr(w, r, err)
		return
	}
	res, err := h.svc.Rates.Shop(r.Context(), app.RateRequest{CartID: b.CartID, Shipment: s, Carriers: b.Carriers})
	respond(w, r, http.StatusOK, res, err)
}

func (h *handlersImpl) buyLabel(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Carrier  domain.CarrierCode `json:"carrier"`
		Service  domain.ServiceCode `json:"service"`
		Shipment ShipmentDTO        `json:"shipment"`
	}
	if !decode(w, r, &b) {
		return
	}
	s, err := b.Shipment.toDomain()
	if err != nil {
		writeErr(w, r, err)
		return
	}
	a, _ := auth.FromContext(r.Context())
	caller := a.OrgID
	if caller == "" {
		caller = a.Subject
	}
	v, err := h.svc.Labels.Buy(r.Context(), app.BuyRequest{IdempotencyKey: r.Header.Get("Idempotency-Key"), Caller: caller,
		Shipment: s, Carrier: b.Carrier, Service: b.Service})
	respond(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) getLabel(w http.ResponseWriter, r *http.Request) {
	v, err := h.svc.Labels.Get(r.Context(), r.PathValue("id"))
	respond(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) labelDocument(w http.ResponseWriter, r *http.Request) {
	data, ct, err := h.svc.Labels.Document(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(data) // #nosec G705 -- label bytes from our object store, served as PDF/ZPL with nosniff
}

func (h *handlersImpl) voidLabel(w http.ResponseWriter, r *http.Request) {
	v, err := h.svc.Labels.Void(r.Context(), r.PathValue("id"))
	respond(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) track(w http.ResponseWriter, r *http.Request) {
	c, err := domain.ParseCarrier(r.PathValue("carrier"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	v, err := h.svc.Tracking.Track(r.Context(), c, r.PathValue("trackingNumber"))
	respond(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) registerTracking(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Carrier        string `json:"carrier"`
		TrackingNumber string `json:"trackingNumber"`
		Reference      string `json:"reference"`
	}
	if !decode(w, r, &b) {
		return
	}
	c, err := domain.ParseCarrier(b.Carrier)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	v, err := h.svc.Tracking.Register(r.Context(), c, strings.TrimSpace(b.TrackingNumber), b.Reference)
	respond(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) schedulePickup(w http.ResponseWriter, r *http.Request) {
	var b struct {
		domain.PickupRequest
		Date string `json:"date"`
	}
	if !decode(w, r, &b) {
		return
	}
	d, err := time.Parse(time.DateOnly, b.Date)
	if err != nil {
		writeErr(w, r, problem.New(http.StatusBadRequest, "carrier-gateway.invalid-request", "date must be YYYY-MM-DD"))
		return
	}
	req := b.PickupRequest
	req.Date = d
	p, err := h.svc.Pickups.Schedule(r.Context(), req)
	respond(w, r, http.StatusCreated, p, err)
}

func (h *handlersImpl) validateAddress(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Carrier string         `json:"carrier"`
		Address domain.Address `json:"address"`
	}
	if !decode(w, r, &b) {
		return
	}
	var c domain.CarrierCode
	if b.Carrier != "" {
		var err error
		if c, err = domain.ParseCarrier(b.Carrier); err != nil {
			writeErr(w, r, err)
			return
		}
	}
	v, err := h.svc.Addresses.Validate(r.Context(), c, b.Address)
	respond(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) listLocations(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	ls, err := h.svc.Pickups.ListLocations(r.Context(), q.Get("ownerOrg"), q.Get("postalPrefix"))
	respond(w, r, http.StatusOK, map[string]any{"items": ls}, err)
}

func (h *handlersImpl) getLocation(w http.ResponseWriter, r *http.Request) {
	l, err := h.svc.Pickups.GetLocation(r.Context(), r.PathValue("id"))
	respond(w, r, http.StatusOK, l, err)
}

func (h *handlersImpl) saveLocation(w http.ResponseWriter, r *http.Request) {
	var l domain.PickupLocation
	if !decode(w, r, &l) {
		return
	}
	status := http.StatusCreated
	if id := r.PathValue("id"); id != "" {
		l.ID, status = id, http.StatusOK
	}
	a, _ := auth.FromContext(r.Context())
	saved, err := h.svc.Pickups.SaveLocation(r.Context(), a, l)
	respond(w, r, status, saved, err)
}

func (h *handlersImpl) markReady(w http.ResponseWriter, r *http.Request) {
	var b app.ReadyRequest
	if !decode(w, r, &b) {
		return
	}
	a, _ := auth.FromContext(r.Context())
	if err := h.svc.Pickups.MarkReady(r.Context(), a, r.PathValue("id"), b); err != nil {
		writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusAccepted)
}

func (h *handlersImpl) webhook(w http.ResponseWriter, r *http.Request) {
	c, err := domain.ParseCarrier(r.PathValue("carrier"))
	if err != nil {
		writeErr(w, r, problem.New(http.StatusNotFound, "carrier-gateway.not-found", "Unknown carrier"))
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		writeErr(w, r, problem.New(http.StatusRequestEntityTooLarge, "carrier-gateway.invalid-request", "Webhook body too large"))
		return
	}
	res, err := h.svc.Tracking.HandleWebhook(r.Context(), c, r.Header, body)
	respond(w, r, http.StatusOK, res, err)
}

func decode(w http.ResponseWriter, r *http.Request, dst any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10)).Decode(dst); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "carrier-gateway.invalid-json", "Malformed request body"))
		return false
	}
	return true
}

func respond(w http.ResponseWriter, r *http.Request, status int, v any, err error) {
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeErr maps domain errors to RFC 9457 problems with stable codes.
func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	var pe *problem.Error
	if errors.As(err, &pe) {
		problem.Write(w, r, pe)
		return
	}
	detail := err.Error()
	var ce *domain.CarrierError
	if errors.As(err, &ce) {
		detail = ce.Error()
	}
	p := func(status int, code string) {
		problem.Write(w, r, problem.New(status, "carrier-gateway."+code, detail))
	}
	switch {
	case errors.Is(err, domain.ErrSignature):
		problem.Write(w, r, problem.New(http.StatusUnauthorized, "carrier-gateway.webhook-unverified", "Webhook verification failed"))
	case errors.Is(err, app.ErrForbidden):
		p(http.StatusForbidden, "forbidden")
	case errors.Is(err, app.ErrIdempotencyMismatch):
		p(http.StatusUnprocessableEntity, "idempotency-key-reused")
	case errors.Is(err, domain.ErrInvalid):
		p(http.StatusBadRequest, "invalid-request")
	case errors.Is(err, domain.ErrNotFound):
		p(http.StatusNotFound, "not-found")
	case errors.Is(err, domain.ErrConflict):
		p(http.StatusConflict, "conflict")
	case errors.Is(err, domain.ErrCarrierDisabled):
		p(http.StatusUnprocessableEntity, "carrier-disabled")
	case errors.Is(err, domain.ErrUnsupported):
		p(http.StatusUnprocessableEntity, "unsupported")
	case errors.Is(err, domain.ErrCarrierRejected):
		p(http.StatusUnprocessableEntity, "carrier-rejected")
	case errors.Is(err, domain.ErrCarrierAuth):
		p(http.StatusBadGateway, "carrier-auth")
	case errors.Is(err, domain.ErrCarrierUnavailable):
		w.Header().Set("Retry-After", "5")
		p(http.StatusServiceUnavailable, "carrier-unavailable")
	default:
		problem.Write(w, r, problem.New(http.StatusInternalServerError, "carrier-gateway.internal", "Unexpected error"))
	}
}

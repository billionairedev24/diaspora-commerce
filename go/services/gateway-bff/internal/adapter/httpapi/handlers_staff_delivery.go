package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- Delivery module (docs/engineering/delivery-fulfilment.md)

func (h *handlersImpl) registerStaffDelivery(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/deliveries", h.staffDeliveries)
	route("GET /v1/staff/deliveries/couriers", h.staffDeliveryCouriers)
	route("POST /v1/staff/deliveries/uploads", h.staffDeliveryUpload)
	route("GET /v1/staff/deliveries/{shipmentId}", h.staffDelivery)
	route("POST /v1/staff/deliveries/{shipmentId}/take", h.staffDeliveryTake)
	route("POST /v1/staff/deliveries/{shipmentId}/assign", h.staffDeliveryAssign)
	route("POST /v1/staff/deliveries/{shipmentId}/picked-up", h.staffDeliveryPickedUp)
	route("POST /v1/staff/deliveries/{shipmentId}/out-for-delivery", h.staffDeliveryOut)
	route("POST /v1/staff/deliveries/{shipmentId}/delivered", h.staffDeliveryDelivered)
	route("POST /v1/staff/deliveries/{shipmentId}/failed", h.staffDeliveryFailed)
	route("PUT /v1/staff/deliveries/{shipmentId}/proof", h.staffDeliveryCorrectProof)
}

func (h *handlersImpl) staffDeliveries(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	if size == 0 {
		size = 25
	}
	v, err := h.s.Staff.Delivery.List(r.Context(), callerFrom(r.Context()), app.DeliveryQuery{Status: q.Get("status"), Mode: q.Get("mode"), Text: q.Get("q"),
		Assignee: q.Get("assignee"), City: q.Get("city"), Seller: q.Get("seller"), Sort: q.Get("sort"), Desc: q.Get("desc") == "true", Page: page, Size: size})
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryCouriers(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Delivery.Couriers(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDelivery(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Delivery.Detail(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryUpload(w http.ResponseWriter, r *http.Request) {
	var b domain.DeliveryUploadRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Delivery.Upload(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

// deliveryAction runs one of the actions that take a version and a note (an empty body is fine).
func (h *handlersImpl) deliveryAction(w http.ResponseWriter, r *http.Request, run func(s app.StaffDeliveryService, id, idem string, b domain.DeliveryActionRequest) (domain.DeliveryDetail, error)) {
	var b domain.DeliveryActionRequest
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := run(h.s.Staff.Delivery, r.PathValue("shipmentId"), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryTake(w http.ResponseWriter, r *http.Request) {
	h.deliveryAction(w, r, func(s app.StaffDeliveryService, id, idem string, b domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
		return s.Take(r.Context(), callerFrom(r.Context()), id, idem, b)
	})
}

func (h *handlersImpl) staffDeliveryPickedUp(w http.ResponseWriter, r *http.Request) {
	h.deliveryAction(w, r, func(s app.StaffDeliveryService, id, idem string, b domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
		return s.PickedUp(r.Context(), callerFrom(r.Context()), id, idem, b)
	})
}

func (h *handlersImpl) staffDeliveryOut(w http.ResponseWriter, r *http.Request) {
	h.deliveryAction(w, r, func(s app.StaffDeliveryService, id, idem string, b domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
		return s.OutForDelivery(r.Context(), callerFrom(r.Context()), id, idem, b)
	})
}

func (h *handlersImpl) staffDeliveryAssign(w http.ResponseWriter, r *http.Request) {
	var b domain.DeliveryAssignRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Delivery.Assign(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryFailed(w http.ResponseWriter, r *http.Request) {
	var b domain.DeliveryFailedRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Delivery.Failed(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryDelivered(w http.ResponseWriter, r *http.Request) {
	var b domain.DeliveryProofRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Delivery.Delivered(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryCorrectProof(w http.ResponseWriter, r *http.Request) {
	var b domain.DeliveryProofRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Delivery.CorrectProof(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

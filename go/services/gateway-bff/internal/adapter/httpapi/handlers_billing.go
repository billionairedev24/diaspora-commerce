package httpapi

import (
	"encoding/json"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// registerSellerBilling adds Plan & billing beyond the overview (S292–S297): rewards, plan change previews and adding
// a payment method through a Stripe SetupIntent.
func (h *handlersImpl) registerSellerBilling(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/seller/billing/rewards", h.billingRewards)
	route("GET /v1/seller/billing/plan/preview", h.previewPlanChange)
	route("POST /v1/seller/billing/setup-intents", h.billingSetupIntent)
	route("POST /v1/seller/billing/payment-methods", h.addBillingMethod)
	route("PUT /v1/seller/billing/plan-fee-collection", h.setPlanFeeCollection)
}

func (h *handlersImpl) setPlanFeeCollection(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Mode string `json:"mode"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMoney.SetPlanFeeCollection(r.Context(), callerFrom(r.Context()), b.Mode)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) billingRewards(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMoney.Rewards(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) previewPlanChange(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.SellerMoney.PreviewPlanChange(r.Context(), callerFrom(r.Context()), q.Get("planCode"), q.Get("cadence"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) billingSetupIntent(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMoney.SetupIntent(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) addBillingMethod(w http.ResponseWriter, r *http.Request) {
	var b domain.AddBillingPaymentMethod
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMoney.AddPaymentMethod(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

// registerStaffPlans adds Plans & billing (S292–S297): one route per app.StaffPlanOps entry plus the organisation search.
func (h *handlersImpl) registerStaffPlans(route func(pattern string, fn http.HandlerFunc)) {
	if h.s.Staff.Plans == nil {
		return
	}
	for _, op := range app.StaffPlanOps {
		route(op.Route, h.staffPlanOp(op))
	}
	route("GET /v1/staff/billing/orgs", h.staffBillingOrgs)
}

func (h *handlersImpl) staffPlanOp(op app.StaffPlanOp) http.HandlerFunc {
	params := app.StaffPlanOpParams(op)
	status := op.Status
	if status == 0 {
		status = http.StatusOK
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var body json.RawMessage
		if op.Body {
			var obj map[string]any
			if err := decode(r, &obj, true); err != nil {
				h.reply(w, r, 0, nil, err)
				return
			}
			if obj == nil {
				obj = map[string]any{}
			}
			raw, err := json.Marshal(obj)
			if err != nil {
				h.reply(w, r, 0, nil, err)
				return
			}
			body = raw
		}
		values := make(map[string]string, len(params))
		for _, p := range params {
			values[p] = r.PathValue(p)
		}
		v, err := h.s.Staff.Plans.Do(r.Context(), callerFrom(r.Context()), op.Name, values, r.URL.Query(), body,
			r.Header.Get("Idempotency-Key"))
		h.reply(w, r, status, v, err)
	}
}

func (h *handlersImpl) staffBillingOrgs(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Plans.SearchOrgs(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"))
	h.reply(w, r, http.StatusOK, v, err)
}

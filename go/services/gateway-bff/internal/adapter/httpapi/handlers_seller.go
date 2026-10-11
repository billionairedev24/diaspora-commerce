package httpapi

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Seller Center & Provider Hub operations (contracts/openapi/gateway-bff-seller.yaml). They sit behind the same chain
// as the storefront routes: CORS, CSRF Origin check, body limit and session resolution (token relay).

func (h *handlersImpl) registerSeller(route func(pattern string, fn http.HandlerFunc)) {
	if h.s.SellerShell == nil {
		return
	}
	// Shell
	route("GET /v1/seller/session", h.sellerSession)
	route("PUT /v1/seller/session/active-org", h.setActiveOrg)
	route("GET /v1/seller/onboarding", h.sellerOnboarding)
	route("GET /v1/seller/compliance", h.sellerCompliance)
	route("GET /v1/seller/dashboard", h.sellerDashboard)
	// Orders
	route("GET /v1/seller/orders", h.sellerOrders)
	route("GET /v1/seller/orders/{orderNumber}", h.sellerOrder)
	route("POST /v1/seller/orders/{orderNumber}/shipments/{shipmentId}/status", h.shipmentStatus)
	route("POST /v1/seller/orders/{orderNumber}/shipments/{shipmentId}/rates", h.shippingRates)
	route("POST /v1/seller/orders/{orderNumber}/shipments/{shipmentId}/labels", h.buyLabel)
	route("GET /v1/seller/ship-from-addresses", h.shipFromList)
	route("POST /v1/seller/ship-from-addresses", h.shipFromCreate)
	route("PUT /v1/seller/ship-from-addresses/{addressId}", h.shipFromUpdate)
	route("POST /v1/seller/ship-from-addresses/{addressId}/default", h.shipFromDefault)
	route("DELETE /v1/seller/ship-from-addresses/{addressId}", h.shipFromDelete)
	route("PUT /v1/seller/ship-from-addresses/{addressId}/afrotransact-pickup", h.shipFromPickup)
	if h.s.SellerOffers != nil {
		route("GET /v1/seller/offers", h.sellerOffers)
		route("PUT /v1/seller/offers/{offerId}/fulfilment", h.offerFulfilment)
	}
	// Listings, builder, stock
	route("GET /v1/seller/listings", h.listings)
	route("POST /v1/seller/listings/{productId}/status", h.listingStatus)
	route("POST /v1/seller/products", h.submitProduct)
	route("GET /v1/seller/catalog/categories", h.categoryTree)
	route("GET /v1/seller/catalog/product-types/{code}", h.productType)
	route("GET /v1/seller/catalog/gtin/{code}", h.checkGTIN)
	route("GET /v1/seller/category-requests", h.categoryRequests)
	route("POST /v1/seller/category-requests", h.requestCategory)
	route("POST /v1/seller/uploads", h.requestUpload)
	route("POST /v1/seller/uploads/{assetId}/complete", h.completeUpload)
	route("GET /v1/seller/inventory", h.inventory)
	route("POST /v1/seller/inventory/adjustments", h.adjustStock)
	// Exports (every table: CSV, Excel with charts, PDF with charts)
	if h.s.SellerExports != nil {
		route("GET /v1/seller/exports", h.sellerExports)
		route("POST /v1/seller/exports", h.sellerCreateExport)
		route("GET /v1/seller/exports/{exportId}", h.sellerExport)
		route("GET /v1/seller/exports/{exportId}/download", h.sellerDownload)
		route("GET /v1/seller/export-files/{exportId}", h.sellerExportFile)
	}
	// Money
	route("GET /v1/seller/payouts", h.payouts)
	route("POST /v1/seller/payouts", h.requestPayout)
	if h.s.SellerFees != nil {
		route("GET /v1/seller/fees", h.sellerFees)
	}
	route("GET /v1/seller/billing", h.billing)
	route("POST /v1/seller/billing/plan", h.changePlan)
	route("POST /v1/seller/billing/payment-methods/{paymentMethodId}/default", h.defaultBillingMethod)
	route("DELETE /v1/seller/billing/payment-methods/{paymentMethodId}", h.removeBillingMethod)
	// Team
	route("GET /v1/seller/team", h.team)
	route("POST /v1/seller/team/invitations", h.invite)
	route("PUT /v1/seller/team/members/{userId}", h.assignRole)
	route("DELETE /v1/seller/team/members/{userId}", h.removeMember)
	route("DELETE /v1/seller/team/invitations/{invitationId}", h.revokeInvitation)
	route("POST /v1/seller/invitations/lookup", h.lookupInvitation)
	route("POST /v1/seller/invitations/accept", h.acceptInvitation)
	// Provider Hub
	route("GET /v1/seller/bookings/calendar", h.calendar)
	route("GET /v1/seller/bookings/requests", h.bookingRequests)
	route("POST /v1/seller/bookings/{bookingId}/actions", h.bookingAction)
	route("GET /v1/seller/provider/hours", h.workingHours)
	route("PUT /v1/seller/provider/hours", h.saveWorkingHours)
	route("GET /v1/seller/provider/services", h.services)
	route("POST /v1/seller/provider/services", h.createService)
	route("PATCH /v1/seller/provider/services/{serviceId}", h.updateService)
	route("GET /v1/seller/provider/cancellation-policy", h.cancellationPolicy)
	route("PUT /v1/seller/provider/cancellation-policy", h.saveCancellationPolicy)
	route("GET /v1/seller/provider/earnings", h.earnings)
	route("GET /v1/seller/storefront", h.publicPage)
	route("PUT /v1/seller/storefront", h.savePublicPage)
	route("POST /v1/seller/storefront/publish", h.publishPublicPage)
}

func intQuery(r *http.Request, name string, def int) (int, error) {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return 0, domain.ValidationProblem(map[string]string{name: "Must be a whole number"})
	}
	return n, nil
}

// ---------------------------------------------------------------- shell

func (h *handlersImpl) sellerSession(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerShell.Session(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) setActiveOrg(w http.ResponseWriter, r *http.Request) {
	var b struct {
		OrgID string `json:"orgId"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerShell.SetActiveOrg(r.Context(), callerFrom(r.Context()), b.OrgID)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerOnboarding(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerShell.Onboarding(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerCompliance(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerShell.Compliance(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerDashboard(w http.ResponseWriter, r *http.Request) {
	days, err := intQuery(r, "rangeDays", 30)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerDashboard.Dashboard(r.Context(), callerFrom(r.Context()), days)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- orders

func (h *handlersImpl) sellerOrders(w http.ResponseWriter, r *http.Request) {
	limit, err := intQuery(r, "limit", 20)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	q := r.URL.Query()
	v, err := h.s.SellerOrders.List(r.Context(), callerFrom(r.Context()), app.SellerTableQuery{Q: strings.TrimSpace(q.Get("q")), Status: q.Get("status"),
		Cursor: q.Get("cursor"), Sort: q["sort"], Size: limit})
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerOrder(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerOrders.Get(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) shipmentStatus(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Status string `json:"status"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerOrders.UpdateStatus(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"),
		r.PathValue("orderNumber"), r.PathValue("shipmentId"), b.Status)
	h.reply(w, r, http.StatusOK, v, err)
}

type labelBody struct {
	Carrier  string               `json:"carrier"`
	Service  string               `json:"service"`
	Parcel   domain.Parcel        `json:"parcel"`
	ShipFrom domain.SellerAddress `json:"shipFrom"`
}

func (h *handlersImpl) shippingRates(w http.ResponseWriter, r *http.Request) {
	var b labelBody
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerOrders.Rates(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"), r.PathValue("shipmentId"), b.Parcel, b.ShipFrom)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) buyLabel(w http.ResponseWriter, r *http.Request) {
	var b labelBody
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerOrders.BuyLabel(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), r.PathValue("orderNumber"),
		r.PathValue("shipmentId"), b.Carrier, b.Service, b.Parcel, b.ShipFrom)
	h.reply(w, r, http.StatusCreated, v, err)
}

// ---------------------------------------------------------------- listings

func (h *handlersImpl) listings(w http.ResponseWriter, r *http.Request) {
	page, err := intQuery(r, "page", 0)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	size, err := intQuery(r, "size", 25)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	q := r.URL.Query()
	v, err := h.s.SellerCatalog.Listings(r.Context(), callerFrom(r.Context()), app.SellerTableQuery{Q: strings.TrimSpace(q.Get("q")), Status: q.Get("status"),
		Sort: q["sort"], Page: page, Size: size})
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) listingStatus(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Active *bool `json:"active"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if b.Active == nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"active": "Required"}))
		return
	}
	v, err := h.s.SellerCatalog.SetActive(r.Context(), callerFrom(r.Context()), r.PathValue("productId"), *b.Active)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) submitProduct(w http.ResponseWriter, r *http.Request) {
	var b domain.ProductDraft
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerCatalog.Submit(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) categoryTree(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerCatalog.Categories(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) productType(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerCatalog.ProductType(r.Context(), callerFrom(r.Context()), r.PathValue("code"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) checkGTIN(w http.ResponseWriter, r *http.Request) {
	isbn := r.URL.Query().Get("isbn") == "true"
	v, err := h.s.SellerCatalog.CheckGTIN(r.Context(), callerFrom(r.Context()), r.PathValue("code"), isbn)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) categoryRequests(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerCatalog.CategoryRequests(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) requestCategory(w http.ResponseWriter, r *http.Request) {
	var b domain.CategoryRequestInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerCatalog.RequestCategory(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) requestUpload(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Purpose     string `json:"purpose"`
		FileName    string `json:"fileName"`
		ContentType string `json:"contentType"`
		Size        int64  `json:"size"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerCatalog.Upload(r.Context(), callerFrom(r.Context()), b.Purpose, b.FileName, b.ContentType, b.Size)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) completeUpload(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerCatalog.CompleteUpload(r.Context(), callerFrom(r.Context()), r.PathValue("assetId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) inventory(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerCatalog.Inventory(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("lowStock") == "true")
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) adjustStock(w http.ResponseWriter, r *http.Request) {
	var b struct {
		SKU      string `json:"sku"`
		Quantity int    `json:"quantity"`
		Reason   string `json:"reason"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerCatalog.AdjustStock(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b.SKU, b.Quantity, b.Reason)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- money

func (h *handlersImpl) payouts(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMoney.Payouts(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerFees(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerFees.Fees(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) requestPayout(w http.ResponseWriter, r *http.Request) {
	var b struct {
		AmountMinor int64 `json:"amountMinor"`
		All         bool  `json:"all"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMoney.RequestPayout(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b.AmountMinor, b.All)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) billing(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMoney.Billing(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) changePlan(w http.ResponseWriter, r *http.Request) {
	var b struct {
		PlanCode string `json:"planCode"`
		Cadence  string `json:"cadence"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMoney.ChangePlan(r.Context(), callerFrom(r.Context()), b.PlanCode, b.Cadence)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) defaultBillingMethod(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMoney.MakeDefaultPaymentMethod(r.Context(), callerFrom(r.Context()), r.PathValue("paymentMethodId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) removeBillingMethod(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMoney.RemovePaymentMethod(r.Context(), callerFrom(r.Context()), r.PathValue("paymentMethodId"))
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- team

func (h *handlersImpl) team(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerTeam.Team(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) invite(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Email   string `json:"email"`
		RoleKey string `json:"roleKey"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerTeam.Invite(r.Context(), callerFrom(r.Context()), b.Email, b.RoleKey)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) assignRole(w http.ResponseWriter, r *http.Request) {
	var b struct {
		RoleKey string `json:"roleKey"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerTeam.AssignRole(r.Context(), callerFrom(r.Context()), r.PathValue("userId"), b.RoleKey)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) removeMember(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerTeam.RemoveMember(r.Context(), callerFrom(r.Context()), r.PathValue("userId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) revokeInvitation(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerTeam.RevokeInvitation(r.Context(), callerFrom(r.Context()), r.PathValue("invitationId"))
	h.reply(w, r, http.StatusOK, v, err)
}

type invitationTokenBody struct {
	Token string `json:"token"`
}

func (h *handlersImpl) lookupInvitation(w http.ResponseWriter, r *http.Request) {
	var b invitationTokenBody
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerTeam.LookupInvitation(r.Context(), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) acceptInvitation(w http.ResponseWriter, r *http.Request) {
	var b invitationTokenBody
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerTeam.AcceptInvitation(r.Context(), callerFrom(r.Context()), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- provider hub

func (h *handlersImpl) calendar(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.ProviderHub.Calendar(r.Context(), callerFrom(r.Context()), q.Get("from"), q.Get("to"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) bookingRequests(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.ProviderHub.Requests(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) bookingAction(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Action          string     `json:"action"`
		Reason          string     `json:"reason"`
		ProposedStartAt *time.Time `json:"proposedStartAt"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.ProviderHub.Act(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), r.PathValue("bookingId"), b.Action, b.Reason, b.ProposedStartAt)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) workingHours(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.ProviderHub.Hours(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) saveWorkingHours(w http.ResponseWriter, r *http.Request) {
	var b domain.Availability
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.ProviderHub.SaveHours(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) services(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.ProviderHub.Services(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) createService(w http.ResponseWriter, r *http.Request) {
	var b domain.ServiceInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.ProviderHub.CreateService(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) updateService(w http.ResponseWriter, r *http.Request) {
	var b domain.ServiceInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.ProviderHub.UpdateService(r.Context(), callerFrom(r.Context()), r.PathValue("serviceId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) cancellationPolicy(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.ProviderHub.Policy(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) saveCancellationPolicy(w http.ResponseWriter, r *http.Request) {
	var b domain.PolicyInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.ProviderHub.SavePolicy(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) earnings(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.ProviderHub.Earnings(r.Context(), callerFrom(r.Context()), q.Get("from"), q.Get("to"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) publicPage(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.ProviderHub.PublicPage(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) savePublicPage(w http.ResponseWriter, r *http.Request) {
	var b domain.PublicPageInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.ProviderHub.SavePublicPage(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) publishPublicPage(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.ProviderHub.PublishPublicPage(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- ship-from addresses

func (h *handlersImpl) shipFromList(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerShipFrom.List(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) shipFromCreate(w http.ResponseWriter, r *http.Request) {
	var b domain.ShipFromInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerShipFrom.Create(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) shipFromUpdate(w http.ResponseWriter, r *http.Request) {
	var b domain.ShipFromInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerShipFrom.Update(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) shipFromDefault(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerShipFrom.MakeDefault(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) shipFromDelete(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerShipFrom.Delete(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) shipFromPickup(w http.ResponseWriter, r *http.Request) {
	var b domain.AfrotransactPickupRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerShipFrom.SetPickup(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- offers: fulfilment choice (S255)

func (h *handlersImpl) sellerOffers(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerOffers.List(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("productId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) offerFulfilment(w http.ResponseWriter, r *http.Request) {
	var b domain.OfferFulfilmentRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerOffers.SetFulfilment(r.Context(), callerFrom(r.Context()), r.PathValue("offerId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- exports

func (h *handlersImpl) sellerExports(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerExports.List(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) sellerCreateExport(w http.ResponseWriter, r *http.Request) {
	var b app.ExportRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, ready, err := h.s.SellerExports.Create(r.Context(), callerFrom(r.Context()), b)
	status := http.StatusAccepted
	if ready {
		status = http.StatusCreated
	}
	h.reply(w, r, status, v, err)
}

func (h *handlersImpl) sellerExport(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerExports.Get(r.Context(), callerFrom(r.Context()), r.PathValue("exportId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerDownload(w http.ResponseWriter, r *http.Request) {
	u, err := h.s.SellerExports.Download(r.Context(), callerFrom(r.Context()), r.PathValue("exportId"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	h.finish(w, callerFrom(r.Context()))
	w.Header().Set("Cache-Control", "no-store")
	// The location is a link signed by the BFF's export store or media-service, never request input.
	w.Header().Set("Location", u)
	w.WriteHeader(http.StatusFound)
}

func (h *handlersImpl) sellerExportFile(w http.ResponseWriter, r *http.Request) {
	h.serveExportFile(w, r, h.s.SellerExportFiles)
}

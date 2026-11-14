package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- public storefront (/b/{slug})

func (h *handlersImpl) storefront(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Storefronts.Get(r.Context(), r.PathValue("slug"))
	h.publicJSON(w, r, v, err)
}

func (h *handlersImpl) storefrontProducts(w http.ResponseWriter, r *http.Request) {
	v := r.URL.Query()
	page, _ := strconv.Atoi(v.Get("page"))
	size, _ := strconv.Atoi(v.Get("size"))
	q := domain.StorefrontQuery{Q: v.Get("q"), Category: v.Get("category"), Price: v.Get("price"), Sort: v.Get("sort"), Page: page, Size: size}
	if q.Price != "" {
		if _, ok := domain.PriceBucketByID(q.Price); !ok {
			h.reply(w, r, 0, nil, domain.NewProblem(http.StatusBadRequest, domain.CodeInvalidQuery, "Invalid query", "Unknown price range."))
			return
		}
	}
	if len(q.Category) > 80 {
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusBadRequest, domain.CodeInvalidQuery, "Invalid query", "Unknown category."))
		return
	}
	res, err := h.s.Shopper.Storefronts.Products(r.Context(), r.PathValue("slug"), q)
	h.publicJSON(w, r, res, err)
}

// ---------------------------------------------------------------- seller: store page settings

func (h *handlersImpl) storePageGet(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerStorePage.Get(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) storePageSave(w http.ResponseWriter, r *http.Request) {
	var b domain.StorePageDraftInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerStorePage.SaveDraft(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) storePagePublish(w http.ResponseWriter, r *http.Request) {
	var b domain.StorePageVersionInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerStorePage.Publish(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) storePageUnpublish(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerStorePage.Unpublish(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) storePageAudit(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerStorePage.Audit(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) storePagePreview(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerStorePage.Preview(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) storePageFind(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerStorePage.FindProducts(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"))
	h.reply(w, r, http.StatusOK, v, err)
}

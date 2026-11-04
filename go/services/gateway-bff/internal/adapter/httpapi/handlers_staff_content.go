package httpapi

import (
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func (h *handlersImpl) registerStaffContent(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/content/pages", h.staffContentPages)
	route("GET /v1/staff/content/pages/{kind}/{key}", h.staffContentPage)
	route("GET /v1/staff/content/pages/{kind}/{key}/versions/{version}", h.staffContentVersion)
	route("PUT /v1/staff/content/pages/{kind}/{key}/draft", h.staffSaveContentDraft)
	route("DELETE /v1/staff/content/pages/{kind}/{key}/draft", h.staffDiscardContentDraft)
	route("POST /v1/staff/content/pages/{kind}/{key}/publication", h.staffPublishContent)
	route("POST /v1/staff/content/pages/{kind}/{key}/rollback", h.staffRollbackContent)
}

func (h *handlersImpl) staffContentPages(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.ContentPages.Pages(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffContentPage(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.ContentPages.Page(r.Context(), callerFrom(r.Context()), r.PathValue("kind"), r.PathValue("key"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffContentVersion(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.ContentPages.Version(r.Context(), callerFrom(r.Context()), r.PathValue("kind"), r.PathValue("key"), r.PathValue("version"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSaveContentDraft(w http.ResponseWriter, r *http.Request) {
	var in domain.ContentDraftInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.ContentPages.SaveDraft(r.Context(), callerFrom(r.Context()), r.PathValue("kind"), r.PathValue("key"), in)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDiscardContentDraft(w http.ResponseWriter, r *http.Request) {
	err := h.s.Staff.ContentPages.DiscardDraft(r.Context(), callerFrom(r.Context()), r.PathValue("kind"), r.PathValue("key"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) staffPublishContent(w http.ResponseWriter, r *http.Request) {
	var in domain.ContentPublishInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.ContentPages.Publish(r.Context(), callerFrom(r.Context()), r.PathValue("kind"), r.PathValue("key"), in)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffRollbackContent(w http.ResponseWriter, r *http.Request) {
	var in domain.ContentRollbackInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.ContentPages.Rollback(r.Context(), callerFrom(r.Context()), r.PathValue("kind"), r.PathValue("key"), in)
	h.reply(w, r, http.StatusCreated, v, err)
}

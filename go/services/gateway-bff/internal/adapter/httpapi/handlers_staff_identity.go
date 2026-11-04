package httpapi

import (
	"net/http"
)

// staffInspectToken decodes a pasted JWT at identity. The body holds the token: it is decoded into a local value and handed
// on, never logged or kept.
func (h *handlersImpl) staffInspectToken(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Token string `json:"token"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Identity.InspectToken(r.Context(), callerFrom(r.Context()), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffIdentityDocuments(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Identity.Documents(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

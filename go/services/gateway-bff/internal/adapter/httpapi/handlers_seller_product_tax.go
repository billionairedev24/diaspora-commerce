package httpapi

import (
	"errors"
	"io"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Seller tax readiness and evidence: contracts/openapi/gateway-bff-seller.yaml, tag Tax readiness. The answers are catalog's JSON.

func (h *handlersImpl) productTaxReadiness(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerProductTax.Readiness(r.Context(), callerFrom(r.Context()), r.PathValue("productId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) variantTaxEvidence(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerProductTax.ListEvidence(r.Context(), callerFrom(r.Context()), r.PathValue("atin"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) uploadVariantTaxEvidence(w http.ResponseWriter, r *http.Request) {
	tooLarge := domain.NewProblem(http.StatusRequestEntityTooLarge, domain.CodePayloadTooLarge, "File too large",
		"Upload files of at most 10 MB.")
	r.Body = http.MaxBytesReader(w, r.Body, app.MaxTaxEvidenceBytes+(1<<20))
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			h.reply(w, r, 0, nil, tooLarge)
			return
		}
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "Send the file as multipart/form-data in the \"file\" field"}))
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "Choose a file"}))
		return
	}
	defer func() { _ = file.Close() }()
	data, err := io.ReadAll(io.LimitReader(file, app.MaxTaxEvidenceBytes+1))
	if err != nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "The file could not be read"}))
		return
	}
	ct := header.Header.Get("Content-Type")
	v, err := h.s.SellerProductTax.UploadEvidence(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), r.FormValue("factId"),
		port.UploadFile{Name: header.Filename, ContentType: ct, Data: data})
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) taxEvidenceFile(w http.ResponseWriter, r *http.Request) {
	f, err := h.s.SellerProductTax.EvidenceFile(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	sendFile(w, f)
}

package httpapi

import (
	"errors"
	"fmt"
	"io"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Stock CSV import (S395): contracts/openapi/gateway-bff-seller.yaml, tag "Stock import".

func (h *handlersImpl) stockImportTemplate(w http.ResponseWriter, r *http.Request) {
	f, err := h.s.SellerStockImports.Template(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("mode"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	sendFile(w, f)
}

func (h *handlersImpl) stockImportReport(w http.ResponseWriter, r *http.Request) {
	f, err := h.s.SellerStockImports.Report(r.Context(), callerFrom(r.Context()), r.PathValue("importId"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	sendFile(w, f)
}

func (h *handlersImpl) uploadStockImport(w http.ResponseWriter, r *http.Request) {
	limit := domain.BulkUploadLimits.MaxFeedFileBytes
	tooLarge := domain.NewProblem(http.StatusRequestEntityTooLarge, domain.CodePayloadTooLarge, "File too large",
		fmt.Sprintf("Upload files of at most %d MB; split larger files into several files.", limit>>20))
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			h.reply(w, r, 0, nil, tooLarge)
			return
		}
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "Send the file as multipart/form-data"}))
		return
	}
	defer func() { _ = r.MultipartForm.RemoveAll() }()
	file, header, err := r.FormFile("file")
	if err != nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "Choose a CSV (.csv) file"}))
		return
	}
	defer func() { _ = file.Close() }()
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "The file could not be read"}))
		return
	}
	if int64(len(data)) > limit {
		h.reply(w, r, 0, nil, tooLarge)
		return
	}
	v, err := h.s.SellerStockImports.Upload(r.Context(), callerFrom(r.Context()), port.UploadFile{Name: header.Filename, Data: data}, r.FormValue("mode"))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) stockImports(w http.ResponseWriter, r *http.Request) {
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
	v, err := h.s.SellerStockImports.List(r.Context(), callerFrom(r.Context()), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) stockImportDetail(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerStockImports.Get(r.Context(), callerFrom(r.Context()), r.PathValue("importId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) stockImportRows(w http.ResponseWriter, r *http.Request) {
	page, err := intQuery(r, "page", 0)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	size, err := intQuery(r, "size", 50)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	q := r.URL.Query()
	v, err := h.s.SellerStockImports.Rows(r.Context(), callerFrom(r.Context()), r.PathValue("importId"), q.Get("status"), q.Get("outcome"), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) startStockImport(w http.ResponseWriter, r *http.Request) {
	var b struct {
		ValidOnly bool `json:"validOnly"`
	}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerStockImports.Start(r.Context(), callerFrom(r.Context()), r.PathValue("importId"), b.ValidOnly)
	h.reply(w, r, http.StatusAccepted, v, err)
}

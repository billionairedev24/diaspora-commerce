package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Seller size charts: contracts/openapi/gateway-bff-seller.yaml, tag Size charts. The answers are catalog's JSON.

func (h *handlersImpl) sizeCharts(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSizeCharts.List(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sizeChart(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSizeCharts.Get(r.Context(), callerFrom(r.Context()), r.PathValue("chartId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sizeChartSuggestions(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.SellerSizeCharts.Suggestions(r.Context(), callerFrom(r.Context()), q.Get("productTypeId"), q.Get("productType"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) createSizeChart(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerSizeCharts.Create(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) updateSizeChart(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerSizeCharts.Update(r.Context(), callerFrom(r.Context()), r.PathValue("chartId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) retireSizeChart(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSizeCharts.Retire(r.Context(), callerFrom(r.Context()), r.PathValue("chartId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) duplicateSizeChart(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerSizeCharts.Duplicate(r.Context(), callerFrom(r.Context()), r.PathValue("chartId"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) sizeChartTemplate(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f, err := h.s.SellerSizeCharts.ImportTemplate(r.Context(), callerFrom(r.Context()), q.Get("productType"), q.Get("productTypeId"), q.Get("format"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	sendFile(w, f)
}

func (h *handlersImpl) importSizeCharts(w http.ResponseWriter, r *http.Request) {
	tooLarge := domain.NewProblem(http.StatusRequestEntityTooLarge, domain.CodePayloadTooLarge, "File too large",
		"Upload files of at most 10 MB; split larger files into several.")
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
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "Choose an Excel (.xlsx) or CSV (.csv) file"}))
		return
	}
	defer func() { _ = file.Close() }()
	data, err := io.ReadAll(io.LimitReader(file, domain.BulkUploadLimits.MaxFileBytes+1))
	if err != nil {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"file": "The file could not be read"}))
		return
	}
	if int64(len(data)) > domain.BulkUploadLimits.MaxFileBytes {
		h.reply(w, r, 0, nil, tooLarge)
		return
	}
	dryRun := false
	if v := strings.TrimSpace(r.FormValue("dryRun")); v != "" {
		if dryRun, err = strconv.ParseBool(v); err != nil {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"dryRun": "true or false"}))
			return
		}
	}
	// productType, productTypeId and dryRun may travel as form fields or as query parameters.
	typ, typID := r.FormValue("productType"), r.FormValue("productTypeId")
	v, err := h.s.SellerSizeCharts.Import(r.Context(), callerFrom(r.Context()), port.UploadFile{Name: header.Filename, Data: data}, typ, typID, dryRun)
	h.reply(w, r, http.StatusOK, v, err)
}

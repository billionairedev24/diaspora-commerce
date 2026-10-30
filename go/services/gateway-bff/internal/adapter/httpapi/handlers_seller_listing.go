package httpapi

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// One-step product form (S307) and bulk upload (S308): contracts/openapi/gateway-bff-seller.yaml.

func (h *handlersImpl) sellerProduct(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerListing.Product(r.Context(), callerFrom(r.Context()), r.PathValue("productId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) updateProduct(w http.ResponseWriter, r *http.Request) {
	var b domain.ProductDraft
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerListing.Update(r.Context(), callerFrom(r.Context()), r.PathValue("productId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) productDrafts(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerListing.Drafts(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) productDraft(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerListing.Draft(r.Context(), callerFrom(r.Context()), r.PathValue("draftId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) createProductDraft(w http.ResponseWriter, r *http.Request) {
	var b domain.SaveProductDraftRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerListing.CreateDraft(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) saveProductDraft(w http.ResponseWriter, r *http.Request) {
	var b domain.SaveProductDraftRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerListing.SaveDraft(r.Context(), callerFrom(r.Context()), r.PathValue("draftId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) deleteProductDraft(w http.ResponseWriter, r *http.Request) {
	if err := h.s.SellerListing.DeleteDraft(r.Context(), callerFrom(r.Context()), r.PathValue("draftId")); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusNoContent)
}

func (h *handlersImpl) listingContext(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerListing.Context(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) writeListing(w http.ResponseWriter, r *http.Request) {
	var b domain.ListingWriterRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerListing.Write(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- bulk upload

// sendFile writes a generated file as an attachment (never cached: it holds the organisation's data).
func sendFile(w http.ResponseWriter, f domain.FileDownload) {
	w.Header().Set("Content-Type", f.ContentType)
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": f.FileName}))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(f.Content)
}

func (h *handlersImpl) importTemplate(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f, err := h.s.SellerImports.Template(r.Context(), callerFrom(r.Context()), q.Get("productType"), q.Get("categoryId"), q.Get("format"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	sendFile(w, f)
}

func (h *handlersImpl) importReport(w http.ResponseWriter, r *http.Request) {
	f, err := h.s.SellerImports.Report(r.Context(), callerFrom(r.Context()), r.PathValue("importId"), r.URL.Query().Get("format"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	sendFile(w, f)
}

func (h *handlersImpl) uploadImport(w http.ResponseWriter, r *http.Request) {
	tooLarge := domain.NewProblem(http.StatusRequestEntityTooLarge, domain.CodePayloadTooLarge, "File too large",
		"Upload files of at most 10 MB; split larger catalogs into several files.")
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
	v, err := h.s.SellerImports.Upload(r.Context(), callerFrom(r.Context()), port.UploadFile{Name: header.Filename, Data: data},
		r.FormValue("mode"), strings.TrimSpace(r.FormValue("productType")))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) imports(w http.ResponseWriter, r *http.Request) {
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
	v, err := h.s.SellerImports.List(r.Context(), callerFrom(r.Context()), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) importDetail(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerImports.Get(r.Context(), callerFrom(r.Context()), r.PathValue("importId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) importRows(w http.ResponseWriter, r *http.Request) {
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
	v, err := h.s.SellerImports.Rows(r.Context(), callerFrom(r.Context()), r.PathValue("importId"), q.Get("status"), q.Get("outcome"), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) startImport(w http.ResponseWriter, r *http.Request) {
	var b struct {
		ValidOnly bool `json:"validOnly"`
	}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerImports.Start(r.Context(), callerFrom(r.Context()), r.PathValue("importId"), b.ValidOnly)
	h.reply(w, r, http.StatusAccepted, v, err)
}

func (h *handlersImpl) imageLibrary(w http.ResponseWriter, r *http.Request) {
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
	v, err := h.s.SellerImports.ImageLibrary(r.Context(), callerFrom(r.Context()), q.Get("q"), q.Get("status"), q.Get("matched"), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) removeLibraryImage(w http.ResponseWriter, r *http.Request) {
	err := h.s.SellerImports.RemoveLibraryImage(r.Context(), callerFrom(r.Context()), r.PathValue("assetId"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) renameLibraryImage(w http.ResponseWriter, r *http.Request) {
	var b struct {
		FileName string `json:"fileName"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerImports.RenameLibraryImage(r.Context(), callerFrom(r.Context()), r.PathValue("assetId"), b.FileName)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) attachLibraryImages(w http.ResponseWriter, r *http.Request) {
	var b struct {
		SKUs []string `json:"skus"`
	}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerImports.AttachLibraryImages(r.Context(), callerFrom(r.Context()), b.SKUs)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) brands(w http.ResponseWriter, r *http.Request) {
	qs := r.URL.Query()
	v, err := h.s.SellerImports.Brands(r.Context(), callerFrom(r.Context()),
		domain.BrandQuery{Q: qs.Get("q"), Category: qs.Get("category"), ProductType: qs.Get("productType")})
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) createBrand(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Name        string `json:"name"`
		Category    string `json:"category"`
		ProductType string `json:"productType"`
	}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerImports.CreateBrand(r.Context(), callerFrom(r.Context()), b.Name,
		domain.BrandQuery{Category: b.Category, ProductType: b.ProductType})
	status := http.StatusOK
	if v.Created {
		status = http.StatusCreated
	}
	h.reply(w, r, status, v, err)
}

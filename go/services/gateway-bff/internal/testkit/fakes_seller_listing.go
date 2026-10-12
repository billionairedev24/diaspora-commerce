package testkit

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// Upload ids of the bulk upload fakes: ImportWithErrors has 2 rows in error, ImportClean none.
const (
	ImportWithErrors = "imp-errors"
	ImportClean      = "imp-clean"
)

func (u *Upstreams) catalogListingRoutes(mux *http.ServeMux) {
	full := func(id string) map[string]any {
		return map[string]any{"id": id, "atin": "ATADE00001", "ownerKind": "SELLER", "ownerOrgId": SellerOrgID,
			"categoryId": "cat-grains", "productType": "grocery", "title": "Ofada Rice, Destoned · 5 lb",
			"attributes":     map[string]string{"brand": "Ade's", "allergens": "None", "shelf": "12 months"},
			"images":         []map[string]any{{"url": "https://img.example/ofada.webp", "contentType": "image/webp", "width": 1600, "height": 1600}},
			"variationTheme": "Net weight",
			"variants": []map[string]any{{"id": "v1", "atin": "ATADEV0001", "sellerSku": "OFADA-5LB", "gtin": "4006381333931", "gtinExempt": false,
				"options": map[string]string{"Net weight": "5 lb"}, "priceMinor": 2499, "compareAtMinor": 2999, "currency": "USD", "quantity": 40, "images": []any{}}},
			"status": "REJECTED", "moderationNote": "Main image has a watermark", "version": 3, "preorderEligible": true,
			"listing": map[string]any{"description": "Stone-free ofada.", "condition": "NEW", "keywords": []string{"ofada"}, "fulfilment": "SELLER",
				"handlingDays": 2, "shipFrom": map[string]any{"locationId": "addr-1", "city": "Houston", "region": "TX", "country": "US"}},
			"updatedAt": "2026-10-09T10:00:00Z"}
	}
	mux.HandleFunc("GET /v1/products/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "prod-1" {
			problem(w, 404, "catalog.product-not-found", "Product not found", nil)
			return
		}
		writeJSON(w, 200, full("prod-1"))
	})
	mux.HandleFunc("PUT /v1/products/{id}", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if v, ok := b["version"].(float64); ok && v != 3 {
			problem(w, 409, "catalog.concurrent-update", "This product was changed since you opened it", nil)
			return
		}
		p := full(r.PathValue("id"))
		p["title"], p["status"], p["moderationNote"] = b["title"], "IN_REVIEW", nil
		u.mu.Lock()
		u.LastProductBody = b
		u.mu.Unlock()
		writeJSON(w, 200, p)
	})
	draftJSON := func(id string, d map[string]any) map[string]any {
		out := map[string]any{"id": id, "productId": d["productId"], "title": d["title"], "categoryPath": d["categoryPath"],
			"payload": d["payload"], "updatedAt": "2026-10-10T10:00:00Z", "version": d["version"]}
		return out
	}
	mux.HandleFunc("GET /v1/product-drafts", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		out := []any{}
		for id, d := range u.seller.drafts {
			out = append(out, draftJSON(id, d))
		}
		writeJSON(w, 200, out)
	})
	mux.HandleFunc("POST /v1/product-drafts", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if pid, _ := b["productId"].(string); pid != "" && pid != "prod-1" {
			problem(w, 404, "catalog.product-not-found", "Product not found", nil)
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		id := fmt.Sprintf("draft-%d", len(u.seller.drafts)+1)
		b["version"] = float64(0)
		u.seller.drafts[id] = b
		writeJSON(w, 201, draftJSON(id, b))
	})
	mux.HandleFunc("GET /v1/product-drafts/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		d, ok := u.seller.drafts[r.PathValue("id")]
		if !ok {
			problem(w, 404, "catalog.draft-not-found", "Draft not found", nil)
			return
		}
		writeJSON(w, 200, draftJSON(r.PathValue("id"), d))
	})
	mux.HandleFunc("PUT /v1/product-drafts/{id}", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		d, ok := u.seller.drafts[r.PathValue("id")]
		if !ok {
			problem(w, 404, "catalog.draft-not-found", "Draft not found", nil)
			return
		}
		if b["version"] != d["version"] {
			problem(w, 409, "catalog.draft-changed", "This draft was saved from another tab or device", nil)
			return
		}
		b["productId"] = d["productId"]
		b["version"] = d["version"].(float64) + 1
		u.seller.drafts[r.PathValue("id")] = b
		writeJSON(w, 200, draftJSON(r.PathValue("id"), b))
	})
	mux.HandleFunc("DELETE /v1/product-drafts/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		if _, ok := u.seller.drafts[r.PathValue("id")]; !ok {
			problem(w, 404, "catalog.draft-not-found", "Draft not found", nil)
			return
		}
		delete(u.seller.drafts, r.PathValue("id"))
		w.WriteHeader(204)
	})

	// ---- bulk upload
	header := []string{"Parent SKU", "SKU *", "Title *", "Price (USD) *", "Quantity *", "Barcode (GTIN/UPC/EAN)"}
	upload := func(id, status string, errors int) map[string]any {
		return map[string]any{"id": id, "reference": "BU-000042", "fileName": "pantry.xlsx", "format": "XLSX", "mode": "CREATE",
			"productType": "grocery", "status": status, "validOnly": status != "VALIDATED" && errors > 0, "totalRows": 5, "validRows": 3 - min(errors, 1),
			"warningRows": min(errors, 1), "errorRows": errors, "productCount": 4, "processedRows": 0, "createdProducts": 0,
			"updatedProducts": 0, "failedRows": 0, "header": header, "fileWarnings": []string{"Ignored column not in the template: Supplier"},
			"uploadedBy": "user-seller", "uploadedByName": "Ade Okafor", "notifySeller": false, "createdAt": "2026-10-10T10:00:00Z"}
	}
	mux.HandleFunc("GET /v1/imports/template", func(w http.ResponseWriter, r *http.Request) {
		if pt := r.URL.Query().Get("productType"); pt != "" && pt != "grocery" {
			problem(w, 404, "catalog.product-type-not-found", "Unknown product type", nil)
			return
		}
		if r.URL.Query().Get("format") == "csv" {
			w.Header().Set("Content-Type", "text/csv; charset=utf-8")
			w.Header().Set("Content-Disposition", `attachment; filename="afrotransact-products-grocery.csv"`)
			_, _ = io.WriteString(w, "# One row per item\r\nParent SKU,SKU *\r\n")
			return
		}
		w.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
		w.Header().Set("Content-Disposition", `attachment; filename="afrotransact-products-grocery.xlsx"`)
		_, _ = w.Write([]byte("PK\x03\x04 fake workbook"))
	})
	mux.HandleFunc("POST /v1/imports", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			problem(w, 400, "catalog.invalid-input", "multipart expected", nil)
			return
		}
		f, h, err := r.FormFile("file")
		if err != nil {
			problem(w, 400, "catalog.invalid-input", "file part expected", nil)
			return
		}
		data, _ := io.ReadAll(f)
		u.mu.Lock()
		u.LastUpload = map[string]string{"name": h.Filename, "contentType": h.Header.Get("Content-Type"), "mode": r.URL.Query().Get("mode"),
			"productType": r.URL.Query().Get("productType"), "uploadedByName": r.URL.Query().Get("uploadedByName"), "body": string(data)}
		u.mu.Unlock()
		if strings.Contains(string(data), "NO-HEADER") {
			problem(w, 422, "catalog.upload-rejected", "Missing required column: SKU *", map[string]any{"fieldErrors": map[string]string{"file": "Missing required column: SKU *"}})
			return
		}
		writeJSON(w, 201, upload(ImportWithErrors, "VALIDATED", 2))
	})
	mux.HandleFunc("GET /v1/imports", func(w http.ResponseWriter, _ *http.Request) {
		done := upload(ImportClean, "COMPLETED", 0)
		done["processedRows"], done["createdProducts"], done["startedAt"], done["finishedAt"] = 5, 4, "2026-10-09T10:00:00Z", "2026-10-09T10:01:00Z"
		writeJSON(w, 200, map[string]any{"items": []any{upload(ImportWithErrors, "VALIDATED", 2), done}, "total": 2, "page": 0, "size": 25})
	})
	mux.HandleFunc("GET /v1/imports/{id}", func(w http.ResponseWriter, r *http.Request) {
		switch r.PathValue("id") {
		case ImportWithErrors:
			writeJSON(w, 200, upload(ImportWithErrors, "VALIDATED", 2))
		case ImportClean:
			writeJSON(w, 200, upload(ImportClean, "VALIDATED", 0))
		default:
			problem(w, 404, "catalog.import-not-found", "Upload not found", nil)
		}
	})
	mux.HandleFunc("GET /v1/imports/{id}/rows", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != ImportWithErrors {
			problem(w, 404, "catalog.import-not-found", "Upload not found", nil)
			return
		}
		price := "price"
		rows := []map[string]any{
			{"rowNumber": 2, "groupKey": "OFADA", "sku": "OFADA-5LB", "status": "VALID", "outcome": "PENDING", "messages": []any{},
				"cells": []string{"OFADA", "OFADA-5LB", "Ofada Rice, Destoned", "24.99", "40", "4006381333931"}},
			{"rowNumber": 3, "groupKey": "OFADA", "sku": "OFADA-10LB", "status": "ERROR", "outcome": "PENDING",
				"messages": []map[string]any{{"column": price, "columnIndex": 3, "severity": "ERROR", "message": "A price above zero in US dollars, e.g. 24.99"}},
				"cells":    []string{"OFADA", "OFADA-10LB", "", "abc", "12", ""}},
		}
		if r.URL.Query().Get("status") == "ERROR" {
			rows = rows[1:]
		}
		writeJSON(w, 200, map[string]any{"items": rows, "total": len(rows), "page": 0, "size": 50})
	})
	mux.HandleFunc("GET /v1/imports/{id}/report", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != ImportWithErrors {
			problem(w, 404, "catalog.import-not-found", "Upload not found", nil)
			return
		}
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="pantry-report.csv"`)
		_, _ = io.WriteString(w, "SKU *,Price (USD) *,Row,Row status,Result,Errors\r\nOFADA-10LB,'=1+1,3,Error,,Price: required\r\n")
	})
	mux.HandleFunc("POST /v1/imports/{id}/start", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			ValidOnly bool `json:"validOnly"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if r.PathValue("id") == ImportWithErrors && !b.ValidOnly {
			problem(w, 409, "catalog.import-has-errors", "2 row(s) have errors: fix them and upload the file again, or import the valid rows only", nil)
			return
		}
		writeJSON(w, 202, upload(r.PathValue("id"), "QUEUED", 2))
	})

	// ---- ai-gateway listing writer
	mux.HandleFunc("POST /v1/ai/listing-writer", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 200, map[string]any{"title": fmt.Sprint(b["title"], " — stone-free, 5 lb"), "description": "Nutty, aromatic ofada rice.",
			"bullets": []string{"Stone-free", "Hand-sorted"}, "status": "DRAFT", "autoPublished": false, "model": "fake/writer"})
	})
}

// SetSellerPlan changes the seller organisation's current plan (the AI listing writer depends on it).
func (u *Upstreams) SetSellerPlan(code string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.seller.plan = code
}

package testkit

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// Stock file ids of the inventory fake: StockImportWithErrors has a row below the reserved units, StockImportOther belongs to
// another organisation (inventory answers 404 for it, exactly as the real service does).
const (
	StockImportWithErrors = "11111111-1111-4111-8111-111111111111"
	StockImportOther      = "22222222-2222-4222-8222-222222222222"
)

// stockImportRoutes is inventory's /v1/stock/imports (S395).
func (u *Upstreams) stockImportRoutes(mux *http.ServeMux) {
	header := []string{"Seller SKU", "Location", "On hand", "Lot", "Expiry"}
	imp := func(id, status string, errors int) map[string]any {
		return map[string]any{"id": id, "reference": "SI-000007", "fileName": "count.csv", "fileSha256": "cd34", "mode": "ABSOLUTE", "status": status,
			"ownerOrg": SellerOrgID, "totalRows": 3, "validRows": 3 - errors, "warningRows": 0, "errorRows": errors, "processedRows": 0, "appliedRows": 0,
			"unchangedRows": 0, "failedRows": 0, "header": header, "uploadedBy": "user-seller", "createdAt": "2026-10-10T10:00:00Z"}
	}
	mux.HandleFunc("GET /v1/stock/imports/template", func(w http.ResponseWriter, r *http.Request) {
		if m := r.URL.Query().Get("mode"); m != "ABSOLUTE" && m != "DELTA" {
			problem(w, 400, "invalid-input", "mode", nil)
			return
		}
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="stock-`+strings.ToLower(r.URL.Query().Get("mode"))+`.csv"`)
		_, _ = io.WriteString(w, "Seller SKU,Location,On hand,Lot,Expiry\r\n")
	})
	mux.HandleFunc("POST /v1/stock/imports", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			problem(w, 400, "invalid-input", "multipart expected", nil)
			return
		}
		f, h, err := r.FormFile("file")
		if err != nil {
			problem(w, 400, "invalid-input", "file part expected", nil)
			return
		}
		data, _ := io.ReadAll(f)
		u.mu.Lock()
		u.LastStockUpload = map[string]string{"rawQuery": r.URL.RawQuery, "authorization": r.Header.Get("Authorization"), "name": h.Filename,
			"contentType": h.Header.Get("Content-Type"), "mode": r.URL.Query().Get("mode"), "ownerOrg": r.URL.Query().Get("ownerOrg"), "body": string(data)}
		u.mu.Unlock()
		switch {
		case strings.Contains(string(data), "NO-HEADER"):
			problem(w, 422, "inventory.upload-rejected", "The file has no Seller SKU column. Download the template and keep its header row", nil)
		case strings.Contains(string(data), "RETAIL"):
			problem(w, 403, "inventory.retail-stock", "AfroTransact Retail's own stock is not changed by a file", nil)
		case strings.Contains(string(data), "RUNNING"):
			problem(w, 409, "inventory.import-already-running", "A stock file is already running", nil)
		default:
			writeJSON(w, 201, imp(StockImportWithErrors, "VALIDATED", 1))
		}
	})
	mux.HandleFunc("GET /v1/stock/imports", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		u.LastStockUpload = map[string]string{"listQuery": r.URL.RawQuery}
		u.mu.Unlock()
		writeJSON(w, 200, map[string]any{"items": []any{imp(StockImportWithErrors, "VALIDATED", 1)}, "total": 1, "page": 0, "size": 25})
	})
	mux.HandleFunc("GET /v1/stock/imports/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != StockImportWithErrors {
			problem(w, 404, "inventory.import-not-found", "Stock file not found", nil)
			return
		}
		writeJSON(w, 200, imp(StockImportWithErrors, "VALIDATED", 1))
	})
	mux.HandleFunc("GET /v1/stock/imports/{id}/rows", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != StockImportWithErrors {
			problem(w, 404, "inventory.import-not-found", "Stock file not found", nil)
			return
		}
		col := "On hand"
		rows := []map[string]any{
			{"rowNumber": 2, "sku": "OFADA-5LB", "location": "HOU-FC", "quantity": 120, "status": "VALID", "outcome": "PENDING", "before": 100, "after": 120,
				"messages": []any{}, "cells": []string{"OFADA-5LB", "HOU-FC", "120"}},
			{"rowNumber": 3, "sku": "OFADA-10LB", "location": "ATL-FC", "quantity": 1, "status": "ERROR", "outcome": "PENDING",
				"messages": []map[string]any{{"column": col, "severity": "ERROR", "message": "2 units are reserved for orders, so the count cannot be lower than 2"}},
				"cells":    []string{"OFADA-10LB", "ATL-FC", "1"}},
		}
		if r.URL.Query().Get("status") == "ERROR" {
			rows = rows[1:]
		}
		writeJSON(w, 200, map[string]any{"items": rows, "total": len(rows), "page": 0, "size": 50})
	})
	mux.HandleFunc("GET /v1/stock/imports/{id}/report", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != StockImportWithErrors {
			problem(w, 404, "inventory.import-not-found", "Stock file not found", nil)
			return
		}
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="count-report.csv"`)
		_, _ = io.WriteString(w, "Seller SKU,Location,On hand,Row,Result,Severity,Column,Message,Before,After\r\n")
	})
	mux.HandleFunc("POST /v1/stock/imports/{id}/start", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			ValidOnly bool `json:"validOnly"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if r.PathValue("id") != StockImportWithErrors {
			problem(w, 404, "inventory.import-not-found", "Stock file not found", nil)
			return
		}
		if !b.ValidOnly {
			problem(w, 409, "inventory.import-has-errors", "1 row(s) have errors: fix them and upload the file again, or import the valid rows only", nil)
			return
		}
		writeJSON(w, 202, imp(StockImportWithErrors, "QUEUED", 1))
	})
}

package testkit

import (
	"fmt"
	"io"
	"net/http"
	"strings"
)

// Product tax fixtures: the fake catalog answers the tax publish gate and keeps the proof files in memory (catalog
// stores them per variant; the answer carries a /v1/tax-evidence/{id}/file link that the BFF rewrites).
const (
	// TaxProductID is a product of the seller organisation whose single variant lacks its net weight.
	TaxProductID = "7a5e0000-0000-4000-8000-000000000001"
	// TaxReadyProductID is a product whose variants are all READY.
	TaxReadyProductID = "7a5e0000-0000-4000-8000-000000000002"
	// TaxVariantATIN is the variant of TaxProductID.
	TaxVariantATIN = "ATTAXVAR001"
)

type fakeEvidence struct {
	id, atin, factID, name, contentType string
	version                             int
	data                                []byte
}

func (u *Upstreams) productTaxRoutes(mux *http.ServeMux) {
	var files []*fakeEvidence
	render := func(e *fakeEvidence) map[string]any {
		return map[string]any{"id": e.id, "atin": e.atin, "factId": e.factID, "version": e.version, "fileName": e.name,
			"contentType": e.contentType, "bytes": len(e.data), "url": "/v1/tax-evidence/" + e.id + "/file",
			"uploadedBy": "user-seller", "uploadedAt": "2026-11-10T09:00:00Z"}
	}
	mux.HandleFunc("GET /v1/products/{id}/tax-readiness", func(w http.ResponseWriter, r *http.Request) {
		switch r.PathValue("id") {
		case TaxProductID:
			writeJSON(w, 200, map[string]any{"productId": TaxProductID, "status": "MISSING_FACTS", "categoryCode": "nuts-seeds",
				"categoryStatus": "ACTIVE", "categoryMessage": nil, "ruleVersion": "tx-2026.11.09-draft.1",
				"variants": []any{map[string]any{"atin": TaxVariantATIN, "sellerSku": "ACME-ATTAXVAR001", "status": "MISSING_FACTS",
					"missingTaxFacts": []any{map[string]any{"factId": "net_weight_oz", "label": "Net weight (oz)",
						"message": "Net weight (oz) is needed. Check the package label."}}, "reason": nil}}})
		case TaxReadyProductID:
			writeJSON(w, 200, map[string]any{"productId": TaxReadyProductID, "status": "READY", "categoryCode": "pantry-staples",
				"categoryStatus": "ACTIVE", "categoryMessage": nil, "ruleVersion": "tx-2026.11.09-draft.1",
				"variants": []any{map[string]any{"atin": "ATTAXVAR002", "sellerSku": "ACME-ATTAXVAR002", "status": "READY",
					"missingTaxFacts": []any{}, "reason": nil}}})
		default:
			problem(w, 404, "catalog.not-found", "No such product", nil)
		}
	})
	mux.HandleFunc("POST /v1/variants/{atin}/tax-evidence", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			problem(w, 422, "catalog.tax-evidence-invalid", "Send a multipart form", map[string]any{"fieldErrors": map[string]string{"file": "Choose a file"}})
			return
		}
		fact := r.FormValue("factId")
		if fact != "supplement_facts_proof" {
			problem(w, 422, "catalog.tax-evidence-invalid", "This question does not take a file",
				map[string]any{"fieldErrors": map[string]string{"factId": "This question does not take a file"}})
			return
		}
		f, h, err := r.FormFile("file")
		if err != nil {
			problem(w, 422, "catalog.tax-evidence-invalid", "Choose a file", nil)
			return
		}
		defer func() { _ = f.Close() }()
		data, _ := io.ReadAll(f)
		u.mu.Lock()
		defer u.mu.Unlock()
		version := 1
		for _, e := range files {
			if e.atin == r.PathValue("atin") && e.factID == fact {
				version++
			}
		}
		e := &fakeEvidence{id: fmt.Sprintf("e71d0000-0000-4000-8000-%012d", len(files)+1), atin: r.PathValue("atin"), factID: fact,
			name: h.Filename, contentType: h.Header.Get("Content-Type"), version: version, data: data}
		files = append(files, e)
		writeJSON(w, 200, render(e))
	})
	mux.HandleFunc("GET /v1/variants/{atin}/tax-evidence", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		out := []any{}
		for _, e := range files {
			if e.atin == r.PathValue("atin") {
				out = append(out, render(e))
			}
		}
		writeJSON(w, 200, out)
	})
	mux.HandleFunc("GET /v1/tax-evidence/{id}/file", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		for _, e := range files {
			if e.id == r.PathValue("id") {
				w.Header().Set("Content-Type", e.contentType)
				w.Header().Set("Content-Disposition", `inline; filename="`+strings.ReplaceAll(e.name, `"`, "")+`"`)
				_, _ = w.Write(e.data)
				return
			}
		}
		problem(w, 404, "catalog.not-found", "No such file", nil)
	})
}

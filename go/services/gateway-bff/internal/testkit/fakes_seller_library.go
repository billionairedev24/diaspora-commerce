package testkit

import (
	"encoding/json"
	"net/http"
)

// libraryRoutes fakes the upstreams behind a seller's plan capabilities: offers' seller coupons, media's image library,
// catalog's brand suggestions and image matching, and carrier-gateway's label document.
func (u *Upstreams) libraryRoutes(mux *http.ServeMux) {
	coupon := func(status string) map[string]any {
		return map[string]any{"id": "cp-1", "code": "WELCOME10", "description": "10% off your first order", "type": "PERCENT", "value": 10, "currency": "USD",
			"scope": "SELLER", "scopeRef": SellerOrgID, "minSpendMinor": 2000, "status": status, "funding": "SELLER", "fundedByOrg": SellerOrgID, "version": 1}
	}
	mux.HandleFunc("GET /v1/coupons", func(w http.ResponseWriter, _ *http.Request) {
		expired := coupon("EXPIRED")
		expired["id"], expired["code"] = "cp-0", "OLD5"
		writeJSON(w, 200, []map[string]any{coupon("ACTIVE"), expired})
	})
	mux.HandleFunc("POST /v1/coupons", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		c := coupon("ACTIVE")
		if code, ok := b["code"].(string); ok {
			c["code"] = code
		}
		writeJSON(w, 201, c)
	})
	mux.HandleFunc("POST /v1/coupons/{id}/status", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Status string `json:"status"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		c := coupon(b.Status)
		c["id"] = r.PathValue("id")
		writeJSON(w, 200, c)
	})

	image := func(id, name string) map[string]any {
		return map[string]any{"assetId": id, "fileName": name, "sku": "OFADA-5LB", "slot": "MAIN", "status": "READY", "url": "https://media.example/" + id + ".jpg",
			"width": 1200, "height": 900, "bytes": 184320, "flags": []string{}, "createdAt": "2026-10-09T15:00:00Z"}
	}
	mux.HandleFunc("GET /v1/library", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"items": []map[string]any{image("img-1", "OFADA-5LB-main.jpg")}, "total": 1, "page": 0, "size": 50})
	})
	mux.HandleFunc("PATCH /v1/library/{id}", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			FileName string `json:"fileName"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 200, image(r.PathValue("id"), b.FileName))
	})
	mux.HandleFunc("DELETE /v1/library/{id}", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(204)
	})
	mux.HandleFunc("POST /v1/image-library/usage", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"usage": []map[string]any{{"url": "https://media.example/img-1.jpg", "skus": []string{"OFADA-5LB"}}}, "knownSkus": []string{"OFADA-5LB"}})
	})
	mux.HandleFunc("POST /v1/image-library/attach", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"productsUpdated": 1, "imagesAttached": 1, "skipped": []map[string]any{{"sku": "NOPE-1", "reason": "No such listing"}}})
	})
	mux.HandleFunc("GET /v1/brands", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"brands": []map[string]any{{"name": "Mama Tee", "source": "OWN", "products": 4}, {"name": "Tilda", "source": "CATALOG", "products": 31}}})
	})
	mux.HandleFunc("GET /v1/labels/by-tracking/{carrier}/{tracking}/document", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/pdf")
		_, _ = w.Write([]byte("%PDF-1.4 fake label"))
	})
}

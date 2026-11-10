package httpapi_test

import (
	"bytes"
	"mime/multipart"
	"net/textproto"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

func evidenceBody(t *testing.T, factID, name, contentType string, data []byte) (string, string) {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	if factID != "" {
		_ = w.WriteField("factId", factID)
	}
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", `form-data; name="file"; filename="`+name+`"`)
	h.Set("Content-Type", contentType)
	part, err := w.CreatePart(h)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write(data)
	_ = w.Close()
	return buf.String(), w.FormDataContentType()
}

// exerciseSellerProductTax drives the Seller Center readiness block and the proof upload through the real routes
// (the caller validates every answer against gateway-bff-seller.yaml): readiness is catalog's JSON, the evidence link
// is the BFF's own, file types and sizes are checked here.
func exerciseSellerProductTax(t *testing.T, b *browser) {
	t.Helper()
	// The gate: missing facts come in plain words; a READY product says so; unknown or malformed ids are 404.
	r := b.do("GET", "/v1/seller/products/"+testkit.TaxProductID+"/tax-readiness", nil)
	if r.status != 200 || r.json(t)["status"] != "MISSING_FACTS" || !strings.Contains(string(r.body), "Net weight (oz) is needed") {
		t.Fatalf("readiness = %d %s", r.status, r.body)
	}
	if r := b.do("GET", "/v1/seller/products/"+testkit.TaxReadyProductID+"/tax-readiness", nil); r.status != 200 || r.json(t)["status"] != "READY" {
		t.Fatalf("ready = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/products/not-a-uuid/tax-readiness", nil), 404)
	mustStatus(t, b.do("GET", "/v1/seller/products/7a5e0000-0000-4000-8000-0000000000ff/tax-readiness", nil), 404)

	// Proof upload: PDF, JPEG, PNG only; a question that takes no file is catalog's 422; the link is the BFF's own.
	post := func(path, fact, name, ct string, data []byte) resp {
		body, mt := evidenceBody(t, fact, name, ct, data)
		b.headers["Content-Type"] = mt
		defer delete(b.headers, "Content-Type")
		return b.do("POST", path, body)
	}
	path := "/v1/seller/variants/" + testkit.TaxVariantATIN + "/tax-evidence"
	up := post(path, "supplement_facts_proof", "label.pdf", "application/pdf", []byte("%PDF-1.4 label"))
	if up.status != 200 || up.json(t)["version"] != float64(1) || !strings.HasPrefix(up.json(t)["url"].(string), "/v1/seller/tax-evidence/") {
		t.Fatalf("upload = %d %s", up.status, up.body)
	}
	if again := post(path, "supplement_facts_proof", "label2.png", "image/png", []byte("png")); again.status != 200 || again.json(t)["version"] != float64(2) {
		t.Fatalf("second version = %d %s", again.status, again.body)
	}
	mustStatus(t, post(path, "supplement_facts_proof", "label.gif", "image/gif", []byte("gif")), 422)
	mustStatus(t, post(path, "supplement_facts_proof", "empty.pdf", "application/pdf", nil), 422)
	mustStatus(t, post(path, "", "label.pdf", "application/pdf", []byte("x")), 422)
	mustStatus(t, post(path, "net_weight_oz", "label.pdf", "application/pdf", []byte("x")), 422)
	mustStatus(t, post(path, "supplement_facts_proof", "big.pdf", "application/pdf", make([]byte, 10<<20+1)), 413)
	list := b.do("GET", path, nil)
	if list.status != 200 || strings.Count(string(list.body), `"factId"`) != 2 {
		t.Fatalf("list = %d %s", list.status, list.body)
	}
	file := b.do("GET", up.json(t)["url"].(string), nil)
	if file.status != 200 || !strings.Contains(string(file.body), "%PDF") {
		t.Fatalf("file = %d %s", file.status, file.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/tax-evidence/not-an-id/file", nil), 404)
}

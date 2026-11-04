package upstreams

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// mediaStub is media-service for the export purpose: upload request, direct storage PUT, complete, signed URL.
type mediaStub struct {
	srv       *httptest.Server
	storage   *httptest.Server
	uploadReq map[string]any
	stored    string
	storedCT  string
	completed string
	bearers   []string
	refuse    bool
}

func newMediaStub(t *testing.T) *mediaStub {
	t.Helper()
	m := &mediaStub{}
	m.storage = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		m.stored, m.storedCT = string(b), r.Header.Get("Content-Type")
		w.WriteHeader(http.StatusOK)
	}))
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/uploads", func(w http.ResponseWriter, r *http.Request) {
		m.bearers = append(m.bearers, r.Header.Get("Authorization"))
		if m.refuse {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusUnprocessableEntity)
			_, _ = w.Write([]byte(`{"title":"Rejected","status":422}`))
			return
		}
		_ = json.NewDecoder(r.Body).Decode(&m.uploadReq)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"asset":  map[string]any{"id": "ast_exp1"},
			"upload": map[string]any{"url": m.storage.URL + "/put", "method": "PUT", "headers": map[string]string{"x-amz-acl": "private"}},
		})
	})
	mux.HandleFunc("POST /v1/assets/{id}/complete", func(w http.ResponseWriter, r *http.Request) {
		m.completed = r.PathValue("id")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"ast_exp1","status":"READY"}`))
	})
	mux.HandleFunc("GET /v1/assets/{id}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"id": r.PathValue("id"), "url": "https://files.example/" + r.PathValue("id") + "?sig=1"})
	})
	m.srv = httptest.NewServer(mux)
	t.Cleanup(func() { m.srv.Close(); m.storage.Close() })
	return m
}

func (m *mediaStub) client() httpclient.Client {
	return httpclient.New(httpclient.Options{Name: "media", BaseURL: m.srv.URL, Timeout: 2 * time.Second})
}

func TestMediaExportsPutAndSign(t *testing.T) {
	m := newMediaStub(t)
	store := NewMediaExports(m.client(), nil)
	csv := "id,total\n1,10\n"
	ref, err := store.Put(context.Background(), "staff-token", port.ExportFile{
		FileName: "orders.csv", ContentType: "text/csv", Size: int64(len(csv)), Body: strings.NewReader(csv)})
	if err != nil || ref != "ast_exp1" {
		t.Fatalf("Put = %q, %v", ref, err)
	}
	if m.uploadReq["purpose"] != "export" || m.uploadReq["fileName"] != "orders.csv" || m.uploadReq["contentType"] != "text/csv" {
		t.Fatalf("upload request = %v", m.uploadReq)
	}
	if m.stored != csv || m.storedCT != "text/csv" || m.completed != "ast_exp1" {
		t.Fatalf("stored %q (%s), completed %q", m.stored, m.storedCT, m.completed)
	}
	if len(m.bearers) != 1 || m.bearers[0] != "Bearer staff-token" {
		t.Fatalf("bearers = %v", m.bearers)
	}
	url, err := store.SignedURL(context.Background(), "staff-token", ref, "orders.csv", time.Now().Add(time.Hour))
	if err != nil || url != "https://files.example/ast_exp1?sig=1" {
		t.Fatalf("SignedURL = %q, %v", url, err)
	}
}

func TestMediaExportsRefusedIsUnavailable(t *testing.T) {
	m := newMediaStub(t)
	m.refuse = true
	_, err := NewMediaExports(m.client(), nil).Put(context.Background(), "t", port.ExportFile{
		FileName: "x.csv", ContentType: "text/csv", Size: 1, Body: strings.NewReader("x")})
	if !errors.Is(err, domain.ErrUpstreamUnavailable) {
		t.Fatalf("err = %v, want ErrUpstreamUnavailable", err)
	}
}

func TestMediaExportsStorageFailure(t *testing.T) {
	m := newMediaStub(t)
	m.storage.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusForbidden) })
	_, err := NewMediaExports(m.client(), nil).Put(context.Background(), "t", port.ExportFile{
		FileName: "x.csv", ContentType: "text/csv", Size: 1, Body: strings.NewReader("x")})
	if !errors.Is(err, domain.ErrUpstreamUnavailable) || m.completed != "" {
		t.Fatalf("err = %v, completed %q", err, m.completed)
	}
}

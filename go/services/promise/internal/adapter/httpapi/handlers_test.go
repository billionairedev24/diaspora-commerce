package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

type fakeService struct{ got app.BatchRequest }

func (f *fakeService) Promise(_ context.Context, r app.BatchRequest) (app.BatchResponse, error) {
	f.got = r
	if r.DestinationZIP == "bad" {
		return app.BatchResponse{}, domain.ErrInvalid
	}
	d := domain.Day{Year: 2026, Month: 10, Day: 10}
	return app.BatchResponse{DestinationZIP: r.DestinationZIP, Promises: []domain.Promise{{ID: "prm_aaaaaaaaaaaaaaaaaaaaaaaa", OfferID: "o1",
		Available: true, EarliestDate: &d, LatestDate: &d, Label: "Tomorrow, Oct 10"}}}, nil
}

func (f *fakeService) Get(_ context.Context, id string) (domain.Promise, error) {
	if id == "prm_aaaaaaaaaaaaaaaaaaaaaaaa" {
		return domain.Promise{ID: id, Label: "Tomorrow, Oct 10"}, nil
	}
	return domain.Promise{}, domain.ErrNotFound
}

func TestPromiseRoutes(t *testing.T) {
	svc := &fakeService{}
	mux := http.NewServeMux()
	httpapi.New(svc).Register(mux, auth.Middleware(nil, true))
	srv := httptest.NewServer(mux)
	defer srv.Close()
	do := func(method, path, body string) (int, map[string]any) {
		req, _ := http.NewRequestWithContext(context.Background(), method, srv.URL+path, strings.NewReader(body))
		res, err := srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = res.Body.Close() }()
		var out map[string]any
		_ = json.NewDecoder(res.Body).Decode(&out)
		return res.StatusCode, out
	}
	code, out := do("POST", "/v1/promises", `{"destinationZip":"77002","offers":[{"offerId":"o1","origin":{"type":"FC","id":"fc_houston_tx"},
		"method":{"type":"CARRIER","serviceLevel":"GROUND"}}]}`)
	promises, _ := out["promises"].([]any)
	if code != 200 || len(promises) != 1 || promises[0].(map[string]any)["earliestDate"] != "2026-10-10" ||
		svc.got.Offers[0].Method.ServiceLevel != domain.LevelGround {
		t.Fatalf("%d %v", code, out)
	}
	if code, out := do("POST", "/v1/promises", `{"destinationZip":"bad","offers":[]}`); code != 400 || out["code"] != "promise.invalid-request" {
		t.Fatalf("invalid: %d %v", code, out)
	}
	if code, _ := do("POST", "/v1/promises", `{`); code != 400 {
		t.Fatalf("malformed json: %d", code)
	}
	if code, out := do("GET", "/v1/promises/prm_aaaaaaaaaaaaaaaaaaaaaaaa", ""); code != 200 || out["label"] != "Tomorrow, Oct 10" {
		t.Fatalf("get: %d %v", code, out)
	}
	if code, _ := do("GET", "/v1/promises/prm_bbbbbbbbbbbbbbbbbbbbbbbb", ""); code != 404 {
		t.Fatalf("unknown: %d", code)
	}
	if code, _ := do("GET", "/v1/promises/../../etc", ""); code != 404 {
		t.Fatalf("malformed id: %d", code)
	}
}

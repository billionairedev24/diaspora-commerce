package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type fakeSearcher struct{ got domain.Query }

func (f *fakeSearcher) Search(_ context.Context, q domain.Query) (domain.Page, error) {
	f.got = q
	if q.Market == "gh" {
		return domain.Page{}, domain.ErrUnknownMarket
	}
	return domain.Page{Hits: []domain.Hit{}, SearchID: "srch-1"}, nil
}

type fakeClicks struct{ got domain.Click }

func (f *fakeClicks) Record(_ context.Context, c domain.Click) (domain.ClickResult, error) {
	f.got = c
	if c.AdToken == "bad" {
		return domain.ClickResult{}, app.ErrInvalidAdToken
	}
	return domain.ClickResult{Recorded: true}, nil
}

type fakeReporter struct{}

func (fakeReporter) TopQueries(context.Context, domain.AnalyticsRange) (domain.QueryReport, error) {
	return domain.QueryReport{Market: "us", Items: []domain.QueryStat{{Query: "egusi", Searches: 3}}}, nil
}

func (fakeReporter) ZeroResultQueries(context.Context, domain.AnalyticsRange) (domain.QueryReport, error) {
	return domain.QueryReport{Items: []domain.QueryStat{}}, nil
}

func (fakeReporter) ClickThrough(context.Context, domain.AnalyticsRange) (domain.CTRReport, error) {
	return domain.CTRReport{Days: []domain.DailyCTR{}}, nil
}

// as authenticates every request as actor (nil: anonymous).
func as(actor *auth.Actor) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if actor != nil {
				r = r.WithContext(auth.WithActor(r.Context(), *actor))
			}
			next.ServeHTTP(w, r)
		})
	}
}

func server(actor *auth.Actor) (*http.ServeMux, *fakeSearcher, *fakeClicks) {
	s, c := &fakeSearcher{}, &fakeClicks{}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Deps{Searcher: s, Clicks: c, Analytics: fakeReporter{}}).Register(mux, as(actor))
	return mux, s, c
}

func do(mux http.Handler, method, target, body string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), method, target, strings.NewReader(body)))
	return rec
}

func code(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var p struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &p)
	return p.Code
}

func TestSearchParamsAdsTrackAndShopper(t *testing.T) {
	mux, s, _ := server(&auth.Actor{Subject: "cus_1", Type: "CUSTOMER"})
	rec := do(mux, http.MethodGet, "/v1/search?q=egusi&ads=true", "")
	if rec.Code != http.StatusOK || !s.got.Ads || !s.got.Track || s.got.CustomerID != "cus_1" ||
		!strings.Contains(rec.Header().Get("Cache-Control"), "no-store") {
		t.Fatalf("%d %+v %s", rec.Code, s.got, rec.Header())
	}
	do(mux, http.MethodGet, "/v1/search?q=rice&track=false", "")
	if s.got.Track || s.got.Ads {
		t.Fatalf("rails: %+v", s.got)
	}
	if rec := do(mux, http.MethodGet, "/v1/search?market=gh", ""); rec.Code != http.StatusBadRequest ||
		code(t, rec) != "search.unknown-market" {
		t.Fatalf("unknown market: %d %s", rec.Code, rec.Body)
	}
}

func TestClicks(t *testing.T) {
	bff := &auth.Actor{Subject: "gateway-bff", Type: "SERVICE"}
	mux, _, clicks := server(bff)
	rec := do(mux, http.MethodPost, "/v1/search/clicks",
		`{"searchId":"srch-1","q":"egusi","atin":"AT00000001","position":2,"customerId":"cus_9"}`)
	if rec.Code != http.StatusAccepted || clicks.got.CustomerID != "cus_9" || clicks.got.Position != 2 {
		t.Fatalf("%d %+v", rec.Code, clicks.got)
	}
	if rec := do(mux, http.MethodPost, "/v1/search/clicks", `{"searchId":"srch-1","atin":"AT00000001","position":1,"adToken":"bad"}`); rec.Code != http.StatusBadRequest || code(t, rec) != "search.invalid-ad-token" {
		t.Fatalf("bad token: %d %s", rec.Code, rec.Body)
	}
	// Anonymous callers cannot attribute clicks to a customer.
	anon, _, anonClicks := server(nil)
	do(anon, http.MethodPost, "/v1/search/clicks", `{"searchId":"srch-1","atin":"AT00000001","position":1,"customerId":"cus_9"}`)
	if anonClicks.got.CustomerID != "" {
		t.Fatal("customerId accepted from an anonymous caller")
	}
	if rec := do(anon, http.MethodPost, "/v1/search/clicks", "not json"); rec.Code != http.StatusBadRequest {
		t.Fatalf("malformed: %d", rec.Code)
	}
}

func TestAnalyticsNeedsPermission(t *testing.T) {
	anon, _, _ := server(nil)
	if rec := do(anon, http.MethodGet, "/v1/search/analytics/top-queries", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous: %d", rec.Code)
	}
	shopper, _, _ := server(&auth.Actor{Subject: "cus_1", Type: "CUSTOMER"})
	if rec := do(shopper, http.MethodGet, "/v1/search/analytics/ctr", ""); rec.Code != http.StatusForbidden {
		t.Fatalf("customer: %d", rec.Code)
	}
	staff, _, _ := server(&auth.Actor{Subject: "ops", Type: "STAFF", Permissions: []string{httpapi.PermAnalyticsView}})
	rec := do(staff, http.MethodGet, "/v1/search/analytics/top-queries?from=2026-10-01&to=2026-10-09&limit=5", "")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "egusi") {
		t.Fatalf("staff: %d %s", rec.Code, rec.Body)
	}
	if rec := do(staff, http.MethodGet, "/v1/search/analytics/zero-results?from=yesterday", ""); rec.Code != http.StatusBadRequest {
		t.Fatalf("bad date: %d", rec.Code)
	}
	if rec := do(staff, http.MethodPut, "/v1/search/synonyms/ogbono", `{"synonyms":"ogbono, bush mango"}`); rec.Code != http.StatusForbidden {
		t.Fatalf("synonym edit needs search:synonyms.edit: %d", rec.Code)
	}
}

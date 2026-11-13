package httpserver

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func status(t *testing.T, h http.Handler, path string) int {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec.Code
}

func TestMetricsServedPublicallyOnlyOutsideSharedEnvironments(t *testing.T) {
	t.Setenv("MANAGEMENT_ADDR", "")
	for env, want := range map[string]int{"local": http.StatusOK, "dev": http.StatusOK, "": http.StatusOK,
		"uat": http.StatusNotFound, "prod": http.StatusNotFound, "PROD": http.StatusNotFound} {
		t.Setenv("APP_ENV", env)
		h := New(Options{Name: "t"}).(*serverImpl).handler()
		if got := status(t, h, "/metrics"); got != want {
			t.Errorf("APP_ENV=%q public /metrics = %d, want %d", env, got, want)
		}
		if got := status(t, h, "/healthz"); got != http.StatusOK {
			t.Errorf("APP_ENV=%q /healthz = %d, want 200 (probes keep working)", env, got)
		}
	}
}

func TestManagementListenerOwnsMetrics(t *testing.T) {
	t.Setenv("APP_ENV", "prod")
	t.Setenv("MANAGEMENT_ADDR", ":9090")
	s := New(Options{Name: "t"}).(*serverImpl)

	h := s.handler()
	if got := status(t, h, "/metrics"); got != http.StatusNotFound {
		t.Errorf("public /metrics = %d, want 404 when a management listener exists", got)
	}
	if got := status(t, h, "/healthz"); got != http.StatusOK {
		t.Errorf("public /healthz = %d, want 200", got)
	}
	m := s.managementHandler()
	for _, p := range []string{"/metrics", "/healthz", "/readyz"} {
		if got := status(t, m, p); got != http.StatusOK {
			t.Errorf("management %s = %d, want 200", p, got)
		}
	}
	if got := status(t, m, "/v1/anything"); got != http.StatusNotFound {
		t.Errorf("management /v1/anything = %d, want 404", got)
	}
}

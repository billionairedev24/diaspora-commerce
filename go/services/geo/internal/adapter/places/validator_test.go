package places_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

const (
	mainStHouston = `{"status":"OK","results":[{"place_id":"ChIJ1","address_components":[
	 {"long_name":"123","short_name":"123","types":["street_number"]},
	 {"long_name":"Main Street","short_name":"Main St","types":["route"]},
	 {"long_name":"Houston","short_name":"Houston","types":["locality"]},
	 {"long_name":"Texas","short_name":"TX","types":["administrative_area_level_1"]},
	 {"long_name":"77002","short_name":"77002","types":["postal_code"]},
	 {"long_name":"United States","short_name":"US","types":["country"]}],
	 "geometry":{"location":{"lat":29.76,"lng":-95.36},"location_type":"%s"}}]}`
	zipCentroid = `{"status":"OK","results":[{"place_id":"ChIJz","address_components":[
	 {"long_name":"77002","short_name":"77002","types":["postal_code"]}],
	 "geometry":{"location":{"lat":29.75,"lng":-95.36},"location_type":"APPROXIMATE"}}]}`
)

func validatorFor(t *testing.T, status int, body string) (*httptest.Server, func(domain.Address) (domain.Validation, error)) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/geocode/json" || r.URL.Query().Get("key") != "k" ||
			!strings.Contains(r.URL.Query().Get("address"), "123 Main St") {
			t.Errorf("unexpected request %s", r.URL)
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	v := places.NewValidator("k", srv.URL, srv.Client())
	return srv, func(a domain.Address) (domain.Validation, error) { return v.Validate(context.Background(), a) }
}

var typed = domain.Address{Line1: "123 Main St", City: "Houston", State: "TX", PostalCode: "77002", Country: "US", Line2: "Apt 4"}

func TestValidatorStreetLevelMatchInTheSameZipIsValid(t *testing.T) {
	for _, lt := range []string{"ROOFTOP", "RANGE_INTERPOLATED"} {
		_, validate := validatorFor(t, 200, strings.Replace(mainStHouston, "%s", lt, 1))
		v, err := validate(typed)
		if err != nil || v.Status != domain.StatusValid || v.Standard == nil || v.Standard.Lat != 29.76 ||
			v.Standard.Line2 != "Apt 4" || v.PlaceID != "ChIJ1" || len(v.Messages) != 0 {
			t.Fatalf("%s: %+v %v", lt, v, err)
		}
	}
}

func TestValidatorCoarseMatchesAreNotFound(t *testing.T) {
	for name, body := range map[string]string{
		"centroid":     zipCentroid,
		"approximate":  strings.Replace(mainStHouston, "%s", "GEOMETRIC_CENTER", 1),
		"zero results": `{"status":"ZERO_RESULTS","results":[]}`,
	} {
		_, validate := validatorFor(t, 200, body)
		v, err := validate(typed)
		if err != nil || v.Status != domain.StatusNotFound || v.Messages[0] != domain.MessageNotFound || v.Suggestions == nil {
			t.Fatalf("%s: %+v %v", name, v, err)
		}
	}
}

func TestValidatorOtherZipIsNotFoundWithTheFoundAddressAsASuggestion(t *testing.T) {
	_, validate := validatorFor(t, 200, strings.Replace(mainStHouston, "%s", "ROOFTOP", 1))
	wrong := typed
	wrong.PostalCode = "77003"
	v, err := validate(wrong)
	if err != nil || v.Status != domain.StatusNotFound || len(v.Suggestions) != 1 || v.Suggestions[0].PostalCode != "77002" {
		t.Fatalf("%+v %v", v, err)
	}
}

func TestValidatorUnreachableGoogleIsAnError(t *testing.T) {
	for name, tc := range map[string]struct {
		status int
		body   string
	}{
		"http error":      {500, "boom"},
		"key denied":      {200, `{"status":"REQUEST_DENIED","error_message":"bad key"}`},
		"quota":           {200, `{"status":"OVER_QUERY_LIMIT"}`},
		"unreadable body": {200, `not json`},
	} {
		_, validate := validatorFor(t, tc.status, tc.body)
		if _, err := validate(typed); err == nil {
			t.Fatalf("%s: must be an error so the service accepts the address", name)
		}
	}
}

package smarty_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/smarty"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

func candidate(line, dpv, rdi string) smarty.Candidate {
	var c smarty.Candidate
	c.DeliveryLine1 = line
	c.Components.CityName = "Houston"
	c.Components.StateAbbreviation = "TX"
	c.Components.Zipcode = "77002"
	c.Analysis.DPVMatchCode = dpv
	c.Metadata.RDI = rdi
	return c
}

func TestInterpret(t *testing.T) {
	in := domain.Address{Line1: "123 Main St", City: "Houston", State: "TX", PostalCode: "77002"}
	cases := []struct {
		name string
		cs   []smarty.Candidate
		want domain.ValidationStatus
	}{
		{"exact", []smarty.Candidate{candidate("123 Main St", "Y", "Residential")}, domain.StatusValid},
		{"corrected", []smarty.Candidate{candidate("123 Main St W", "Y", "Commercial")}, domain.StatusCorrected},
		{"missing unit", []smarty.Candidate{candidate("123 Main St", "D", "Residential")}, domain.StatusMissingUnit},
		{"undeliverable", []smarty.Candidate{candidate("123 Main St", "N", "")}, domain.StatusUndeliverable},
		{"no candidates", nil, domain.StatusUndeliverable},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			v := smarty.Interpret(in, tc.cs)
			if v.Status != tc.want {
				t.Fatalf("status = %s, want %s (%v)", v.Status, tc.want, v.Messages)
			}
		})
	}
}

func TestClientSendsCredentialsAndParses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("auth-id") != "id" || r.URL.Query().Get("street") != "123 Main St" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		_ = json.NewEncoder(w).Encode([]smarty.Candidate{candidate("123 Main St", "D", "Residential")})
	}))
	defer srv.Close()
	v, err := smarty.New("id", "token", srv.URL, srv.Client()).Validate(context.Background(),
		domain.Address{Line1: "123 Main St", City: "Houston", State: "TX", PostalCode: "77002"})
	if err != nil {
		t.Fatal(err)
	}
	if v.Status != domain.StatusMissingUnit || v.Residential == nil || !*v.Residential {
		t.Fatalf("got %+v", v)
	}
}

package modcfg_test

import (
	"net/url"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/modcfg"
)

func base(m map[string]string) modcfg.Lookup {
	return func(k string) (string, bool) { v, ok := m[k]; return v, ok }
}

func TestPrefix(t *testing.T) {
	for in, want := range map[string]string{"cart": "CART_", "gateway-bff": "GATEWAY_BFF_", "carrier-gateway": "CARRIER_GATEWAY_"} {
		if got := modcfg.Prefix(in); got != want {
			t.Errorf("Prefix(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestModulePrefixedNameWinsThenPlainName(t *testing.T) {
	l := modcfg.New(modcfg.Options{Module: "cart", Base: base(map[string]string{"CART_DB_URL": "a", "DB_URL": "b", "REDIS_URL": "r", "PORT": ""})})
	if v, _ := l("DB_URL"); v != "a" {
		t.Errorf("DB_URL = %q, want the CART_ one", v)
	}
	if v, _ := l("REDIS_URL"); v != "r" {
		t.Errorf("REDIS_URL = %q, want the plain one", v)
	}
	if _, ok := l("PORT"); ok {
		t.Error("an empty value is not a value")
	}
	if _, ok := l("MISSING"); ok {
		t.Error("an unknown name is not found")
	}
}

func TestInProcessURLBeatsThePlainNameButNotTheModulePrefixedOne(t *testing.T) {
	in := map[string]string{"SEARCH_URL": "http://search.inproc"}
	l := modcfg.New(modcfg.Options{Module: "gateway-bff", InProcess: in, Base: base(map[string]string{"SEARCH_URL": "http://search:8201"})})
	if v, _ := l("SEARCH_URL"); v != "http://search.inproc" {
		t.Errorf("got %q", v)
	}
	l = modcfg.New(modcfg.Options{Module: "gateway-bff", InProcess: in, Base: base(map[string]string{"GATEWAY_BFF_SEARCH_URL": "http://x"})})
	if v, _ := l("SEARCH_URL"); v != "http://x" {
		t.Errorf("got %q", v)
	}
}

func TestSchemaURLPinsTheSearchPath(t *testing.T) {
	got, err := modcfg.SchemaURL("postgres://cart_role:s3cret@db:5432/go?sslmode=require", "geo,public")
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(got)
	if u.Query().Get("search_path") != "geo,public" || u.Query().Get("sslmode") != "require" || u.User.Username() != "cart_role" {
		t.Errorf("SchemaURL = %q", got)
	}
}

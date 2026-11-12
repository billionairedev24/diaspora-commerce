package config_test

import (
	"strings"
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/config"
)

func loadEnv(t *testing.T, env string) config.Config {
	t.Helper()
	var c config.Config
	if err := pkgconfig.NewLoaderFor(media.Files, env, func(string) (string, bool) { return "", false }).Load(&c); err != nil {
		t.Fatal(err)
	}
	return c
}

func prodEnv() map[string]string {
	return map[string]string{
		"R2_ACCOUNT_ID": "0123456789abcdef", "R2_ACCESS_KEY_ID": "pub", "R2_SECRET_ACCESS_KEY": "pubs",
		"R2_PRIVATE_ACCESS_KEY_ID": "priv", "R2_PRIVATE_SECRET_ACCESS_KEY": "privs",
	}
}

func lookup(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestProdSelectsR2ForPublicAndPrivateBuckets(t *testing.T) {
	c := loadEnv(t, pkgconfig.EnvProd)
	env := prodEnv()
	if err := c.Validate(pkgconfig.EnvProd, lookup(env)); err != nil {
		t.Fatalf("valid prod config refused: %v", err)
	}
	for _, p := range []string{"product-image", "storefront"} {
		if c.Stores[c.Purposes[p]].Bucket != "afrotransact-prod-media" {
			t.Errorf("%s not on the public R2 bucket", p)
		}
	}
	kyc := c.Stores[c.Purposes["kyc-document"]]
	if kyc.Bucket != "afrotransact-prod-kyc-evidence" || kyc.PublicBaseURL != "" {
		t.Error("kyc-document must use the private bucket without a public URL")
	}
	if got := c.Stores["r2"].ResolvedEndpoint(lookup(env)); got != "https://0123456789abcdef.r2.cloudflarestorage.com" {
		t.Errorf("endpoint = %q", got)
	}
	if c.Links.DefaultTTL.Minutes() != 5 {
		t.Errorf("signed link default = %v", c.Links.DefaultTTL)
	}
}

func TestProdRefusesUnsafeStorage(t *testing.T) {
	cases := map[string]func(c *config.Config, env map[string]string){
		"placeholder endpoint": func(_ *config.Config, env map[string]string) { delete(env, "R2_ACCOUNT_ID") },
		"missing public key":   func(_ *config.Config, env map[string]string) { delete(env, "R2_ACCESS_KEY_ID") },
		"missing private key":  func(_ *config.Config, env map[string]string) { delete(env, "R2_PRIVATE_SECRET_ACCESS_KEY") },
		"uploadthing default": func(c *config.Config, _ map[string]string) {
			c.Stores["uploadthing"] = config.Store{Kind: "uploadthing"}
			c.Purposes["product-image"] = "uploadthing"
		},
		"minio store": func(c *config.Config, _ map[string]string) {
			c.Stores["r2"] = config.Store{Kind: "s3", Name: "minio", Endpoint: "http://minio:9000", Bucket: "media",
				PathStyle: true, PublicBaseURL: "http://localhost:9000/media", AccessKeyEnv: "R2_ACCESS_KEY_ID", SecretKeyEnv: "R2_SECRET_ACCESS_KEY"}
		},
		"kyc in the public bucket": func(c *config.Config, _ map[string]string) { c.Purposes["kyc-document"] = "r2" },
		"shared credential": func(c *config.Config, _ map[string]string) {
			s := c.Stores["private"]
			s.AccessKeyEnv = "R2_ACCESS_KEY_ID"
			c.Stores["private"] = s
		},
		"http public url": func(c *config.Config, _ map[string]string) {
			s := c.Stores["r2"]
			s.PublicBaseURLEnv, s.PublicBaseURL = "", "http://media.example.test"
			c.Stores["r2"] = s
		},
		"auth off":             func(c *config.Config, _ map[string]string) { c.Auth.Enabled = false },
		"no quarantine store":  func(c *config.Config, _ map[string]string) { c.QuarantineStore = "" },
		"quarantine in public": func(c *config.Config, _ map[string]string) { c.QuarantineStore = "r2" },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			c, env := loadEnv(t, pkgconfig.EnvProd), prodEnv()
			mutate(&c, env)
			if err := c.Validate(pkgconfig.EnvProd, lookup(env)); err == nil {
				t.Fatal("expected prod to refuse this configuration")
			}
		})
	}
}

func TestValidateOnlyRestrictsProd(t *testing.T) {
	c := loadEnv(t, pkgconfig.EnvLocal)
	if err := c.Validate(pkgconfig.EnvLocal, lookup(nil)); err != nil {
		t.Fatalf("local must not be restricted: %v", err)
	}
}

func TestProdYamlNeverSelectsUploadThingOrMinio(t *testing.T) {
	b, err := media.Files.ReadFile("config/prod.yaml")
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(string(b), "\n") {
		l := strings.ToLower(line)
		if strings.HasPrefix(strings.TrimSpace(l), "#") {
			continue
		}
		if strings.Contains(l, "uploadthing") || strings.Contains(l, "minio") {
			t.Errorf("prod.yaml selects %q", line)
		}
	}
}

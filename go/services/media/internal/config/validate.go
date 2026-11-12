package config

import (
	"errors"
	"fmt"
	"net/url"
	"slices"
	"strings"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// publicPurposes are the purposes whose files are served publicly from the CDN.
var publicPurposes = []string{"product-image", "storefront"}

// privatePurposes must never share a bucket, a store or a credential with the public purposes.
var privatePurposes = []string{"kyc-document", "proof-photo", "return-photo", "purchase-order", "shipping-document",
	"career-resume", "export"}

// Validate refuses configurations that must not run in the given environment. In prod the public purposes must use
// a Cloudflare R2 store (https, real account endpoint, credentials present, an https public CDN URL) and every
// private purpose a different private R2 store (no public URL, its own bucket and credentials); UploadThing, MinIO,
// path-style and plain-http stores are refused. Other environments are not restricted.
func (c Config) Validate(env string, getenv func(string) string) error {
	if p := c.Images.Problem(); p != "" {
		return fmt.Errorf("images: %s", p)
	}
	if env != pkgconfig.EnvProd {
		return nil
	}
	var errs []error
	fail := func(format string, a ...any) { errs = append(errs, fmt.Errorf(format, a...)) }

	storeOf := func(purpose string) (string, Store, bool) {
		name, ok := c.Purposes[purpose]
		if !ok {
			fail("purpose %s has no store", purpose)
			return "", Store{}, false
		}
		st, ok := c.Stores[name]
		if !ok {
			fail("purpose %s refers to unknown store %s", purpose, name)
			return "", Store{}, false
		}
		return name, st, true
	}

	used := map[string]bool{}
	var publicBuckets, privateBuckets, publicKeys, privateKeys []string
	for _, p := range publicPurposes {
		name, st, ok := storeOf(p)
		if !ok {
			continue
		}
		used[name] = true
		public := st.ResolvedPublicBaseURL(getenv)
		if public == "" {
			fail("public store %s needs a public CDN URL (publicBaseUrl or MEDIA_PUBLIC_URL)", name)
		} else if u, err := url.Parse(public); err != nil || u.Scheme != "https" {
			fail("public store %s: public URL must be https", name)
		}
		publicBuckets = append(publicBuckets, st.ResolvedBucket(getenv))
		publicKeys = append(publicKeys, st.AccessKeyEnv)
	}
	for _, p := range privatePurposes {
		name, st, ok := storeOf(p)
		if !ok {
			continue
		}
		used[name] = true
		if st.PublicBaseURL != "" || st.PublicBaseURLEnv != "" {
			fail("private purpose %s uses store %s that has a public URL", p, name)
		}
		privateBuckets = append(privateBuckets, st.ResolvedBucket(getenv))
		privateKeys = append(privateKeys, st.AccessKeyEnv)
	}
	if c.QuarantineStore == "" {
		fail("quarantineStore must name the private store: unreleased public images must not sit in the public bucket")
	} else if st, ok := c.Stores[c.QuarantineStore]; !ok {
		fail("quarantineStore refers to unknown store %s", c.QuarantineStore)
	} else {
		used[c.QuarantineStore] = true
		if st.PublicBaseURL != "" || st.PublicBaseURLEnv != "" || slices.Contains(publicBuckets, st.ResolvedBucket(getenv)) ||
			slices.Contains(publicKeys, st.AccessKeyEnv) {
			fail("quarantineStore %s must be a private store with its own bucket and credential", c.QuarantineStore)
		}
	}
	for _, b := range privateBuckets {
		if slices.Contains(publicBuckets, b) {
			fail("bucket %s is used by both public and private purposes", b)
		}
	}
	for _, k := range privateKeys {
		if slices.Contains(publicKeys, k) {
			fail("public and private stores share the credential %s; use one least-privilege token per bucket", k)
		}
	}
	for name := range used {
		errs = append(errs, validateR2Store(name, c.Stores[name], getenv)...)
	}
	for name, st := range c.Stores {
		if st.Kind == "uploadthing" {
			fail("store %s: UploadThing is not allowed in prod", name)
		}
	}
	if !c.Auth.Enabled {
		fail("auth.enabled must be true in prod")
	}
	return errors.Join(errs...)
}

func validateR2Store(name string, st Store, getenv func(string) string) []error {
	var errs []error
	fail := func(format string, a ...any) { errs = append(errs, fmt.Errorf(format, a...)) }
	if st.Kind != "s3" || st.Name != "r2" {
		fail("store %s: prod needs kind s3 / name r2 (Cloudflare R2), got kind %q name %q", name, st.Kind, st.Name)
		return errs
	}
	if st.PathStyle {
		fail("store %s: pathStyle is for MinIO, not R2", name)
	}
	if bucket := st.ResolvedBucket(getenv); bucket == "" || strings.Contains(strings.ToLower(bucket), "minio") {
		fail("store %s: bucket %q is not a prod bucket", name, bucket)
	}
	ep := st.ResolvedEndpoint(getenv)
	u, err := url.Parse(ep)
	switch {
	case err != nil || u.Scheme != "https" || !strings.HasSuffix(u.Hostname(), ".r2.cloudflarestorage.com"):
		fail("store %s: endpoint %q is not an https R2 endpoint", name, ep)
	case strings.Contains(strings.ToUpper(u.Hostname()), "ACCOUNT_ID"):
		fail("store %s: endpoint still has the ACCOUNT_ID placeholder; set R2_ACCOUNT_ID or R2_ENDPOINT", name)
	}
	for _, env := range []string{st.AccessKeyEnv, st.SecretKeyEnv} {
		if env == "" || strings.TrimSpace(getenv(env)) == "" {
			fail("store %s: credential %q is not set", name, env)
		}
	}
	return errs
}

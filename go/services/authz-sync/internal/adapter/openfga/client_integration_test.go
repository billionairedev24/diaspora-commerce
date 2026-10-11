package openfga_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/adapter/openfga"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
)

// TestSyncAgainstOpenFGA provisions a real OpenFGA (local image openfga/openfga:v1.10.0) with the embedded model and
// replays identity / seller-org events through the syncer, checking the resulting permissions.
func TestSyncAgainstOpenFGA(t *testing.T) {
	testdocker.Require(t)
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "openfga/openfga:v1.10.0",
		testcontainers.WithCmd("run"),
		testcontainers.WithExposedPorts("8080/tcp"),
		testcontainers.WithWaitStrategy(wait.ForHTTP("/healthz").WithPort("8080/tcp").
			WithStartupTimeout(2*time.Minute)),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	base, err := ctr.PortEndpoint(ctx, "8080/tcp", "http")
	if err != nil {
		t.Fatal(err)
	}
	client := openfga.NewClient(base, "", "", &http.Client{Timeout: 5 * time.Second})

	// Store and model provisioning are idempotent.
	store, err := client.EnsureStore(ctx, "afrotransact")
	must(t, err)
	again, err := client.EnsureStore(ctx, "afrotransact")
	must(t, err)
	if again != store {
		t.Fatalf("second EnsureStore created %s, want %s", again, store)
	}
	client.UseStore(store)
	model, err := authzsync.Files.ReadFile("model/model.json")
	must(t, err)
	id, written, err := client.EnsureModel(ctx, model)
	must(t, err)
	if !written || id == "" {
		t.Fatalf("first EnsureModel: id=%q written=%v", id, written)
	}
	id2, written, err := client.EnsureModel(ctx, model)
	must(t, err)
	if written || id2 != id {
		t.Fatalf("unchanged model rewritten: id=%q written=%v", id2, written)
	}
	must(t, client.Ping(ctx))

	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))
	sync := app.NewSyncer(client, "org_retail", quiet)
	replay := func(typ, payload string) {
		t.Helper()
		e := events.Envelope{ID: typ, Type: typ, Payload: []byte(payload)}
		must(t, sync.Handle(ctx, e))
		must(t, sync.Handle(ctx, e)) // redelivery is harmless
	}
	offer := []map[string]string{{"user": "organization:org_a", "relation": "org", "object": "offer:o1"}}

	replay(domain.SellerOrgApproved, `{"orgId":"org_a","kind":"SELLER","country":"US","status":"APPROVED"}`)
	replay(domain.RoleGranted, `{"userId":"u1","roleKey":"seller.catalog","orgId":"org_a"}`)
	replay(domain.RoleGranted, `{"userId":"s1","roleKey":"platform.catalog_manager","market":"GB"}`)
	if !check(t, base, store, "user:u1", "can_edit", "offer:o1", offer) {
		t.Fatal("catalog member cannot edit its org's offer")
	}
	if check(t, base, store, "user:s1", "can_edit", "offer:o1", offer) {
		t.Fatal("GB catalog manager edits a US offer")
	}

	// The organisation moves to GB and becomes a provider only: products are off, GB staff now reach it.
	replay(domain.SellerOrgUpdated, `{"orgId":"org_a","kind":"PROVIDER","country":"GB","status":"APPROVED"}`)
	markets, err := client.Read(ctx, "organization:org_a", "market")
	must(t, err)
	if len(markets) != 1 || markets[0].User != "market:GB" {
		t.Fatalf("markets = %+v", markets)
	}
	if check(t, base, store, "user:u1", "can_edit", "offer:o1", offer) {
		t.Fatal("provider-only organisation still edits products")
	}
	if !check(t, base, store, "user:s1", "can_edit", "offer:o1", offer) {
		t.Fatal("GB catalog manager cannot edit a GB offer")
	}

	// Revocation removes access.
	replay(domain.RoleGranted, `{"userId":"u2","roleKey":"seller.owner","orgId":"org_a"}`)
	if !check(t, base, store, "user:u2", "member", "organization:org_a", nil) {
		t.Fatal("owner is not a member")
	}
	replay(domain.RoleRevoked, `{"userId":"u2","roleKey":"seller.owner","orgId":"org_a"}`)
	if check(t, base, store, "user:u2", "member", "organization:org_a", nil) {
		t.Fatal("revoked owner is still a member")
	}
}

func check(t *testing.T, base, store, user, relation, object string, contextual []map[string]string) bool {
	t.Helper()
	body := map[string]any{"tuple_key": map[string]string{"user": user, "relation": relation, "object": object}}
	if len(contextual) > 0 {
		body["contextual_tuples"] = map[string]any{"tuple_keys": contextual}
	}
	raw, err := json.Marshal(body)
	must(t, err)
	req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, base+"/stores/"+store+"/check",
		bytes.NewReader(raw))
	must(t, err)
	resp, err := http.DefaultClient.Do(req)
	must(t, err)
	defer func() { _ = resp.Body.Close() }()
	var out struct {
		Allowed bool `json:"allowed"`
	}
	must(t, json.NewDecoder(resp.Body).Decode(&out))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("check %s %s %s: %d", user, relation, object, resp.StatusCode)
	}
	return out.Allowed
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

package elastic_test

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/elastic/go-elasticsearch/v9"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/elastic"
)

func TestVersionGuardedProjection(t *testing.T) {
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "elasticsearch:9.3.0",
		testcontainers.WithEnv(map[string]string{
			"discovery.type": "single-node", "xpack.security.enabled": "false",
			"ES_JAVA_OPTS": "-Xms512m -Xmx512m", "cluster.routing.allocation.disk.threshold_enabled": "false",
		}),
		testcontainers.WithExposedPorts("9200/tcp"),
		testcontainers.WithWaitStrategy(wait.ForHTTP("/_cluster/health?wait_for_status=yellow").
			WithPort("9200/tcp").WithStartupTimeout(3*time.Minute)),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	endpoint, err := ctr.PortEndpoint(ctx, "9200/tcp", "http")
	if err != nil {
		t.Fatal(err)
	}
	es, err := elasticsearch.New(elasticsearch.WithAddresses(endpoint))
	if err != nil {
		t.Fatal(err)
	}
	if err := searchindex.NewAdmin(es).EnsureIndex(ctx, "us"); err != nil {
		t.Fatal(err)
	}
	idx := elastic.NewIndex(es, true)

	doc := searchindex.ProductDocument{ATIN: "AT00000001", Title: "Jollof Seasoning Blend", Department: "Groceries",
		CategoryPath: "Groceries › Pantry", SellerID: "org_r", SellerName: "AfroTransact Retail", SellerKind: "PLATFORM",
		Fulfilment: "AFROTRANSACT", PriceMinor: 899, Currency: "USD", Status: "IN_REVIEW",
		VariantATINs: []string{"AT0000000A", "AT0000000B"}}
	must(t, idx.UpsertProduct(ctx, "us", doc, 100))

	found, err := idx.ApplyAvailability(ctx, "us", "AT0000000A", "HOU1", 7, 200)
	must(t, err)
	if !found {
		t.Fatal("variant should be found")
	}
	_, err = idx.ApplyAvailability(ctx, "us", "AT0000000B", "EWR1", 5, 201)
	must(t, err)
	_, err = idx.ApplyAvailability(ctx, "us", "AT0000000A", "HOU1", 99, 150) // older: ignored
	must(t, err)

	live := doc
	live.Status = "LIVE"
	must(t, idx.UpsertProduct(ctx, "us", live, 300))
	stale := doc
	stale.Title = "Stale title"
	must(t, idx.UpsertProduct(ctx, "us", stale, 250)) // older: ignored

	got := get(t, es, "AT00000001")
	if got.Status != "LIVE" || got.Title != "Jollof Seasoning Blend" {
		t.Fatalf("version guard failed: %+v", got)
	}
	if got.AvailableQty != 12 || !got.InStock {
		t.Fatalf("available = %d in_stock = %v, want 12 true", got.AvailableQty, got.InStock)
	}

	missing, err := idx.ApplyAvailability(ctx, "us", "ATZZZZZZZZ", "HOU1", 3, 400)
	must(t, err)
	if missing {
		t.Fatal("unknown variant should report not found")
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func get(t *testing.T, es *elasticsearch.Client, id string) searchindex.ProductDocument {
	t.Helper()
	res, err := es.Get(searchindex.Alias("us"), id)
	must(t, err)
	defer func() { _ = res.Body.Close() }()
	var out struct {
		Source searchindex.ProductDocument `json:"_source"`
	}
	must(t, json.NewDecoder(res.Body).Decode(&out))
	return out.Source
}

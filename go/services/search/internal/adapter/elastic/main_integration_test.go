package elastic_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/elastic/go-elasticsearch/v9"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
)

// image is the local Elasticsearch image (Docker Hub is rate-limited; never pull another tag in CI).
const image = "elasticsearch:9.3.0"

var (
	esOnce     sync.Once
	esClient   *elasticsearch.Client
	esErr      error
	esShutdown func()
)

// TestMain shares one Elasticsearch container between every integration test of the package.
func TestMain(m *testing.M) {
	code := m.Run()
	if esShutdown != nil {
		esShutdown()
	}
	os.Exit(code)
}

// sharedES starts (once) and returns the package's Elasticsearch client, or skips without Docker.
func sharedES(t *testing.T) *elasticsearch.Client {
	t.Helper()
	testdocker.Require(t)
	esOnce.Do(func() {
		ctx := context.Background()
		ctr, err := testcontainers.Run(ctx, image,
			testcontainers.WithEnv(map[string]string{
				"discovery.type":         "single-node",
				"xpack.security.enabled": "false",
				"ES_JAVA_OPTS":           "-Xms512m -Xmx512m",
				"cluster.routing.allocation.disk.threshold_enabled": "false",
			}),
			testcontainers.WithExposedPorts("9200/tcp"),
			testcontainers.WithWaitStrategy(wait.ForHTTP("/_cluster/health?wait_for_status=yellow").
				WithPort("9200/tcp").WithStartupTimeout(3*time.Minute)),
		)
		if err != nil {
			esErr = fmt.Errorf("start elasticsearch: %w", err)
			return
		}
		esShutdown = func() { _ = ctr.Terminate(context.Background()) }
		endpoint, err := ctr.PortEndpoint(ctx, "9200/tcp", "http")
		if err != nil {
			esErr = fmt.Errorf("endpoint: %w", err)
			return
		}
		esClient, esErr = elasticsearch.New(elasticsearch.WithAddresses(endpoint))
	})
	if esErr != nil {
		t.Fatal(esErr)
	}
	return esClient
}

// bulkIndex writes documents into market's alias and refreshes.
func bulkIndex(t *testing.T, es *elasticsearch.Client, market string, docs []searchindex.ProductDocument) {
	t.Helper()
	var buf bytes.Buffer
	for _, d := range docs {
		meta, _ := json.Marshal(map[string]any{"index": map[string]string{"_index": searchindex.Alias(market), "_id": d.ATIN}})
		body, _ := json.Marshal(d)
		buf.Write(meta)
		buf.WriteByte('\n')
		buf.Write(body)
		buf.WriteByte('\n')
	}
	res, err := es.Bulk(bytes.NewReader(buf.Bytes()), es.Bulk.WithRefresh("true"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	var out struct {
		Errors bool `json:"errors"`
		Items  []map[string]any
	}
	_ = json.NewDecoder(res.Body).Decode(&out)
	if res.IsError() || out.Errors {
		t.Fatalf("bulk failed: %s %+v", res.Status(), out.Items)
	}
}

// refresh makes recent writes to index searchable.
func refresh(t *testing.T, es *elasticsearch.Client, index string) {
	t.Helper()
	res, err := es.Indices.Refresh(es.Indices.Refresh.WithIndex(index))
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.IsError() {
		t.Fatalf("refresh %s: %s", index, res.Status())
	}
}

// putEmptySet creates an empty synonyms set, as the indexer does when it creates an index before search starts.
func putEmptySet(t *testing.T, es *elasticsearch.Client, set string) {
	t.Helper()
	res, err := es.SynonymsPutSynonym(set, strings.NewReader(`{"synonyms_set":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.IsError() {
		t.Fatalf("put empty set: %s", res.Status())
	}
}

// Command search serves product search and typeahead over Elasticsearch.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/embedder"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

const serviceName = "search"

func main() {
	if err := run(); err != nil {
		slog.Error("search exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(search.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)

	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	esOpts := []elasticsearch.Option{elasticsearch.WithAddresses(cfg.Elasticsearch.URLs...), elasticsearch.WithRetry(3)}
	switch {
	case cfg.Elasticsearch.APIKey != "":
		esOpts = append(esOpts, elasticsearch.WithAPIKey(cfg.Elasticsearch.APIKey))
	case cfg.Elasticsearch.Username != "":
		esOpts = append(esOpts, elasticsearch.WithBasicAuth(cfg.Elasticsearch.Username, cfg.Elasticsearch.Password))
	}
	es, err := elasticsearch.New(esOpts...)
	if err != nil {
		return fmt.Errorf("elasticsearch client: %w", err)
	}
	if loader.Env() == pkgconfig.EnvLocal {
		admin := searchindex.NewAdmin(es)
		for _, m := range cfg.Markets {
			if err := admin.EnsureIndex(ctx, m); err != nil {
				log.Warn("could not ensure index (is Elasticsearch up?)", "market", m, "error", err)
			}
		}
	}

	httpClient := &http.Client{Timeout: 5 * time.Second}
	index := elastic.NewIndex(es)
	var emb port.Embedder
	if cfg.AIGatewayURL != "" {
		emb = embedder.NewGatewayEmbedder(cfg.AIGatewayURL, httpClient)
	}
	searcher := app.NewProductSearcher(index, emb, log)
	suggester := app.NewSuggester(index)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}

	openapi, err := search.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name:    "Search API",
		Addr:    ":" + strconv.Itoa(cfg.Port),
		OpenAPI: openapi,
		DocsOn:  cfg.DocsEnabled,
		Checks:  []httpserver.ReadinessCheck{readiness{index: index}},
		Logger:  log,
	})
	httpapi.New(searcher, suggester).Register(srv.Mux(), auth.Middleware(verifier, true))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

type readiness struct{ index port.Index }

func (readiness) Name() string { return "elasticsearch" }
func (r readiness) Check(ctx context.Context) error {
	if err := r.index.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}

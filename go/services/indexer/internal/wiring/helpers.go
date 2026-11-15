// Package wiring holds the construction helpers shared by the indexer consumer (module.go) and `indexer reindex`.
package wiring

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/embedder"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sellers"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

const fallbackSellerName = "Marketplace seller"

func NewSellerDirectory(cfg config.Config, httpClient *http.Client, log *slog.Logger) sellers.Directory {
	sd := cfg.SellerDirectory
	if !sd.Enabled || sd.BaseURL == "" {
		log.Warn("seller directory disabled: names come from configuration and seller.org.* events only")
		return sellers.NewDirectory(cfg.SellerNames, fallbackSellerName, nil, sellers.Options{Log: log})
	}
	if sd.TokenURL != "" && sd.ClientSecret == "" {
		log.Warn("INDEXER_CLIENT_SECRET not set: seller directory calls are unauthenticated")
	}
	source := sellers.NewClient(sellers.ClientConfig{BaseURL: sd.BaseURL, TokenURL: sd.TokenURL, ClientID: sd.ClientID,
		ClientSecret: sd.ClientSecret, Scopes: sd.Scopes}, httpClient)
	return sellers.NewDirectory(cfg.SellerNames, fallbackSellerName, source,
		sellers.Options{PageSize: sd.PageSize, Log: log})
}

// warmDirectory retries the warm with backoff until it succeeds or timeout passes; the consumer then starts either
// way (unknown sellers keep the name already indexed, and are looked up on their next event).
func WarmDirectory(ctx context.Context, d sellers.Directory, timeout time.Duration, log *slog.Logger) {
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	wctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	backoff := 500 * time.Millisecond
	for {
		n, err := d.Warm(wctx)
		if err == nil {
			log.Info("seller directory warmed", "names", n)
			return
		}
		select {
		case <-wctx.Done():
			log.Error("seller directory not warmed; starting with known names only", "names", n, "error", err)
			return
		case <-time.After(backoff):
			backoff = min(backoff*2, 8*time.Second)
		}
	}
}

// newEmbedder returns the ai-gateway embedder (indexer-service client, scope ai:use), or nil when embeddings are off.
func NewEmbedder(cfg config.Config, log *slog.Logger) port.Embedder {
	e := cfg.Embeddings
	if !e.Enabled || e.BaseURL == "" {
		log.Info("embeddings off: products are indexed for lexical search only")
		return nil
	}
	sd := cfg.SellerDirectory
	if sd.TokenURL != "" && sd.ClientSecret == "" {
		log.Warn("INDEXER_CLIENT_SECRET not set: embedding calls are unauthenticated")
	}
	timeout := e.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	return embedder.New(embedder.Config{BaseURL: e.BaseURL, TokenURL: sd.TokenURL, ClientID: sd.ClientID,
		ClientSecret: sd.ClientSecret, Scopes: e.Scopes, CacheSize: e.CacheSize}, &http.Client{Timeout: timeout})
}

type CountingEmbedder struct {
	Next    port.Embedder
	Counter *prometheus.CounterVec
}

func (c CountingEmbedder) Embed(ctx context.Context, text string) ([]float32, error) {
	v, err := c.Next.Embed(ctx, text)
	if err != nil {
		c.Counter.WithLabelValues("error").Inc()
		return nil, fmt.Errorf("embed: %w", err)
	}
	c.Counter.WithLabelValues("ok").Inc()
	return v, nil
}

type Counting struct {
	Next    events.Handler
	Counter *prometheus.CounterVec
}

func (c Counting) Handle(ctx context.Context, e events.Envelope) error {
	if err := c.Next.Handle(ctx, e); err != nil {
		c.Counter.WithLabelValues(e.Type, "error").Inc()
		return fmt.Errorf("project %s: %w", e.Type, err)
	}
	c.Counter.WithLabelValues(e.Type, "ok").Inc()
	return nil
}

type Readiness struct {
	Index  port.ProductIndex
	Engine string
}

func (r Readiness) Name() string { return r.Engine }

func (r Readiness) Check(ctx context.Context) error {
	if err := r.Index.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}

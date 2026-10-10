package app

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// EmbeddingBackfill gives every LIVE product an embedding: documents indexed before semantic retrieval existed or
// while the AI gateway was unavailable. A pass walks the documents without one in ATIN order; a vector is stored only
// if the document did not change meanwhile (version guard).
type EmbeddingBackfill interface {
	// RunOnce makes one pass and returns how many documents got an embedding.
	RunOnce(ctx context.Context) (int, error)
	// Run passes at start and then every interval until ctx ends.
	Run(ctx context.Context, interval time.Duration) error
}

type embeddingBackfillImpl struct {
	index    port.ProductIndex
	embedder port.Embedder
	market   string
	batch    int
	timeout  time.Duration
	log      *slog.Logger
}

var _ EmbeddingBackfill = (*embeddingBackfillImpl)(nil)

// NewEmbeddingBackfill returns the backfill (batch documents per page, timeout per embedding call).
func NewEmbeddingBackfill(index port.ProductIndex, embedder port.Embedder, market string, batch int,
	timeout time.Duration, log *slog.Logger,
) EmbeddingBackfill {
	if batch <= 0 {
		batch = 100
	}
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	return &embeddingBackfillImpl{index: index, embedder: embedder, market: market, batch: batch, timeout: timeout, log: log}
}

func (b *embeddingBackfillImpl) RunOnce(ctx context.Context) (int, error) {
	stored, after := 0, ""
	for {
		page, err := b.index.MissingEmbeddings(ctx, b.market, after, b.batch)
		if err != nil {
			return stored, fmt.Errorf("documents without embeddings: %w", err)
		}
		for _, c := range page {
			after = c.ATIN
			text := searchindex.EmbeddingText(c.Title, c.Brand, c.CategoryPath, "")
			if text == "" {
				continue
			}
			ectx, cancel := context.WithTimeout(ctx, b.timeout)
			vec, err := b.embedder.Embed(ectx, text)
			cancel()
			if err != nil {
				// The gateway is down: stop this pass; the next one resumes.
				return stored, fmt.Errorf("embed %s: %w", c.ATIN, err)
			}
			ok, err := b.index.SetEmbedding(ctx, b.market, c.ATIN, vec, c.Version)
			if err != nil {
				return stored, fmt.Errorf("store embedding %s: %w", c.ATIN, err)
			}
			if ok {
				stored++
			}
		}
		if len(page) < b.batch {
			return stored, nil
		}
	}
}

func (b *embeddingBackfillImpl) Run(ctx context.Context, interval time.Duration) error {
	if interval <= 0 {
		interval = 15 * time.Minute
	}
	for {
		n, err := b.RunOnce(ctx)
		switch {
		case err != nil && ctx.Err() == nil:
			b.log.WarnContext(ctx, "embedding backfill pass stopped", "stored", n, "error", err)
		case n > 0:
			b.log.InfoContext(ctx, "embedding backfill pass", "stored", n)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(interval):
		}
	}
}

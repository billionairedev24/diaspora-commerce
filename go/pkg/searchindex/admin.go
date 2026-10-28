package searchindex

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/elastic/go-elasticsearch/v9"
)

// Admin manages physical indices and aliases.
type Admin interface {
	// EnsureIndex creates IndexName(market) with the current mapping and points Alias(market) at it, if missing.
	EnsureIndex(ctx context.Context, market string) error
}

type adminImpl struct {
	es *elasticsearch.Client
}

var _ Admin = (*adminImpl)(nil)

// NewAdmin returns an Admin backed by the official Elasticsearch client.
func NewAdmin(es *elasticsearch.Client) Admin { return &adminImpl{es: es} }

func (a *adminImpl) EnsureIndex(ctx context.Context, market string) error {
	name := IndexName(market)
	exists, err := a.es.Indices.Exists([]string{name}, a.es.Indices.Exists.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("index exists %s: %w", name, err)
	}
	_ = exists.Body.Close()
	if exists.StatusCode == http.StatusOK {
		return a.putLateMappings(ctx, name)
	}
	body, err := Mapping()
	if err != nil {
		return err
	}
	res, err := a.es.Indices.Create(name, a.es.Indices.Create.WithBody(bytes.NewReader(body)), a.es.Indices.Create.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("create %s: %w", name, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(res.Body)
		if !strings.Contains(string(msg), "resource_already_exists_exception") {
			return fmt.Errorf("create %s: %s", name, msg)
		}
	}
	alias, err := a.es.Indices.PutAlias([]string{name}, Alias(market), a.es.Indices.PutAlias.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("alias %s: %w", name, err)
	}
	defer func() { _ = alias.Body.Close() }()
	if alias.IsError() {
		msg, _ := io.ReadAll(alias.Body)
		return fmt.Errorf("alias %s: %s", name, msg)
	}
	return nil
}

// putLateMappings adds the fields introduced after the index was first created (idempotent).
func (a *adminImpl) putLateMappings(ctx context.Context, name string) error {
	res, err := a.es.Indices.PutMapping([]string{name}, strings.NewReader(LateMappings), a.es.Indices.PutMapping.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("put mapping %s: %w", name, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(res.Body)
		return fmt.Errorf("put mapping %s: %s", name, msg)
	}
	return nil
}

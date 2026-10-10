package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// SynonymCurator lets staff curate the diaspora synonym set (guided by the zero-result report). Changes reload the
// search analyzers immediately; no reindex is needed.
type SynonymCurator interface {
	List(ctx context.Context) ([]domain.SynonymRule, error)
	Put(ctx context.Context, rule domain.SynonymRule) (domain.SynonymRule, error)
	Delete(ctx context.Context, id string) error
}

// ErrSynonymNotFound is returned when deleting an unknown rule.
var ErrSynonymNotFound = errors.New("synonym rule not found")

type synonymCuratorImpl struct {
	admin port.IndexAdmin
	log   *slog.Logger
}

var _ SynonymCurator = (*synonymCuratorImpl)(nil)

// NewSynonymCurator returns the curation use case.
func NewSynonymCurator(admin port.IndexAdmin, log *slog.Logger) SynonymCurator {
	return &synonymCuratorImpl{admin: admin, log: log}
}

func (s *synonymCuratorImpl) List(ctx context.Context) ([]domain.SynonymRule, error) {
	rules, err := s.admin.Synonyms(ctx)
	if err != nil {
		return nil, fmt.Errorf("list synonyms: %w", err)
	}
	return rules, nil
}

func (s *synonymCuratorImpl) Put(ctx context.Context, rule domain.SynonymRule) (domain.SynonymRule, error) {
	rule, err := rule.Normalize()
	if err != nil {
		return domain.SynonymRule{}, fmt.Errorf("synonym: %w", err)
	}
	if err := s.admin.PutSynonym(ctx, rule); err != nil {
		return domain.SynonymRule{}, fmt.Errorf("put synonym: %w", err)
	}
	s.log.InfoContext(ctx, "synonym rule saved", "id", rule.ID, "synonyms", rule.Synonyms)
	return rule, nil
}

func (s *synonymCuratorImpl) Delete(ctx context.Context, id string) error {
	found, err := s.admin.DeleteSynonym(ctx, id)
	if err != nil {
		return fmt.Errorf("delete synonym: %w", err)
	}
	if !found {
		return ErrSynonymNotFound
	}
	s.log.InfoContext(ctx, "synonym rule deleted", "id", id)
	return nil
}

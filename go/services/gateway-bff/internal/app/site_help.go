package app

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func helpNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that help page.")
}

func (s *siteServiceImpl) HelpArticles(ctx context.Context, q domain.HelpQuery) (domain.HelpArticlePage, error) {
	q.Topic = strings.ToUpper(strings.TrimSpace(q.Topic))
	q.Q = strings.TrimSpace(q.Q)
	q.Cursor = strings.TrimSpace(q.Cursor)
	errs := map[string]string{}
	if q.Topic != "" && !domain.IsHelpTopic(q.Topic) {
		errs["topic"] = "Unknown help topic"
	}
	if len(q.Q) > 100 {
		errs["q"] = "Up to 100 characters"
	}
	if q.Limit < 0 || q.Limit > domain.HelpPageMax {
		errs["limit"] = "Between 1 and 50"
	}
	if len(errs) > 0 {
		return domain.HelpArticlePage{}, domain.ValidationProblem(errs)
	}
	if q.Limit == 0 {
		q.Limit = 20
	}
	p, err := s.d.Content.HelpArticles(ctx, q)
	if err != nil {
		return p, mapErr("content", err)
	}
	p.Items = orEmpty(p.Items)
	p.Topics = orEmpty(p.Topics)
	if p.NextCursor != nil && *p.NextCursor == "" {
		p.NextCursor = nil
	}
	return p, nil
}

func (s *siteServiceImpl) HelpArticle(ctx context.Context, slug string) (domain.HelpArticle, error) {
	slug = strings.ToLower(strings.TrimSpace(slug))
	if !domain.IsHelpSlug(slug) {
		return domain.HelpArticle{}, helpNotFound()
	}
	a, err := s.d.Content.HelpArticle(ctx, slug)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return a, helpNotFound().WithCause(err)
		}
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusNotFound {
			return a, helpNotFound().WithCause(err)
		}
		return a, mapErr("content", err)
	}
	a.Sections = orEmpty(a.Sections)
	return a, nil
}

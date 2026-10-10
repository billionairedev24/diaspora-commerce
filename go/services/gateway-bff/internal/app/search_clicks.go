package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SearchClickService reports result clicks (organic and sponsored) to search (S039, S240). Search counts a click
// once per search and product; a sponsored click with a valid, unexpired ad token is charged once per token.
type SearchClickService interface {
	Record(ctx context.Context, c *domain.Caller, click domain.SearchClick) (domain.SearchClickResult, error)
}

type searchClickServiceImpl struct {
	clicks port.SearchClicksClient
	market string
}

var _ SearchClickService = (*searchClickServiceImpl)(nil)

// NewSearchClickService returns the SearchClickService.
func NewSearchClickService(clicks port.SearchClicksClient, market string) SearchClickService {
	return &searchClickServiceImpl{clicks: clicks, market: market}
}

func (s *searchClickServiceImpl) Record(ctx context.Context, _ *domain.Caller, click domain.SearchClick) (domain.SearchClickResult, error) {
	errs := map[string]string{}
	click.SearchID = strings.TrimSpace(click.SearchID)
	if click.SearchID == "" || len(click.SearchID) > 200 {
		errs["searchId"] = "Required"
	}
	if !atinRe.MatchString(click.ATIN) {
		errs["atin"] = "Invalid ATIN"
	}
	if click.Position < 1 || click.Position > 1000 {
		errs["position"] = "Must be between 1 and 1000"
	}
	if len(click.AdToken) > 4096 {
		errs["adToken"] = "Too long"
	}
	if len(errs) > 0 {
		return domain.SearchClickResult{}, domain.ValidationProblem(errs)
	}
	q := strings.TrimSpace(click.Q)
	if len(q) > 200 {
		q = q[:200]
	}
	res, err := s.clicks.RecordClick(ctx, port.SearchClick{SearchID: click.SearchID, Q: q, Market: s.market, ATIN: click.ATIN,
		Position: click.Position, AdToken: click.AdToken})
	if err != nil {
		return domain.SearchClickResult{}, mapErr("search", err)
	}
	return domain.SearchClickResult{Recorded: res.Recorded, Duplicate: res.Duplicate, Sponsored: res.Sponsored, AdStatus: res.AdStatus}, nil
}

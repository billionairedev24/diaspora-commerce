// Package app holds the storefront use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/port"
)

// Permissions checked by the storefront use cases.
const (
	PermEdit     = "storefront:edit"
	PermModerate = "storefront:moderate"
)

// ErrForbidden is returned when the actor lacks the permission or org.
var ErrForbidden = errors.New("forbidden")

// StorefrontService lets sellers and providers design their pages and staff moderate links.
type StorefrontService interface {
	VetTheme(in domain.ThemeInput) (domain.Vetting, error)
	Draft(ctx context.Context, actor auth.Actor) (domain.Storefront, error)
	SaveDraft(ctx context.Context, actor auth.Actor, s domain.Storefront) (domain.Storefront, error)
	Publish(ctx context.Context, actor auth.Actor) (domain.Storefront, domain.Vetting, error)
	Public(ctx context.Context, slug string) (domain.Storefront, error)
	PendingLinks(ctx context.Context, actor auth.Actor) ([]domain.Storefront, error)
	DecideLink(ctx context.Context, actor auth.Actor, orgID, network string, approve bool) (domain.Storefront, error)
}

type storefrontServiceImpl struct {
	repo    port.StorefrontRepository
	purger  port.CachePurger
	limits  port.PlanLimits
	baseURL string
	now     func() time.Time
}

var _ StorefrontService = (*storefrontServiceImpl)(nil)

// NewStorefrontService returns the use cases. publicBaseURL is the storefront origin used for cache purges.
func NewStorefrontService(repo port.StorefrontRepository, purger port.CachePurger, limits port.PlanLimits,
	publicBaseURL string,
) StorefrontService {
	return &storefrontServiceImpl{repo: repo, purger: purger, limits: limits, baseURL: strings.TrimSuffix(publicBaseURL, "/"),
		now: time.Now}
}

func (s *storefrontServiceImpl) VetTheme(in domain.ThemeInput) (domain.Vetting, error) {
	v, err := domain.Vet(in)
	if err != nil {
		return domain.Vetting{}, fmt.Errorf("vet theme: %w", err)
	}
	return v, nil
}

func orgOf(actor auth.Actor) (string, error) {
	if !actor.Can(PermEdit) || actor.OrgID == "" {
		return "", ErrForbidden
	}
	return actor.OrgID, nil
}

func kindOf(actor auth.Actor) domain.Kind {
	if actor.Type == "PROVIDER_MEMBER" {
		return domain.KindProvider
	}
	return domain.KindSeller
}

func (s *storefrontServiceImpl) Draft(ctx context.Context, actor auth.Actor) (domain.Storefront, error) {
	org, err := orgOf(actor)
	if err != nil {
		return domain.Storefront{}, err
	}
	d, err := s.repo.Draft(ctx, org)
	if errors.Is(err, domain.ErrNotFound) {
		return starter(org, kindOf(actor)), nil
	}
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("load draft: %w", err)
	}
	return d, nil
}

// starter is the template a new organisation begins with.
func starter(org string, kind domain.Kind) domain.Storefront {
	st := domain.Storefront{OrgID: org, Kind: kind, Status: domain.StatusDraft, PlanCode: "starter",
		Theme: domain.ThemeInput{Primary: "#067457", Accent: "#FFD400"}, Gallery: []domain.GalleryItem{},
		Featured: []domain.FeaturedPost{}, Social: []domain.SocialLink{}}
	for _, t := range domain.SectionTypes[kind] {
		st.Sections = append(st.Sections, domain.Section{Type: t, Enabled: t != "banner" && t != "social"})
	}
	if kind == domain.KindProvider {
		st.Provider = &domain.ProviderDesign{Layout: "CLASSIC", Header: "BAND", ButtonShape: "ROUNDED", GallerySize: 6}
	}
	return st
}

func (s *storefrontServiceImpl) SaveDraft(ctx context.Context, actor auth.Actor, in domain.Storefront) (domain.Storefront, error) {
	org, err := orgOf(actor)
	if err != nil {
		return domain.Storefront{}, err
	}
	current, err := s.Draft(ctx, actor)
	if err != nil {
		return domain.Storefront{}, err
	}
	in.OrgID, in.Kind, in.PlanCode, in.Status = org, current.Kind, current.PlanCode, domain.StatusDraft
	in.Slug = strings.ToLower(strings.TrimSpace(in.Slug))
	in.Social = domain.MergeSocial(current.Social, in.Social)
	if err := in.Validate(s.limits.For(in.PlanCode)); err != nil {
		return domain.Storefront{}, fmt.Errorf("save draft: %w", err)
	}
	vet, err := domain.Vet(in.Theme)
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("save draft: %w", errors.Join(domain.ErrInvalid, err))
	}
	in.Tokens = vet.Tokens
	if err := s.repo.SaveDraft(ctx, &in); err != nil {
		return domain.Storefront{}, fmt.Errorf("save draft: %w", err)
	}
	return in, nil
}

func (s *storefrontServiceImpl) Publish(ctx context.Context, actor auth.Actor) (domain.Storefront, domain.Vetting, error) {
	d, err := s.Draft(ctx, actor)
	if err != nil {
		return domain.Storefront{}, domain.Vetting{}, err
	}
	if d.Version == 0 {
		return domain.Storefront{}, domain.Vetting{}, fmt.Errorf("publish: %w: save the page first", domain.ErrInvalid)
	}
	if err := d.Validate(s.limits.For(d.PlanCode)); err != nil {
		return domain.Storefront{}, domain.Vetting{}, fmt.Errorf("publish: %w", err)
	}
	vet, err := domain.Vet(d.Theme)
	if err != nil {
		return domain.Storefront{}, domain.Vetting{}, fmt.Errorf("publish: %w", err)
	}
	if !vet.OK {
		return domain.Storefront{}, vet, domain.ErrThemeFails
	}
	now := s.now().UTC()
	d.Status, d.PublishedAt, d.Tokens = domain.StatusPublished, &now, vet.Tokens
	if err := s.repo.Publish(ctx, &d); err != nil {
		return domain.Storefront{}, vet, fmt.Errorf("publish: %w", err)
	}
	if s.purger != nil && s.baseURL != "" {
		_ = s.purger.Purge(ctx, []string{s.baseURL + "/s/" + d.Slug, s.baseURL + "/v1/storefronts/" + d.Slug})
	}
	return d, vet, nil
}

func (s *storefrontServiceImpl) Public(ctx context.Context, slug string) (domain.Storefront, error) {
	p, err := s.repo.Published(ctx, strings.ToLower(slug))
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("public storefront: %w", err)
	}
	return p.PublicView(), nil
}

func (s *storefrontServiceImpl) PendingLinks(ctx context.Context, actor auth.Actor) ([]domain.Storefront, error) {
	if !actor.Can(PermModerate) {
		return nil, ErrForbidden
	}
	out, err := s.repo.WithPendingLinks(ctx, 200)
	if err != nil {
		return nil, fmt.Errorf("pending links: %w", err)
	}
	return out, nil
}

func (s *storefrontServiceImpl) DecideLink(ctx context.Context, actor auth.Actor, orgID, network string, approve bool) (domain.Storefront, error) {
	if !actor.Can(PermModerate) {
		return domain.Storefront{}, ErrForbidden
	}
	d, err := s.repo.Draft(ctx, orgID)
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("load: %w", err)
	}
	found := false
	for i := range d.Social {
		if strings.EqualFold(d.Social[i].Network, network) {
			found = true
			d.Social[i].Status = domain.LinkRejected
			if approve {
				d.Social[i].Status = domain.LinkApproved
			}
		}
	}
	if !found {
		return domain.Storefront{}, domain.ErrNotFound
	}
	if d.Status == domain.StatusPublished {
		err = s.repo.Publish(ctx, &d)
	} else {
		err = s.repo.SaveDraft(ctx, &d)
	}
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("save decision: %w", err)
	}
	return d, nil
}

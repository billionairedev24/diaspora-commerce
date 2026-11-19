package app

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerStorePageService is Settings > Store page: the draft of the seller's storefront, its live preview, Publish and
// Unpublish, and the audit trail. Reading needs Team view; changing needs Team edit (the seller service further limits it
// to the owner and admins). Sellers and providers: each organisation edits its own page, with the sections of its kind.
type SellerStorePageService interface {
	Get(ctx context.Context, c *domain.Caller) (domain.StorePageSettings, error)
	SaveDraft(ctx context.Context, c *domain.Caller, in domain.StorePageDraftInput) (domain.StorePageSettings, error)
	Publish(ctx context.Context, c *domain.Caller, in domain.StorePageVersionInput) (domain.StorePageSettings, error)
	Unpublish(ctx context.Context, c *domain.Caller) (domain.StorePageSettings, error)
	Audit(ctx context.Context, c *domain.Caller) (domain.StorePageAudit, error)
	Preview(ctx context.Context, c *domain.Caller) (domain.Storefront, error)
	FindProducts(ctx context.Context, c *domain.Caller, text string) (domain.StorePageProductList, error)
}

type sellerStorePageImpl struct {
	r      *orgResolver
	store  StorefrontService
	policy domain.StorePolicy
}

var _ SellerStorePageService = (*sellerStorePageImpl)(nil)

// NewSellerStorePageService returns the SellerStorePageService.
func NewSellerStorePageService(d SellerDeps, store StorefrontService, policy domain.StorePolicy) SellerStorePageService {
	return &sellerStorePageImpl{r: newResolver(d), store: store, policy: policy}
}

func (s *sellerStorePageImpl) access(ctx context.Context, c *domain.Caller, level string) (sellerCtx, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return sc, err
	}
	if !sc.access.Allows("team", level) {
		return sc, domain.AccessDenied(sc.org.Role, level)
	}
	return sc, nil
}

// storeKind is the kind of storefront the caller's organisation has: a provider's, or (anything else) a seller's.
func storeKind(sc sellerCtx) string {
	if sc.kind() == domain.OrgKindProvider {
		return domain.StoreKindProvider
	}
	return domain.StoreKindSeller
}

func (s *sellerStorePageImpl) decode(raw json.RawMessage, kind string) domain.StoreConfig {
	if len(raw) == 0 || string(raw) == "null" {
		return domain.DefaultStoreConfigFor(kind)
	}
	var in domain.StoreConfig
	if json.Unmarshal(raw, &in) != nil {
		return domain.DefaultStoreConfigFor(kind)
	}
	return domain.SanitizeStoreConfig(in, s.policy.ForKind(kind))
}

func (s *sellerStorePageImpl) settings(ctx context.Context, c *domain.Caller, sc sellerCtx, up port.UpStorePage) (domain.StorePageSettings, error) {
	bp, err := s.r.d.Sellers.BusinessPageSettings(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	canEdit := domain.AtLeast(sc.access.Level("team"), domain.LevelEdit)
	kind := storeKind(sc)
	out := domain.StorePageSettings{Kind: kind, Slug: strPtr(bp.Slug), Name: bp.Name, LogoURL: strPtr(bp.LogoURL), ContactEmail: strPtr(bp.ContactEmail),
		Draft: s.decode(up.Draft, kind), IsPublished: up.IsPublished, Version: up.Version, PublishedVersion: up.PublishedVersion,
		HasUnpublishedChanges: up.HasUnpublishedChanges, UpdatedAt: up.UpdatedAt, PublishedAt: up.PublishedAt,
		Approved: up.Approved, CanEdit: canEdit, CanPublish: canEdit && up.Approved && bp.Slug != ""}
	if len(up.Published) > 0 && string(up.Published) != "null" {
		p := s.decode(up.Published, kind)
		out.Published = &p
	}
	return out, nil
}

func (s *sellerStorePageImpl) Get(ctx context.Context, c *domain.Caller) (domain.StorePageSettings, error) {
	sc, err := s.access(ctx, c, domain.LevelView)
	if err != nil {
		return domain.StorePageSettings{}, err
	}
	up, err := s.r.d.Sellers.StorePage(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	return s.settings(ctx, c, sc, up)
}

// validate checks the config and turns a failure into the 422 the editor shows next to the field.
func (s *sellerStorePageImpl) validate(in domain.StoreConfig, kind string) (domain.StoreConfig, error) {
	return s.validateWith(in, kind, nil)
}

// existingWebsite is the website links in the stored draft and published copy.
func existingWebsite(up port.UpStorePage) []string {
	var out []string
	for _, raw := range []json.RawMessage{up.Draft, up.Published} {
		var c domain.StoreConfig
		if len(raw) == 0 || json.Unmarshal(raw, &c) != nil {
			continue
		}
		for _, l := range c.Social {
			if l.Network == domain.NetworkWebsite {
				if u, ok := domain.SafeLink(l.URL); ok {
					out = append(out, u)
				}
			}
		}
	}
	return out
}

func (s *sellerStorePageImpl) validateWith(in domain.StoreConfig, kind string, website []string) (domain.StoreConfig, error) {
	pol := s.policy.ForKind(kind)
	pol.ExistingWebsite = website
	clean, errs := domain.ValidateStoreConfig(in, pol)
	if len(errs) == 0 {
		return clean, nil
	}
	if msg, ok := errs["theme.accent"]; ok && strings.Contains(msg, "too light") {
		p := domain.FieldProblem(domain.CodeStoreAccentLowContrast, "That colour is hard to read", "theme.accent", msg)
		for k, v := range errs {
			p.Errors[k] = v
		}
		return clean, p
	}
	return clean, domain.ValidationProblem(errs)
}

func (s *sellerStorePageImpl) SaveDraft(ctx context.Context, c *domain.Caller, in domain.StorePageDraftInput) (domain.StorePageSettings, error) {
	sc, err := s.access(ctx, c, domain.LevelEdit)
	if err != nil {
		return domain.StorePageSettings{}, err
	}
	// Website links already stored may stay (they cannot be added).
	cur, err := s.r.d.Sellers.StorePage(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	clean, err := s.validateWith(in.Config, storeKind(sc), existingWebsite(cur))
	if err != nil {
		return domain.StorePageSettings{}, err
	}
	raw, err := json.Marshal(clean)
	if err != nil {
		return domain.StorePageSettings{}, err
	}
	up, err := s.r.d.Sellers.SaveStorePage(ctx, c.BearerToken, sc.org.ID, raw, in.Version)
	if err != nil {
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	s.invalidate()
	return s.settings(ctx, c, sc, up)
}

func (s *sellerStorePageImpl) Publish(ctx context.Context, c *domain.Caller, in domain.StorePageVersionInput) (domain.StorePageSettings, error) {
	sc, err := s.access(ctx, c, domain.LevelEdit)
	if err != nil {
		return domain.StorePageSettings{}, err
	}
	cur, err := s.r.d.Sellers.StorePage(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	if len(cur.Draft) == 0 || string(cur.Draft) == "null" {
		return domain.StorePageSettings{}, domain.ValidationProblem(map[string]string{"form": "Save your changes before publishing."})
	}
	var draft domain.StoreConfig
	if json.Unmarshal(cur.Draft, &draft) != nil {
		return domain.StorePageSettings{}, domain.ValidationProblem(map[string]string{"form": "The saved draft cannot be read. Save it again."})
	}
	if _, err := s.validateWith(draft, storeKind(sc), existingWebsite(cur)); err != nil {
		return domain.StorePageSettings{}, err
	}
	up, err := s.r.d.Sellers.PublishStorePage(ctx, c.BearerToken, sc.org.ID, in.Version)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnprocessableEntity {
			return domain.StorePageSettings{}, domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeStoreNotApproved, "Not approved yet",
				"Your store page can be published once your business is approved.").WithCause(err)
		}
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	s.invalidate()
	return s.settings(ctx, c, sc, up)
}

func (s *sellerStorePageImpl) Unpublish(ctx context.Context, c *domain.Caller) (domain.StorePageSettings, error) {
	sc, err := s.access(ctx, c, domain.LevelEdit)
	if err != nil {
		return domain.StorePageSettings{}, err
	}
	up, err := s.r.d.Sellers.UnpublishStorePage(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.StorePageSettings{}, mapErr("seller", err)
	}
	s.invalidate()
	return s.settings(ctx, c, sc, up)
}

// invalidate is a hook for caches that outlive a request; the public page cache expires within a minute by itself, and a
// publish or unpublish is allowed to take that long to show.
func (s *sellerStorePageImpl) invalidate() {}

func (s *sellerStorePageImpl) Audit(ctx context.Context, c *domain.Caller) (domain.StorePageAudit, error) {
	sc, err := s.access(ctx, c, domain.LevelView)
	if err != nil {
		return domain.StorePageAudit{}, err
	}
	rows, err := s.r.d.Sellers.StorePageAudit(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.StorePageAudit{}, mapErr("seller", err)
	}
	out := domain.StorePageAudit{Items: make([]domain.StorePageAuditEntry, 0, len(rows))}
	for _, r := range rows {
		who := "A team member"
		if r.Actor == c.Subject {
			who = "You"
		}
		out.Items = append(out.Items, domain.StorePageAuditEntry{At: r.At.UTC().Truncate(time.Second), Action: r.Action, Actor: who, Version: r.Version})
	}
	return out, nil
}

// Preview is the storefront built from the DRAFT, for the live preview and the quick check before Publish. It is the caller's
// own organisation only (resolved from the token, never from a parameter) and is never reachable by address; publishing still
// needs the address (CanPublish).
func (s *sellerStorePageImpl) Preview(ctx context.Context, c *domain.Caller) (domain.Storefront, error) {
	sc, err := s.access(ctx, c, domain.LevelView)
	if err != nil {
		return domain.Storefront{}, err
	}
	up, err := s.r.d.Sellers.StorePage(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.Storefront{}, mapErr("seller", err)
	}
	bp, err := s.r.d.Sellers.BusinessPageSettings(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.Storefront{}, mapErr("seller", err)
	}
	// The preview never looks the store up by address: it is built from the caller's own organisation and draft, so it works
	// before the page address exists (made when the payout account is set up) and before the first publish. Slug may be "".
	return s.store.Build(ctx, StorefrontSource{Kind: storeKind(sc), OrgID: sc.org.ID, Slug: bp.Slug, Name: bp.Name, Description: bp.Description, LogoURL: bp.LogoURL,
		ContactEmail: bp.ContactEmail, Config: s.decode(up.Draft, storeKind(sc)), Preview: true})
}

func (s *sellerStorePageImpl) FindProducts(ctx context.Context, c *domain.Caller, text string) (domain.StorePageProductList, error) {
	sc, err := s.access(ctx, c, domain.LevelView)
	if err != nil {
		return domain.StorePageProductList{}, err
	}
	if storeKind(sc) == domain.StoreKindProvider {
		return domain.StorePageProductList{Items: []domain.ProductSummary{}}, nil // a provider has no products to feature
	}
	items, err := s.store.FindProducts(ctx, sc.org.ID, text)
	if err != nil {
		return domain.StorePageProductList{}, err
	}
	return domain.StorePageProductList{Items: items}, nil
}

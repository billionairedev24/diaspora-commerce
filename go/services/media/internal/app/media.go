// Package app holds the media use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"path"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// MediaService issues direct uploads, inspects what was uploaded and serves asset metadata.
type MediaService interface {
	RequestUpload(ctx context.Context, actor auth.Actor, p domain.Purpose, fileName, contentType string, size int64) (domain.Asset, port.Upload, error)
	Complete(ctx context.Context, actor auth.Actor, id string) (domain.Asset, error)
	// Get returns an asset; private assets carry a signed URL valid for linkTTL (0 = the default), bounded by the
	// purpose's link policy and the store, with its expiry in URLExpiresAt.
	Get(ctx context.Context, actor auth.Actor, id string, linkTTL time.Duration) (domain.Asset, error)
	List(ctx context.Context, actor auth.Actor, p domain.Purpose) ([]domain.Asset, error)
}

// ErrForbidden is returned when an actor touches another organisation's asset.
var ErrForbidden = errors.New("forbidden")

// ErrState is returned for an action that doesn't fit the asset status.
var ErrState = errors.New("invalid state")

type mediaServiceImpl struct {
	stores    map[domain.Purpose]port.ObjectStore
	repo      port.AssetRepository
	inspector port.Inspector
	links     domain.LinkPolicy
	now       func() time.Time
	uploadTTL time.Duration
}

var _ MediaService = (*mediaServiceImpl)(nil)

// NewMediaService routes each purpose to its store (UploadThing or R2 for public images, the private cloud bucket
// for KYC documents, proof photos and documents). links bounds signed download links; zero values fall back to
// domain.DefaultLinkTTL and domain.DefaultMaxLongLinkTTL.
func NewMediaService(stores map[domain.Purpose]port.ObjectStore, repo port.AssetRepository, inspector port.Inspector,
	links domain.LinkPolicy,
) MediaService {
	if links.Default <= 0 {
		links.Default = domain.DefaultLinkTTL
	}
	if links.MaxLong <= 0 {
		links.MaxLong = domain.DefaultMaxLongLinkTTL
	}
	return &mediaServiceImpl{stores: stores, repo: repo, inspector: inspector, links: links, now: time.Now,
		uploadTTL: 10 * time.Minute}
}

// maxInspect caps how much of an object is read for inspection.
const maxInspect = 16 << 20

func ownerOf(a auth.Actor) string {
	if a.OrgID != "" {
		return a.OrgID
	}
	return "user:" + a.Subject
}

func (s *mediaServiceImpl) store(p domain.Purpose) (port.ObjectStore, error) {
	st, ok := s.stores[p]
	if !ok {
		return nil, fmt.Errorf("%w: no storage configured for %s", domain.ErrRejected, p)
	}
	return st, nil
}

func (s *mediaServiceImpl) RequestUpload(ctx context.Context, actor auth.Actor, p domain.Purpose, fileName,
	contentType string, size int64,
) (domain.Asset, port.Upload, error) {
	if err := domain.CheckDeclared(p, fileName, contentType, size); err != nil {
		return domain.Asset{}, port.Upload{}, fmt.Errorf("request upload: %w", err)
	}
	if !mayUse(actor, p) {
		return domain.Asset{}, port.Upload{}, fmt.Errorf("upload %s: %w", p, ErrForbidden)
	}
	st, err := s.store(p)
	if err != nil {
		return domain.Asset{}, port.Upload{}, err
	}
	now := s.now().UTC()
	id := uuid.NewString()
	ext := strings.ToLower(path.Ext(fileName))
	key := fmt.Sprintf("%s/%s/%s/%s%s", p, sanitize(ownerOf(actor)), now.Format("2006/01"), id, ext)
	up, err := st.PrepareUpload(ctx, key, fileName, contentType, size, s.uploadTTL)
	if err != nil {
		return domain.Asset{}, port.Upload{}, fmt.Errorf("prepare upload: %w", err)
	}
	a := domain.Asset{ID: id, OwnerOrg: ownerOf(actor), UploadedBy: actor.Subject, Purpose: p, Provider: st.Name(),
		Key: up.Key, URL: up.PublicURL, FileName: fileName, ContentType: contentType, Bytes: size,
		Status: domain.StatusPending, Flags: []string{}, CreatedAt: now}
	if err := s.repo.Insert(ctx, a); err != nil {
		return domain.Asset{}, port.Upload{}, fmt.Errorf("record asset: %w", err)
	}
	return a, up, nil
}

func (s *mediaServiceImpl) Complete(ctx context.Context, actor auth.Actor, id string) (domain.Asset, error) {
	a, err := s.owned(ctx, actor, id)
	if err != nil {
		return domain.Asset{}, err
	}
	if a.Status != domain.StatusPending {
		return a, nil // idempotent: completing twice returns the stored result
	}
	st, err := s.store(a.Purpose)
	if err != nil {
		return domain.Asset{}, err
	}
	body, size, _, err := st.Open(ctx, a.Key, maxInspect)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("%w: upload not found in storage yet", ErrState)
	}
	in, inspectErr := s.inspector.Inspect(body, size, a.ContentType)
	_ = body.Close()
	status, flags := domain.Judge(a.Purpose, in)
	if inspectErr != nil {
		status, flags = domain.StatusRejected, []string{"file could not be read as " + a.ContentType}
	}
	a.ContentType, a.Bytes, a.Width, a.Height, a.Status, a.Flags = in.ContentType, in.Bytes, in.Width, in.Height, status, flags
	if status == domain.StatusRejected {
		_ = st.Delete(ctx, a.Key)
		a.URL = ""
	}
	if err := s.repo.Update(ctx, a); err != nil {
		return domain.Asset{}, fmt.Errorf("update asset: %w", err)
	}
	return a, nil
}

func (s *mediaServiceImpl) Get(ctx context.Context, actor auth.Actor, id string, linkTTL time.Duration) (domain.Asset, error) {
	a, err := s.repo.Get(ctx, id)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("get asset: %w", err)
	}
	rules, _ := domain.RulesFor(a.Purpose)
	if rules.Public && a.Status == domain.StatusReady {
		return a, nil
	}
	if !mayRead(actor, a) {
		return domain.Asset{}, ErrForbidden
	}
	if !rules.Public && a.Status != domain.StatusRejected {
		st, err := s.store(a.Purpose)
		if err != nil {
			return domain.Asset{}, err
		}
		ttl := s.links.TTL(rules, linkTTL, st.MaxSignedURLTTL())
		u, err := st.SignedGetURL(ctx, a.Key, ttl)
		if err != nil {
			return domain.Asset{}, fmt.Errorf("sign url: %w", err)
		}
		expires := s.now().UTC().Add(ttl).Truncate(time.Second)
		a.URL, a.URLExpiresAt = u, &expires
	}
	return a, nil
}

func (s *mediaServiceImpl) List(ctx context.Context, actor auth.Actor, p domain.Purpose) ([]domain.Asset, error) {
	as, err := s.repo.ListByOrg(ctx, ownerOf(actor), p, 100)
	if err != nil {
		return nil, fmt.Errorf("list assets: %w", err)
	}
	return as, nil
}

func (s *mediaServiceImpl) owned(ctx context.Context, actor auth.Actor, id string) (domain.Asset, error) {
	a, err := s.repo.Get(ctx, id)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("get asset: %w", err)
	}
	if actor.Subject != a.UploadedBy && (!actor.IsStaff() || !mayUse(actor, a.Purpose)) {
		return domain.Asset{}, ErrForbidden
	}
	return a, nil
}

// mayUse reports whether the actor's kind may work with a purpose at all. Purposes that name their service clients
// refuse every other service principal (and, when staff-only, every human but staff); the named clients, whose own
// tokens media accepts for those purposes, are kept to the purposes that name them.
func mayUse(actor auth.Actor, p domain.Purpose) bool {
	rules, err := domain.RulesFor(p)
	if err != nil {
		return false
	}
	if actor.Type == principalService {
		if rules.Restricted() {
			return rules.AllowsServiceClient(actor.Subject)
		}
		return !slices.Contains(domain.ServiceClients(), actor.Subject)
	}
	return !rules.StaffOnly || actor.Type == principalStaff
}

// mayRead reports whether the actor may read a private (or not yet approved) asset: its uploader, its owner
// organisation, or staff and service principals allowed for the purpose.
func mayRead(actor auth.Actor, a domain.Asset) bool {
	if actor.Subject == a.UploadedBy && actor.Subject != "" {
		return true
	}
	if actor.IsStaff() {
		return mayUse(actor, a.Purpose)
	}
	return ownerOf(actor) == a.OwnerOrg
}

const (
	principalService = "SERVICE"
	principalStaff   = "STAFF"
)

func sanitize(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' {
			return r
		}
		return '_'
	}, s)
}

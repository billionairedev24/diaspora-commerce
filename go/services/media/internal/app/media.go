// Package app holds the media use cases.
package app

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"path"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// MediaService issues direct uploads, inspects and moderates what was uploaded and serves asset metadata.
type MediaService interface {
	RequestUpload(ctx context.Context, actor auth.Actor, p domain.Purpose, fileName, contentType string, size int64) (domain.Asset, port.Upload, error)
	// Complete inspects the uploaded object; public images are also moderated (EXIF stripping, perceptual-hash
	// duplicates, the AI hook) and published only when moderation passes.
	Complete(ctx context.Context, actor auth.Actor, id string) (domain.Asset, error)
	// Get returns an asset; private assets carry a signed URL valid for linkTTL (0 = the default), bounded by the
	// purpose's link policy and the store, with its expiry in URLExpiresAt. Long links (beyond the default) of
	// document purposes are media-hosted redirect links when a link signer is configured.
	Get(ctx context.Context, actor auth.Actor, id string, linkTTL time.Duration) (domain.Asset, error)
	List(ctx context.Context, actor auth.Actor, p domain.Purpose) ([]domain.Asset, error)
	// ResolveLink checks a media-hosted link token and returns a fresh short-lived download URL.
	ResolveLink(ctx context.Context, token string) (string, error)
	// Review releases (APPROVE) or rejects (REJECT) an image held by moderation (staff only).
	Review(ctx context.Context, actor auth.Actor, id string, decision domain.ReviewDecision, note string) (domain.Asset, error)
}

// ErrForbidden is returned when an actor touches another organisation's asset.
var ErrForbidden = errors.New("forbidden")

// ErrState is returned for an action that doesn't fit the asset status.
var ErrState = errors.New("invalid state")

// ErrLinkInvalid is returned for a link token that is malformed, forged or names an asset that can't be served.
var ErrLinkInvalid = errors.New("link not found")

// ErrLinkExpired is returned for a valid link token past its expiry.
var ErrLinkExpired = errors.New("link expired")

// ModeratePermissions release or reject held images (media's own key, or catalog listing moderation).
var ModeratePermissions = []string{"media:moderate", "catalog:moderate"}

// Option configures optional collaborators.
type Option func(*mediaServiceImpl)

// WithModeration gates public images: processor strips metadata and hashes them; moderator (may be nil) is the AI
// hook, called with aiTimeout.
func WithModeration(processor port.ImageProcessor, moderator port.ImageModerator, policy domain.ModerationPolicy,
	aiTimeout time.Duration,
) Option {
	return func(s *mediaServiceImpl) {
		s.processor, s.moderator, s.policy, s.aiTimeout = processor, moderator, policy, aiTimeout
		if s.aiTimeout <= 0 {
			s.aiTimeout = 10 * time.Second
		}
	}
}

// WithLinks enables media-hosted long-lived links: baseURL is media's public URL (links are <baseURL>/v1/l/<token>),
// redirectTTL the lifetime of the storage URL each click is redirected to.
func WithLinks(signer port.LinkSigner, baseURL string, redirectTTL time.Duration) Option {
	return func(s *mediaServiceImpl) {
		s.signer, s.linkBase, s.redirectTTL = signer, strings.TrimSuffix(baseURL, "/"), redirectTTL
		if s.redirectTTL <= 0 {
			s.redirectTTL = domain.DefaultLinkTTL
		}
	}
}

// WithClock replaces time.Now (tests).
func WithClock(now func() time.Time) Option { return func(s *mediaServiceImpl) { s.now = now } }

// WithLogger sets the logger (default slog.Default()).
func WithLogger(log *slog.Logger) Option { return func(s *mediaServiceImpl) { s.log = log } }

type mediaServiceImpl struct {
	stores    map[domain.Purpose]port.ObjectStore
	repo      port.AssetRepository
	inspector port.Inspector
	links     domain.LinkPolicy
	now       func() time.Time
	uploadTTL time.Duration
	log       *slog.Logger

	processor port.ImageProcessor
	moderator port.ImageModerator
	policy    domain.ModerationPolicy
	aiTimeout time.Duration

	signer      port.LinkSigner
	linkBase    string
	redirectTTL time.Duration
}

var _ MediaService = (*mediaServiceImpl)(nil)

// NewMediaService routes each purpose to its store (UploadThing or R2 for public images, the private cloud bucket
// for KYC documents, proof photos and documents). links bounds signed download links; zero values fall back to
// domain.DefaultLinkTTL and domain.DefaultMaxLongLinkTTL.
func NewMediaService(stores map[domain.Purpose]port.ObjectStore, repo port.AssetRepository, inspector port.Inspector,
	links domain.LinkPolicy, opts ...Option,
) MediaService {
	if links.Default <= 0 {
		links.Default = domain.DefaultLinkTTL
	}
	if links.MaxLong <= 0 {
		links.MaxLong = domain.DefaultMaxLongLinkTTL
	}
	s := &mediaServiceImpl{stores: stores, repo: repo, inspector: inspector, links: links, now: time.Now,
		uploadTTL: 10 * time.Minute, log: slog.Default()}
	for _, o := range opts {
		o(s)
	}
	return s
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

// moderated reports whether uploads of the purpose go through the moderation gate.
func (s *mediaServiceImpl) moderated(p domain.Purpose) bool {
	return s.processor != nil && domain.ModeratedPurpose(p)
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
	moderated := s.moderated(p)
	if moderated {
		// Not public until moderation releases it.
		key = domain.QuarantinePrefix + key
	}
	up, err := st.PrepareUpload(ctx, key, fileName, contentType, size, s.uploadTTL)
	if err != nil {
		return domain.Asset{}, port.Upload{}, fmt.Errorf("prepare upload: %w", err)
	}
	if moderated {
		up.PublicURL = ""
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
	moderated := s.moderated(a.Purpose)
	var data []byte
	var reader io.Reader = body
	if moderated {
		data, err = io.ReadAll(io.LimitReader(body, maxInspect))
		if err != nil {
			_ = body.Close()
			return domain.Asset{}, fmt.Errorf("%w: upload could not be read: %w", ErrState, err)
		}
		reader = bytes.NewReader(data)
	}
	in, inspectErr := s.inspector.Inspect(reader, size, a.ContentType)
	_ = body.Close()
	status, flags := domain.Judge(a.Purpose, in)
	if inspectErr != nil {
		status, flags = domain.StatusRejected, []string{"file could not be read as " + a.ContentType}
	}
	a.ContentType, a.Bytes, a.Width, a.Height, a.Status, a.Flags = in.ContentType, in.Bytes, in.Width, in.Height, status, flags
	switch {
	case status == domain.StatusRejected:
		_ = st.Delete(ctx, a.Key)
		a.URL = ""
	case moderated:
		if err := s.moderate(ctx, st, &a, data); err != nil {
			return domain.Asset{}, err
		}
	}
	return s.save(ctx, a)
}

// save writes the asset; when another request changed it first (a concurrent completion), the stored copy wins.
func (s *mediaServiceImpl) save(ctx context.Context, a domain.Asset) (domain.Asset, error) {
	if err := s.repo.Update(ctx, a); err != nil {
		if errors.Is(err, domain.ErrConflict) {
			if cur, gerr := s.repo.Get(ctx, a.ID); gerr == nil {
				return cur, nil
			}
		}
		return domain.Asset{}, fmt.Errorf("update asset: %w", err)
	}
	a.Version++
	return a, nil
}

// moderate runs the gate on an image that passed the purpose rules: strip metadata, hash, look for duplicates, ask
// the AI hook, then publish (PASSED), keep it private for review (HELD) or delete it (BLOCKED).
func (s *mediaServiceImpl) moderate(ctx context.Context, st port.ObjectStore, a *domain.Asset, data []byte) error {
	img, perr := s.processor.Process(data, a.ContentType)
	if perr != nil {
		// Not a service failure: the file itself can't be cleaned (corrupt, too many pixels), so it is rejected.
		a.Status, a.Flags, a.URL = domain.StatusRejected, []string{"image could not be processed: " + perr.Error()}, ""
		_ = st.Delete(ctx, a.Key)
		return nil //nolint:nilerr // the outcome is recorded on the asset
	}
	hash := img.Hash
	a.PHash = &hash
	input := domain.ModerationInput{OwnerOrg: a.OwnerOrg, MetadataStripped: img.Stripped, AIOff: s.moderator == nil}
	if s.policy.DuplicateDistance >= 0 {
		similar, err := s.repo.SimilarImages(ctx, publicPurposes(), hash, s.policy.DuplicateDistance, a.ID, 5)
		if err != nil {
			s.log.WarnContext(ctx, "duplicate check failed; continuing without it", "asset_id", a.ID, "error", err)
		}
		input.Similar = similar
	}
	if s.moderator != nil {
		actx, cancel := context.WithTimeout(ctx, s.aiTimeout)
		v, err := s.moderator.Moderate(actx, img.Preview, "image/jpeg", a.Purpose)
		cancel()
		if err != nil {
			s.log.WarnContext(ctx, "AI moderation unavailable", "asset_id", a.ID, "fail_open", s.policy.AIFailOpen,
				"error", err)
			input.AIErr = err
		} else {
			input.AI = &v
		}
	}
	m := domain.Moderate(s.policy, input)
	a.Moderation = &m
	a.URL = ""
	switch m.Verdict {
	case domain.VerdictBlocked:
		a.Status, a.Flags = domain.StatusRejected, append(a.Flags, m.Reasons...)
		_ = st.Delete(ctx, a.Key)
		return nil
	case domain.VerdictHeld:
		a.Status, a.Flags = domain.StatusFlagged, append(a.Flags, m.Reasons...)
		return nil
	case domain.VerdictPassed:
	}
	return s.publish(ctx, st, a, img)
}

// publish writes the cleaned image under its public key and removes the quarantined upload.
func (s *mediaServiceImpl) publish(ctx context.Context, st port.ObjectStore, a *domain.Asset, img port.ProcessedImage) error {
	finalKey := strings.TrimPrefix(a.Key, domain.QuarantinePrefix)
	if finalKey == a.Key {
		// The store named the upload itself (UploadThing file keys): publish under our own key.
		finalKey = fmt.Sprintf("%s/%s/%s%s", a.Purpose, sanitize(a.OwnerOrg), a.ID, strings.ToLower(path.Ext(a.FileName)))
	}
	key, publicURL, err := st.Put(ctx, finalKey, a.FileName, a.ContentType, img.Data, true)
	if err != nil {
		return fmt.Errorf("publish image: %w", err)
	}
	if key != a.Key {
		if err := st.Delete(ctx, a.Key); err != nil {
			s.log.WarnContext(ctx, "quarantined upload not deleted", "asset_id", a.ID, "error", err)
		}
	}
	a.Key, a.URL, a.Bytes = key, publicURL, int64(len(img.Data))
	if img.Width > 0 {
		a.Width, a.Height = img.Width, img.Height
	}
	return nil
}

func publicPurposes() []domain.Purpose {
	var out []domain.Purpose
	for _, p := range domain.Purposes() {
		if domain.ModeratedPurpose(p) {
			out = append(out, p)
		}
	}
	return out
}

func (s *mediaServiceImpl) Get(ctx context.Context, actor auth.Actor, id string, linkTTL time.Duration) (domain.Asset, error) {
	a, err := s.repo.Get(ctx, id)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("get asset: %w", err)
	}
	rules, _ := domain.RulesFor(a.Purpose)
	published := a.Moderation.Published() && a.URL != ""
	if rules.Public && (a.Status == domain.StatusReady || a.Status == domain.StatusFlagged) && published {
		return a, nil
	}
	if !mayRead(actor, a) {
		return domain.Asset{}, ErrForbidden
	}
	switch {
	case rules.Public:
		// A held image is shown to its owner and to staff through a short-lived signed URL.
		if a.Status == domain.StatusFlagged && !a.Moderation.Published() {
			return s.signed(ctx, a, s.links.Default)
		}
		return a, nil
	case a.Status == domain.StatusRejected:
		return a, nil
	case rules.LongLinks && s.signer != nil && linkTTL > s.links.Default:
		// Media-hosted link: lasts as long as the policy allows on every cloud, whatever the store can sign.
		ttl := s.links.TTL(rules, linkTTL, 0)
		expires := s.now().UTC().Add(ttl).Truncate(time.Second)
		token, err := s.signer.Sign(a.ID, expires)
		if err != nil {
			return domain.Asset{}, fmt.Errorf("sign link: %w", err)
		}
		a.URL, a.URLExpiresAt = s.linkBase+"/v1/l/"+token, &expires
		return a, nil
	default:
		st, err := s.store(a.Purpose)
		if err != nil {
			return domain.Asset{}, err
		}
		return s.signed(ctx, a, s.links.TTL(rules, linkTTL, st.MaxSignedURLTTL()))
	}
}

func (s *mediaServiceImpl) signed(ctx context.Context, a domain.Asset, ttl time.Duration) (domain.Asset, error) {
	st, err := s.store(a.Purpose)
	if err != nil {
		return domain.Asset{}, err
	}
	if limit := st.MaxSignedURLTTL(); limit > 0 {
		ttl = min(ttl, limit)
	}
	u, err := st.SignedGetURL(ctx, a.Key, ttl)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("sign url: %w", err)
	}
	expires := s.now().UTC().Add(ttl).Truncate(time.Second)
	a.URL, a.URLExpiresAt = u, &expires
	return a, nil
}

func (s *mediaServiceImpl) ResolveLink(ctx context.Context, token string) (string, error) {
	if s.signer == nil {
		return "", ErrLinkInvalid
	}
	id, expires, err := s.signer.Verify(token)
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrLinkInvalid, err)
	}
	if !s.now().Before(expires) {
		return "", ErrLinkExpired
	}
	a, err := s.repo.Get(ctx, id)
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrLinkInvalid, err)
	}
	rules, err := domain.RulesFor(a.Purpose)
	if err != nil || !rules.LongLinks || (a.Status != domain.StatusReady && a.Status != domain.StatusFlagged) {
		return "", ErrLinkInvalid
	}
	st, err := s.store(a.Purpose)
	if err != nil {
		return "", err
	}
	ttl := s.redirectTTL
	if limit := st.MaxSignedURLTTL(); limit > 0 {
		ttl = min(ttl, limit)
	}
	u, err := st.SignedGetURL(ctx, a.Key, ttl)
	if err != nil {
		return "", fmt.Errorf("sign url: %w", err)
	}
	return u, nil
}

func (s *mediaServiceImpl) Review(ctx context.Context, actor auth.Actor, id string, decision domain.ReviewDecision,
	note string,
) (domain.Asset, error) {
	if actor.Type != principalStaff || !slices.ContainsFunc(ModeratePermissions, actor.Can) {
		return domain.Asset{}, ErrForbidden
	}
	a, err := s.repo.Get(ctx, id)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("get asset: %w", err)
	}
	if !s.moderated(a.Purpose) || a.Moderation == nil || a.Moderation.Verdict != domain.VerdictHeld {
		return domain.Asset{}, fmt.Errorf("%w: only images held by moderation can be reviewed", ErrState)
	}
	st, err := s.store(a.Purpose)
	if err != nil {
		return domain.Asset{}, err
	}
	m := *a.Moderation
	at := s.now().UTC()
	m.ReviewedBy, m.ReviewedAt = actor.Subject, &at
	held := m.Reasons
	a.Flags = slices.DeleteFunc(slices.Clone(a.Flags), func(f string) bool { return slices.Contains(held, f) })
	note = strings.TrimSpace(note)
	switch decision {
	case domain.ReviewApprove:
		data, err := s.read(ctx, st, a.Key)
		if err != nil {
			return domain.Asset{}, err
		}
		img, err := s.processor.Process(data, a.ContentType)
		if err != nil {
			return domain.Asset{}, fmt.Errorf("%w: image could not be processed: %w", ErrState, err)
		}
		if err := s.publish(ctx, st, &a, img); err != nil {
			return domain.Asset{}, err
		}
		m.Verdict = domain.VerdictPassed
		m.Reasons = append(slices.Clone(held), "released by staff")
		a.Status = domain.StatusForFlags(a.Flags)
	case domain.ReviewReject:
		_ = st.Delete(ctx, a.Key)
		reason := "rejected by staff"
		if note != "" {
			reason += ": " + note
		}
		m.Verdict, m.Reasons = domain.VerdictBlocked, append(slices.Clone(held), reason)
		a.Status, a.URL, a.Flags = domain.StatusRejected, "", append(a.Flags, reason)
	default:
		return domain.Asset{}, fmt.Errorf("%w: decision must be APPROVE or REJECT", domain.ErrRejected)
	}
	a.Moderation = &m
	return s.save(ctx, a)
}

func (s *mediaServiceImpl) read(ctx context.Context, st port.ObjectStore, key string) ([]byte, error) {
	body, _, _, err := st.Open(ctx, key, maxInspect)
	if err != nil {
		return nil, fmt.Errorf("%w: object not found in storage: %w", ErrState, err)
	}
	defer func() { _ = body.Close() }()
	data, err := io.ReadAll(io.LimitReader(body, maxInspect))
	if err != nil {
		return nil, fmt.Errorf("read object: %w", err)
	}
	return data, nil
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
	if readerClient(actor) {
		return false // read-only clients never upload, complete or manage assets
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
	if readerClient(actor) {
		// A reader client (delivery-runs) checks public assets that are not published yet (pending, held).
		rules, err := domain.RulesFor(a.Purpose)
		return err == nil && rules.Public && actor.Can(domain.PermissionMediaRead)
	}
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

// readerClient reports whether the actor is a read-only service client's own token (domain.ReaderClients).
func readerClient(actor auth.Actor) bool {
	return actor.Type == principalService && slices.Contains(domain.ReaderClients(), actor.Subject)
}

package app_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/imageproc"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/linktoken"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// fakeModerator plays the ai-gateway hook (never a real model).
type fakeModerator struct {
	verdict domain.AIVerdict
	err     error
	calls   int
	preview []byte
}

func (f *fakeModerator) Moderate(_ context.Context, preview []byte, contentType string, _ domain.Purpose) (domain.AIVerdict, error) {
	f.calls++
	f.preview = preview
	if contentType != "image/jpeg" {
		return domain.AIVerdict{}, errors.New("previews are JPEG")
	}
	return f.verdict, f.err
}

// productPhoto is a 1200×1200 photo on white with a pattern that differs per seed (distinct perceptual hashes).
func productPhoto(t *testing.T, seed int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 1200, 1200))
	for y := range 1200 {
		for x := range 1200 {
			c := color.RGBA{R: 255, G: 255, B: 255, A: 255}
			if x > 150 && x < 1050 && y > 150 && y < 1050 {
				band := ((x/(40+seed*17))+(y/(60+seed*11))*seed)%3 == 0
				if band {
					c = color.RGBA{R: uint8(30 * seed), G: 90, B: 160, A: 255} //nolint:gosec // test colours
				} else {
					c = color.RGBA{R: 240, G: uint8(200 - 20*seed), B: 40, A: 255} //nolint:gosec // test colours
				}
			}
			img.Set(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 85}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// withGPS inserts an EXIF segment carrying a location, as phone cameras write it.
func withGPS(jpg []byte) []byte {
	payload := append([]byte("Exif\x00\x00II*\x00\x08\x00\x00\x00\x00\x00"), []byte("GPS 6.5244N 3.3792E")...)
	seg := []byte{0xFF, 0xE1}
	seg = binary.BigEndian.AppendUint16(seg, uint16(len(payload)+2)) //nolint:gosec // small test segment
	out := append([]byte{}, jpg[:2]...)
	out = append(append(out, seg...), payload...)
	return append(out, jpg[2:]...)
}

type moderationFixture struct {
	svc   app.MediaService
	store *memStore
	repo  *memRepo
	ai    *fakeModerator
}

func newModeration(policy domain.ModerationPolicy, ai *fakeModerator) moderationFixture {
	st := &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	stores := map[domain.Purpose]port.ObjectStore{domain.PurposeProductImage: st, domain.PurposeStorefront: st}
	var moderator port.ImageModerator
	if ai != nil {
		moderator = ai
	}
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000), domain.LinkPolicy{},
		app.WithModeration(imageproc.New(40_000_000), moderator, policy, time.Second))
	return moderationFixture{svc: svc, store: st, repo: repo, ai: ai}
}

func (f moderationFixture) upload(t *testing.T, actor auth.Actor, body []byte) (domain.Asset, port.Upload) {
	t.Helper()
	ctx := context.Background()
	a, up, err := f.svc.RequestUpload(ctx, actor, domain.PurposeProductImage, "dress.jpg", "image/jpeg", int64(len(body)))
	if err != nil {
		t.Fatal(err)
	}
	f.store.objects[up.Key] = body
	done, err := f.svc.Complete(ctx, actor, a.ID)
	if err != nil {
		t.Fatal(err)
	}
	return done, up
}

var allow = domain.AIVerdict{Decision: domain.AIAllow, Labels: []string{}, Model: "fake/vision"}

func TestCleanProductImageIsStrippedAndPublishedAfterModeration(t *testing.T) {
	f := newModeration(domain.ModerationPolicy{DuplicateDistance: 6}, &fakeModerator{verdict: allow})
	ctx := context.Background()
	a, up, err := f.svc.RequestUpload(ctx, sellerMember, domain.PurposeProductImage, "dress.jpg", "image/jpeg", 1000)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(up.Key, domain.QuarantinePrefix) || a.URL != "" || up.PublicURL != "" {
		t.Fatalf("uploads land in quarantine without a public URL: %+v %+v", a, up)
	}
	f.store.objects[up.Key] = withGPS(productPhoto(t, 1))
	done, err := f.svc.Complete(ctx, sellerMember, a.ID)
	if err != nil {
		t.Fatal(err)
	}
	m := done.Moderation
	if done.Status != domain.StatusReady || m == nil || m.Verdict != domain.VerdictPassed || !m.MetadataStripped ||
		m.AI != domain.AIChecked {
		t.Fatalf("published asset %+v moderation %+v", done, m)
	}
	if strings.HasPrefix(done.Key, domain.QuarantinePrefix) || done.URL != "https://cdn/"+done.Key {
		t.Fatalf("image must be published under its public key: key %s url %s", done.Key, done.URL)
	}
	if _, still := f.store.objects[up.Key]; still {
		t.Fatal("the quarantined upload must be deleted")
	}
	if bytes.Contains(f.store.objects[done.Key], []byte("GPS")) {
		t.Fatal("the published image must not carry EXIF / GPS")
	}
	if done.Width != 1200 || done.Height != 1200 || done.Bytes != int64(len(f.store.objects[done.Key])) {
		t.Fatalf("builder metadata (width, height, bytes) %+v", done)
	}
	if f.ai.calls != 1 || len(f.ai.preview) == 0 || len(f.ai.preview) >= len(f.store.objects[done.Key]) {
		t.Fatalf("the AI hook gets a small preview: calls %d, %d bytes", f.ai.calls, len(f.ai.preview))
	}
	// Anyone may read a published image's metadata.
	if got, err := f.svc.Get(ctx, customer, done.ID, 0); err != nil || got.URL != done.URL {
		t.Fatalf("public read: %+v %v", got, err)
	}
	// Completing again is idempotent and does not re-run moderation.
	if again, err := f.svc.Complete(ctx, sellerMember, a.ID); err != nil || again.Key != done.Key || f.ai.calls != 1 {
		t.Fatalf("second completion: %+v %v (AI calls %d)", again, err, f.ai.calls)
	}
}

func TestAIReviewHoldsTheImageUntilStaffRelease(t *testing.T) {
	f := newModeration(domain.ModerationPolicy{DuplicateDistance: 6},
		&fakeModerator{verdict: domain.AIVerdict{Decision: domain.AIReview, Labels: []string{"WATERMARK"}}})
	ctx := context.Background()
	held, up := f.upload(t, sellerMember, productPhoto(t, 2))
	if held.Status != domain.StatusFlagged || held.Moderation.Verdict != domain.VerdictHeld || held.URL != "" {
		t.Fatalf("held image %+v", held)
	}
	if held.Key != up.Key || f.store.objects[up.Key] == nil || len(f.store.puts) != 0 {
		t.Fatal("a held image stays in quarantine, unpublished")
	}
	if _, err := f.svc.Get(ctx, customer, held.ID, 0); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("shoppers must not see held images, got %v", err)
	}
	own, err := f.svc.Get(ctx, sellerMember, held.ID, 0)
	if err != nil || !strings.HasPrefix(own.URL, "https://signed/"+domain.QuarantinePrefix) || own.URLExpiresAt == nil {
		t.Fatalf("the seller previews it through a short signed URL: %+v %v", own, err)
	}
	// Only staff with a moderation permission may release it.
	moderator := auth.Actor{Subject: "staff-9", Type: "STAFF", Permissions: []string{"catalog:moderate"}}
	for _, who := range []auth.Actor{sellerMember, staff} {
		if _, err := f.svc.Review(ctx, who, held.ID, domain.ReviewApprove, ""); !errors.Is(err, app.ErrForbidden) {
			t.Fatalf("%s must not review, got %v", who.Subject, err)
		}
	}
	released, err := f.svc.Review(ctx, moderator, held.ID, domain.ReviewApprove, "logo is the brand's own")
	if err != nil {
		t.Fatal(err)
	}
	if released.Status != domain.StatusReady || released.URL == "" || released.Moderation.Verdict != domain.VerdictPassed ||
		released.Moderation.ReviewedBy != "staff-9" || len(released.Flags) != 1 {
		t.Fatalf("released image %+v %+v", released, released.Moderation)
	}
	if _, err := f.svc.Review(ctx, moderator, held.ID, domain.ReviewReject, ""); !errors.Is(err, app.ErrState) {
		t.Fatalf("a released image is no longer held, got %v", err)
	}
}

func TestAIBlockRejectsAndDeletes(t *testing.T) {
	f := newModeration(domain.ModerationPolicy{DuplicateDistance: 6},
		&fakeModerator{verdict: domain.AIVerdict{Decision: domain.AIBlock, Labels: []string{"WEAPONS"}}})
	done, up := f.upload(t, sellerMember, productPhoto(t, 3))
	if done.Status != domain.StatusRejected || done.Moderation.Verdict != domain.VerdictBlocked || done.URL != "" {
		t.Fatalf("blocked image %+v", done)
	}
	if _, ok := f.store.objects[up.Key]; ok || !strings.Contains(strings.Join(done.Flags, ","), "WEAPONS") {
		t.Fatalf("blocked upload must be deleted and the reason flagged: %v", done.Flags)
	}
}

func TestAIOutageFailsClosedOrOpenAsConfigured(t *testing.T) {
	down := errors.New("ai-gateway: status 503")
	closed := newModeration(domain.ModerationPolicy{DuplicateDistance: 6}, &fakeModerator{err: down})
	held, _ := closed.upload(t, sellerMember, productPhoto(t, 4))
	if held.Status != domain.StatusFlagged || held.Moderation.Verdict != domain.VerdictHeld ||
		held.Moderation.AI != domain.AIUnavailable || held.URL != "" {
		t.Fatalf("fail closed must hold: %+v %+v", held, held.Moderation)
	}
	open := newModeration(domain.ModerationPolicy{AIFailOpen: true, DuplicateDistance: 6}, &fakeModerator{err: down})
	pub, _ := open.upload(t, sellerMember, productPhoto(t, 4))
	if pub.Status != domain.StatusReady || pub.URL == "" || pub.Moderation.AI != domain.AIUnavailable ||
		!strings.Contains(strings.Join(pub.Moderation.Reasons, ","), "skipped") {
		t.Fatalf("fail open must publish with a note: %+v %+v", pub, pub.Moderation)
	}
	// Without an AI hook the rules and duplicate checks still gate the image.
	off := newModeration(domain.ModerationPolicy{DuplicateDistance: 6}, nil)
	plain, _ := off.upload(t, sellerMember, productPhoto(t, 4))
	if plain.Status != domain.StatusReady || plain.Moderation.AI != domain.AIOff {
		t.Fatalf("no AI hook: %+v", plain.Moderation)
	}
}

func TestDuplicatePhotosOfAnotherSellerAreHeld(t *testing.T) {
	f := newModeration(domain.ModerationPolicy{DuplicateDistance: 6}, &fakeModerator{verdict: allow})
	photo := productPhoto(t, 5)
	first, _ := f.upload(t, sellerMember, photo)
	if first.Moderation.Verdict != domain.VerdictPassed || first.Moderation.DuplicateOf != "" {
		t.Fatalf("first upload %+v", first.Moderation)
	}
	// The same seller reusing a photo (another variant) is only noted.
	again, _ := f.upload(t, sellerMember, withGPS(photo))
	if again.Moderation.Verdict != domain.VerdictPassed || again.Moderation.DuplicateOf != first.ID {
		t.Fatalf("own duplicate %+v", again.Moderation)
	}
	// Another seller uploading it (possibly lifted from the first listing) is held for review.
	thief := auth.Actor{Subject: "u9", Type: "SELLER_MEMBER", OrgID: "org_other"}
	copied, _ := f.upload(t, thief, photo)
	if copied.Status != domain.StatusFlagged || copied.Moderation.Verdict != domain.VerdictHeld ||
		copied.Moderation.DuplicateOf == "" || copied.URL != "" {
		t.Fatalf("another seller's copy %+v %+v", copied, copied.Moderation)
	}
	// A different photo is not a duplicate.
	other, _ := f.upload(t, thief, productPhoto(t, 6))
	if other.Moderation.DuplicateOf != "" || other.Moderation.Verdict != domain.VerdictPassed {
		t.Fatalf("different photo %+v", other.Moderation)
	}
}

func TestDisguisedProductImageIsRejectedBeforeModeration(t *testing.T) {
	f := newModeration(domain.ModerationPolicy{DuplicateDistance: 6}, &fakeModerator{verdict: allow})
	done, up := f.upload(t, sellerMember, []byte("<html><script>alert(1)</script></html>"))
	if done.Status != domain.StatusRejected || f.ai.calls != 0 {
		t.Fatalf("rules reject before the AI is asked: %+v (calls %d)", done, f.ai.calls)
	}
	if _, ok := f.store.objects[up.Key]; ok {
		t.Fatal("rejected upload must be deleted")
	}
}

const linkKey = "media-link-signing-key-0123456789abcdef"

func TestMediaHostedLinksLastFourteenDaysOnEveryStore(t *testing.T) {
	now := time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	signer, err := linktoken.New(linkKey)
	if err != nil {
		t.Fatal(err)
	}
	st := &memStore{objects: map[string][]byte{}, maxTTL: 7 * 24 * time.Hour} // S3 / UploadThing: 7 days at most
	stores := map[domain.Purpose]port.ObjectStore{}
	for _, p := range domain.Purposes() {
		stores[p] = st
	}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000), domain.LinkPolicy{},
		app.WithLinks(signer, "https://api.example.test/media/", 5*time.Minute), app.WithClock(clock))
	f := documentsFixture{svc: svc, store: st}
	ctx := context.Background()
	po := f.upload(t, retailService, domain.PurposePurchaseOrder, "PO-1001.pdf", domain.ContentTypePDF, pdf())

	got, err := svc.Get(ctx, retailService, po.ID, 14*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(got.URL, "https://api.example.test/media/v1/l/") || got.URLExpiresAt == nil ||
		!got.URLExpiresAt.Equal(now.Add(14*24*time.Hour)) {
		t.Fatalf("a 14-day media-hosted link was expected: %s until %v", got.URL, got.URLExpiresAt)
	}
	token := strings.TrimPrefix(got.URL, "https://api.example.test/media/v1/l/")

	// Day 13: the link still works and redirects to a fresh 5-minute storage URL.
	now = now.Add(13 * 24 * time.Hour)
	u, err := svc.ResolveLink(ctx, token)
	if err != nil || u != "https://signed/"+po.Key || st.signed[len(st.signed)-1] != 5*time.Minute {
		t.Fatalf("resolve on day 13: %s %v (signed %v)", u, err, st.signed)
	}
	// Day 15: expired.
	now = now.Add(2 * 24 * time.Hour)
	if _, err := svc.ResolveLink(ctx, token); !errors.Is(err, app.ErrLinkExpired) {
		t.Fatalf("expired link, got %v", err)
	}
	if _, err := svc.ResolveLink(ctx, token[:len(token)-3]+"AAA"); !errors.Is(err, app.ErrLinkInvalid) {
		t.Fatalf("tampered link, got %v", err)
	}

	// Short links stay storage-signed; other purposes never get media-hosted links.
	if got, _ := svc.Get(ctx, retailService, po.ID, 0); strings.Contains(got.URL, "/v1/l/") {
		t.Fatalf("default links are storage URLs: %s", got.URL)
	}
	kyc := f.upload(t, sellerMember, domain.PurposeKYCDocument, "ein.pdf", domain.ContentTypePDF, pdf())
	if got, _ := svc.Get(ctx, sellerMember, kyc.ID, 14*24*time.Hour); strings.Contains(got.URL, "/v1/l/") {
		t.Fatalf("KYC documents keep 5-minute links: %s", got.URL)
	}
	// A link to a non-document asset is refused even with a valid signature.
	kycToken, _ := signer.Sign(kyc.ID, now.Add(time.Hour))
	if _, err := svc.ResolveLink(ctx, kycToken); !errors.Is(err, app.ErrLinkInvalid) {
		t.Fatalf("links only serve long-link purposes, got %v", err)
	}
}

func TestStaffExportThroughTheBFFWithTheStaffUsersOwnToken(t *testing.T) {
	// The BFF forwards the staff user's token: media sees a STAFF actor, so the staff-only export purpose accepts it.
	f := newDocuments(0, domain.LinkPolicy{})
	staffViaBFF := auth.Actor{Subject: "staff-4", Type: "STAFF", Permissions: []string{"staff:exports"}}
	exp := f.upload(t, staffViaBFF, domain.PurposeExport, "orders.csv", domain.ContentTypeCSV, []byte("id,total\n1,10\n"))
	if exp.Status != domain.StatusReady || exp.UploadedBy != "staff-4" {
		t.Fatalf("export %+v", exp)
	}
	// A shopper's forwarded token does not turn into staff.
	if _, _, err := f.svc.RequestUpload(context.Background(), customer, domain.PurposeExport, "x.csv",
		domain.ContentTypeCSV, 10); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("customers may not store exports, got %v", err)
	}
}

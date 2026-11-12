package pgrepo_test

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// S388: the verdict columns, the backfill of existing assets, lookups by URL, rescoring and the staff override.
func TestVerdictColumnsBackfillRescoreAndRulesOverride(t *testing.T) {
	pool := startPostgres(t)
	ctx := context.Background()
	if err := pgrepo.Migrate(ctx, pool, media.Files); err != nil {
		t.Fatal(err)
	}
	repo := pgrepo.New(pool)

	// Assets stored before the checks existed: no verdict yet.
	for id, status := range map[string]string{
		"55555555-5555-4555-8555-555555555555": "READY",
		"66666666-6666-4666-8666-666666666666": "FLAGGED",
		"77777777-7777-4777-8777-777777777777": "REJECTED",
		"88888888-8888-4888-8888-888888888888": "PENDING",
	} {
		if _, err := pool.Exec(ctx, `insert into asset (id, owner_org, uploaded_by, purpose, provider, object_key, url, file_name,
			content_type, bytes, status) values ($1, 'org', 'u', 'product-image', 'mem', $3::text, 'https://cdn/' || $3::text, 'a.jpg', 'image/jpeg', 10, $2)`,
			id, status, id); err != nil {
			t.Fatal(err)
		}
	}
	for range 2 { // idempotent: migrations run on every start
		if err := pgrepo.Migrate(ctx, pool, media.Files); err != nil {
			t.Fatal(err)
		}
	}
	for id, want := range map[string]domain.ImageVerdict{
		"55555555-5555-4555-8555-555555555555": domain.VerdictPass,
		"66666666-6666-4666-8666-666666666666": domain.VerdictHold,
		"77777777-7777-4777-8777-777777777777": domain.VerdictReject,
		"88888888-8888-4888-8888-888888888888": "",
	} {
		a, err := repo.Get(ctx, id)
		if err != nil || a.Verdict != want || (want != "" && a.Checks == nil) || a.Metrics != nil {
			t.Fatalf("backfill of %s: %+v %v", id, a, err)
		}
	}

	// A new image keeps its slot, checks, metrics and hint.
	fresh := asset("99999999-9999-4999-8999-999999999999", "org", 1)
	fresh.ImageSlot, fresh.Hint = domain.ImageSlotMain, domain.ModerationHint{Slot: domain.ImageSlotMain, Title: "Dress", Brand: "Adire"}
	if err := repo.Insert(ctx, fresh); err != nil {
		t.Fatal(err)
	}
	cur, _ := repo.Get(ctx, fresh.ID)
	if cur.ImageSlot != domain.ImageSlotMain || cur.Hint.Title != "Dress" || cur.Checks != nil || cur.Verdict != "" {
		t.Fatalf("inserted: %+v", cur)
	}
	cur.Status, cur.URL, cur.Key = domain.StatusReady, "https://cdn/fresh.jpg", "product-image/fresh.jpg"
	cur.Metrics = &domain.ImageMetrics{Measured: true, RingWhiteRatio: 1, CornersWhite: true, FillRatio: 0.8, BorderRatio: 0.02, Sharpness: 120}
	cur.Checks = []domain.Check{domain.NewCheck(domain.CodeFillNear, domain.VerdictWarn, map[string]any{"fill": 85, "actual": 80})}
	cur.Verdict, cur.Shadow = domain.VerdictWarn, true
	cur.Moderation = &domain.Moderation{Verdict: domain.VerdictPassed, Reasons: []string{}, AI: domain.AIChecked}
	if err := repo.Update(ctx, cur); err != nil {
		t.Fatal(err)
	}
	found, ok, err := repo.FindByURL(ctx, "https://cdn/fresh.jpg")
	if err != nil || !ok || found.ID != fresh.ID || found.Verdict != domain.VerdictWarn || !found.Shadow || found.Metrics == nil ||
		found.Metrics.FillRatio != 0.8 || len(found.Checks) != 1 || found.Checks[0].Code != "FILL_OK_NEAR" || found.Checks[0].Message == "" {
		t.Fatalf("by url: %+v %v %v", found, ok, err)
	}
	var fill, border float32
	if err := pool.QueryRow(ctx, `select fill_ratio, border_ratio from asset where id = $1`, fresh.ID).Scan(&fill, &border); err != nil ||
		fill < 0.79 || fill > 0.81 || border < 0.019 || border > 0.021 {
		t.Fatalf("denormalised ratios %v %v %v", fill, border, err)
	}
	if _, ok, _ := repo.FindByURL(ctx, "https://cdn/nope.jpg"); ok {
		t.Fatal("unknown url")
	}

	// A rescore changes the verdict and measurements, not the status or the object.
	found.Metrics = &domain.ImageMetrics{Measured: true, RingWhiteRatio: 0.2, FillRatio: -1, Sharpness: -1}
	found.Checks = []domain.Check{domain.NewCheck(domain.CodeNotWhite, domain.VerdictReject, nil)}
	found.Verdict, found.Width, found.Height = domain.VerdictReject, 1600, 1600
	found.Flags = domain.Messages(found.Checks)
	if err := repo.UpdateChecks(ctx, found); err != nil {
		t.Fatal(err)
	}
	again, _ := repo.Get(ctx, fresh.ID)
	if again.Verdict != domain.VerdictReject || again.Status != domain.StatusReady || again.Key != "product-image/fresh.jpg" || again.Width != 1600 ||
		again.Metrics.RingWhiteRatio != 0.2 || len(again.Flags) != 1 {
		t.Fatalf("rescored: %+v", again)
	}
	var unmeasured *float32
	if err := pool.QueryRow(ctx, `select fill_ratio from asset where id = $1`, fresh.ID).Scan(&unmeasured); err != nil || unmeasured != nil {
		t.Fatalf("a ratio that was not measured is null: %v %v", unmeasured, err)
	}

	// The review queue: held product images, oldest first.
	held, err := repo.ListHeld(ctx, 10)
	if err != nil || len(held) != 0 {
		// The backfilled FLAGGED row has no moderation verdict, so it is not in the queue.
		t.Fatalf("held before any is stored: %+v %v", held, err)
	}
	queued := asset("12121212-1212-4212-8212-121212121212", "org", 2)
	if err := repo.Insert(ctx, queued); err != nil {
		t.Fatal(err)
	}
	queued.Status, queued.Verdict = domain.StatusFlagged, domain.VerdictHold
	queued.Checks = []domain.Check{domain.NewCheck(domain.CodeHeldText, domain.VerdictHold, nil)}
	queued.Moderation = &domain.Moderation{Verdict: domain.VerdictHeld, Reasons: []string{"needs review"}, AI: domain.AIChecked,
		AILabels: []string{"WATERMARK"}}
	cur, _ = repo.Get(ctx, queued.ID)
	queued.Version = cur.Version
	if err := repo.Update(ctx, queued); err != nil {
		t.Fatal(err)
	}
	if held, err = repo.ListHeld(ctx, 10); err != nil || len(held) != 1 || held[0].ID != queued.ID || held[0].Moderation.AILabels[0] != "WATERMARK" {
		t.Fatalf("review queue: %+v %v", held, err)
	}

	// The staff override: none, then saved, then replaced.
	empty, err := repo.LoadImageRules(ctx)
	if err != nil || empty.UpdatedAt != nil || empty.Override.WarnOnly != nil {
		t.Fatalf("no override yet: %+v %v", empty, err)
	}
	yes, fillReject := true, 0.7
	if err := repo.SaveImageRules(ctx, domain.ImageRulesOverride{WarnOnly: &yes, FillReject: &fillReject}, "staff-9"); err != nil {
		t.Fatal(err)
	}
	no := false
	if err := repo.SaveImageRules(ctx, domain.ImageRulesOverride{WarnOnly: &no}, "staff-10"); err != nil {
		t.Fatal(err)
	}
	saved, err := repo.LoadImageRules(ctx)
	if err != nil || saved.UpdatedBy != "staff-10" || saved.UpdatedAt == nil || saved.Override.WarnOnly == nil || *saved.Override.WarnOnly ||
		saved.Override.FillReject != nil {
		t.Fatalf("override: %+v %v", saved, err)
	}
}

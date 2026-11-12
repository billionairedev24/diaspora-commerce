package app

import (
	"context"
	"fmt"
	"path"
	"slices"
	"strings"
	"time"
	"unicode"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// rulesTTL is how long the staff override is cached before it is read again.
const rulesTTL = 30 * time.Second

// Bounds of the free text sent to the AI hook (seller text is data, never instructions; it is cut and cleaned).
const (
	maxHintTitle = 200
	maxHintBrand = 80
	maxHintType  = 80
)

// imageRules is the configured thresholds with the staff override on top (cached; an unreadable override keeps the
// last good rules, or the configured ones).
func (s *mediaServiceImpl) imageRules(ctx context.Context) domain.ImageRules {
	if s.rulesStore == nil {
		return s.baseRules
	}
	s.rulesMu.Lock()
	defer s.rulesMu.Unlock()
	if !s.rulesAt.IsZero() && s.now().Sub(s.rulesAt) < rulesTTL {
		return s.rulesNow
	}
	stored, err := s.rulesStore.LoadImageRules(ctx)
	if err != nil {
		s.log.WarnContext(ctx, "image rules override not read; keeping the current rules", "error", err)
		if s.rulesAt.IsZero() {
			s.rulesNow = s.baseRules
		}
		s.rulesAt = s.now()
		return s.rulesNow
	}
	rules := stored.Override.Apply(s.baseRules)
	if p := rules.Problem(); p != "" {
		s.log.WarnContext(ctx, "image rules override is not valid; using the configured rules", "problem", p)
		rules = s.baseRules
	}
	s.rulesNow, s.rulesAt = rules, s.now()
	return rules
}

// purposeRules are the rules of a purpose; the size cap of product images is the configurable one.
func (s *mediaServiceImpl) purposeRules(ctx context.Context, p domain.Purpose) (domain.Rules, error) {
	r, err := domain.RulesFor(p)
	if err != nil {
		return domain.Rules{}, fmt.Errorf("rules for %s: %w", p, err)
	}
	if p == domain.PurposeProductImage {
		r.MaxBytes = s.imageRules(ctx).MaxBytes
	}
	return r, nil
}

// cleanHint cuts the free text the form gave and drops control characters.
func cleanHint(h domain.ModerationHint, slot domain.ImageSlot) domain.ModerationHint {
	return domain.ModerationHint{Slot: slot, Title: cleanText(h.Title, maxHintTitle), Brand: cleanText(h.Brand, maxHintBrand),
		ProductTypeCode: cleanText(h.ProductTypeCode, maxHintType)}
}

func cleanText(s string, limit int) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, s)
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > limit {
		s = string(r[:limit])
	}
	return s
}

// setChecks records the findings and the verdict on the asset (checks are never nil), and the seller-facing messages
// as its flags.
func setChecks(a *domain.Asset, checks []domain.Check) {
	if checks == nil {
		checks = []domain.Check{}
	}
	a.Checks, a.Verdict = checks, domain.VerdictOf(checks)
	a.Flags = domain.Messages(checks)
}

// prepare strips the metadata of an image, measures it and, for a main image on white, snaps the background to pure
// white. A transparent image is flattened and a CMYK one converted; WebP written again becomes PNG.
func (s *mediaServiceImpl) prepare(a *domain.Asset, data []byte, rules domain.ImageRules,
) (port.ProcessedImage, *domain.ImageMetrics, error) {
	img, err := s.processor.Process(data, a.ContentType)
	if err != nil {
		return port.ProcessedImage{}, nil, fmt.Errorf("process image: %w", err)
	}
	if a.Purpose != domain.PurposeProductImage || s.analyzer == nil {
		return img, nil, nil
	}
	an, err := s.analyzer.Analyze(img.Data, a.ContentType, a.ImageSlot == domain.ImageSlotMain, rules.Main)
	if err != nil {
		return port.ProcessedImage{}, nil, fmt.Errorf("analyse image: %w", err)
	}
	if an.Data != nil {
		img.Data, img.Stripped = an.Data, true
		if an.ContentType != a.ContentType {
			a.ContentType = an.ContentType
			a.FileName = strings.TrimSuffix(a.FileName, path.Ext(a.FileName)) + extFor(an.ContentType)
		}
	}
	m := an.Metrics
	return img, &m, nil
}

func extFor(contentType string) string {
	switch contentType {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	default:
		return ""
	}
}

// imageFacts are what the checks look at for an asset judged for a slot.
func imageFacts(a domain.Asset, slot domain.ImageSlot) domain.ImageFacts {
	return domain.ImageFacts{Slot: slot, Width: a.Width, Height: a.Height, Metrics: a.Metrics}
}

func (s *mediaServiceImpl) VerifyImages(ctx context.Context, actor auth.Actor, items []domain.VerifyItem) ([]domain.VerifyResult, error) {
	return s.judgeStored(ctx, actor, items, false)
}

func (s *mediaServiceImpl) RescoreImages(ctx context.Context, actor auth.Actor, items []domain.VerifyItem) ([]domain.VerifyResult, error) {
	return s.judgeStored(ctx, actor, items, true)
}

func (s *mediaServiceImpl) judgeStored(ctx context.Context, actor auth.Actor, items []domain.VerifyItem, rescore bool) ([]domain.VerifyResult, error) {
	if actor.Type != principalService || !slices.Contains(domain.ImporterClients(), actor.Subject) ||
		!actor.Can(domain.PermissionMediaImport) {
		return nil, fmt.Errorf("verify images: %w", ErrForbidden)
	}
	limit := MaxVerifyBatch
	if rescore {
		limit = MaxRescoreBatch
	}
	if len(items) == 0 || len(items) > limit {
		return nil, fmt.Errorf("%w: send 1 to %d images", domain.ErrRejected, limit)
	}
	rules := s.imageRules(ctx)
	out := make([]domain.VerifyResult, 0, len(items))
	for _, it := range items {
		slot, ok := domain.ParseImageSlot(string(it.Slot))
		if !ok {
			return nil, fmt.Errorf("%w: slot must be MAIN, OTHER, SWATCH or LABEL", domain.ErrRejected)
		}
		a, found, err := s.repo.FindByURL(ctx, strings.TrimSpace(it.URL))
		if err != nil {
			return nil, fmt.Errorf("find image: %w", err)
		}
		if !found || a.Purpose != domain.PurposeProductImage {
			out = append(out, domain.VerifyResult{URL: it.URL, Slot: slot, Verdict: domain.VerdictReject,
				Checks: []domain.Check{domain.NewCheck(domain.CodeNotVerified, domain.VerdictReject, nil)}})
			continue
		}
		if rescore {
			if err := s.rescore(ctx, &a, rules); err != nil {
				s.log.WarnContext(ctx, "image could not be rescored", "asset_id", a.ID, "error", err)
				out = append(out, domain.VerifyResult{URL: it.URL, Found: true, Slot: slot, Verdict: domain.VerdictReject,
					Checks: []domain.Check{domain.NewCheck(domain.CodeNotAnImage, domain.VerdictReject, nil)}})
				continue
			}
		}
		out = append(out, s.resultFor(rules, a, slot, it.ProductTypeCode))
	}
	return out, nil
}

// resultFor judges a stored image for a slot. When the image was accepted for another slot, what that slot allowed
// is checked again: props and people are fine in an additional picture, not in the main one.
func (s *mediaServiceImpl) resultFor(rules domain.ImageRules, a domain.Asset, slot domain.ImageSlot, productType string,
) domain.VerifyResult {
	checks := domain.EvaluateImage(rules, imageFacts(a, slot))
	if slot == domain.ImageSlotMain && a.ImageSlot != domain.ImageSlotMain && a.Moderation != nil &&
		a.Moderation.ReviewedBy == "" && !rules.Main.OnModelAllowed(productType) {
		for _, l := range a.Moderation.AILabels {
			if l == domain.LabelProps || l == domain.LabelMannequin {
				checks = append(checks, domain.NewCheck(domain.CodeMainProps, domain.VerdictReject, nil))
				break
			}
		}
	}
	res := domain.VerifyResult{URL: a.URL, Found: true, Slot: slot, Verdict: domain.VerdictOf(checks), Checks: checks,
		Width: a.Width, Height: a.Height, Bytes: a.Bytes, ContentType: a.ContentType,
		Measured: a.Metrics != nil && a.Metrics.Measured, Shadow: a.Shadow}
	return res
}

// rescore measures a published image again from its stored file and records the result for its own slot. The file is
// never changed. At most two images are measured at a time.
func (s *mediaServiceImpl) rescore(ctx context.Context, a *domain.Asset, rules domain.ImageRules) error {
	if s.analyzer == nil {
		return fmt.Errorf("%w: image analysis is not enabled", domain.ErrRejected)
	}
	select {
	case s.rescoring <- struct{}{}:
		defer func() { <-s.rescoring }()
	case <-ctx.Done():
		return fmt.Errorf("rescore: %w", ctx.Err())
	}
	st, err := s.store(a.Purpose)
	if err != nil {
		return err
	}
	data, err := s.read(ctx, st, a.Key)
	if err != nil {
		return err
	}
	an, err := s.analyzer.Analyze(data, a.ContentType, false, rules.Main)
	if err != nil {
		return fmt.Errorf("analyse image: %w", err)
	}
	m := an.Metrics
	a.Metrics, a.Width, a.Height = &m, an.Width, an.Height
	slot := a.ImageSlot
	if slot == "" {
		slot = domain.ImageSlotOther
	}
	setChecks(a, domain.EvaluateImage(rules, imageFacts(*a, slot)))
	if err := s.repo.UpdateChecks(ctx, *a); err != nil {
		return fmt.Errorf("store checks: %w", err)
	}
	return nil
}

func (s *mediaServiceImpl) ImageRules(ctx context.Context, _ auth.Actor) (domain.ImageRulesView, error) {
	view := domain.ImageRulesView{Effective: s.imageRules(ctx)}
	if s.rulesStore != nil {
		stored, err := s.rulesStore.LoadImageRules(ctx)
		if err != nil {
			return domain.ImageRulesView{}, fmt.Errorf("load image rules: %w", err)
		}
		view.Override = stored
	}
	return view, nil
}

func (s *mediaServiceImpl) SetImageRules(ctx context.Context, actor auth.Actor, o domain.ImageRulesOverride) (domain.ImageRulesView, error) {
	if actor.Type != principalStaff || !slices.ContainsFunc(ModeratePermissions, actor.Can) {
		return domain.ImageRulesView{}, ErrForbidden
	}
	if s.rulesStore == nil {
		return domain.ImageRulesView{}, fmt.Errorf("%w: image rules cannot be changed here", domain.ErrRejected)
	}
	if p := o.Apply(s.baseRules).Problem(); p != "" {
		return domain.ImageRulesView{}, fmt.Errorf("%w: %s", domain.ErrRejected, p)
	}
	if err := s.rulesStore.SaveImageRules(ctx, o, actor.Subject); err != nil {
		return domain.ImageRulesView{}, fmt.Errorf("save image rules: %w", err)
	}
	s.rulesMu.Lock()
	s.rulesAt = time.Time{}
	s.rulesMu.Unlock()
	return s.ImageRules(ctx, actor)
}

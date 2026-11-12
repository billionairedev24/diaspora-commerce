package domain

import (
	"fmt"
	"math/bits"
	"time"
)

// ModerationVerdict is the outcome of the moderation checks that gate a public image.
type ModerationVerdict string

// Moderation verdicts.
const (
	// VerdictPassed: the image may be published (quality flags may still apply).
	VerdictPassed ModerationVerdict = "PASSED"
	// VerdictHeld: the image stays private until staff review it (asset status FLAGGED).
	VerdictHeld ModerationVerdict = "HELD"
	// VerdictBlocked: prohibited content; the object is deleted (asset status REJECTED).
	VerdictBlocked ModerationVerdict = "BLOCKED"
)

// AICheck says how the AI moderation hook took part in a verdict.
type AICheck string

// AI check outcomes.
const (
	AIChecked     AICheck = "CHECKED"     // the AI gateway answered
	AIUnavailable AICheck = "UNAVAILABLE" // the call failed or timed out; the fail mode decided
	AIOff         AICheck = "OFF"         // no AI moderation configured
)

// AIDecision is what the AI moderation hook (ai-gateway) answers for one image.
type AIDecision string

// AI decisions.
const (
	AIAllow  AIDecision = "ALLOW"
	AIReview AIDecision = "REVIEW"
	AIBlock  AIDecision = "BLOCK"
)

// AIVerdict is the AI gateway's answer: a decision with labels such as NUDITY or WATERMARK.
type AIVerdict struct {
	Decision AIDecision
	Labels   []string
	Model    string
	// MismatchScore is how far the picture is from the title and brand it was given (0 matches, 1 clearly
	// different); nil when the hook was not given a title or brand, or did not judge it.
	MismatchScore *float64
}

// ModerationHint is what the seller's form knows about the image, so the hook can judge it in context.
type ModerationHint struct {
	Slot            ImageSlot `json:"slot,omitempty"`
	Title           string    `json:"title,omitempty"`
	Brand           string    `json:"brand,omitempty"`
	ProductTypeCode string    `json:"productTypeCode,omitempty"`
}

// Moderation records the checks an image went through before it could become public.
type Moderation struct {
	Verdict ModerationVerdict `json:"verdict"`
	Reasons []string          `json:"reasons"`
	AI      AICheck           `json:"ai"`
	// AILabels are the AI gateway's labels (empty when it allowed the image or was not asked).
	AILabels []string `json:"aiLabels,omitempty"`
	// MetadataStripped is true when EXIF / XMP / text metadata (camera, GPS, …) was removed before publishing.
	MetadataStripped bool `json:"metadataStripped"`
	// DuplicateOf is the closest earlier image with the same perceptual hash, if any.
	DuplicateOf string `json:"duplicateOf,omitempty"`
	// DuplicateHeld is true when another seller's near-identical image is why the image was held.
	DuplicateHeld bool `json:"duplicateHeld,omitempty"`
	// MismatchScore is the AI's title and brand mismatch score, when it gave one.
	MismatchScore *float64 `json:"mismatchScore,omitempty"`
	// Checks are the seller-facing findings of the moderation (held for text, props, mismatch, a duplicate ...).
	Checks []Check `json:"-"`
	// ReviewedBy is the staff member who released or rejected a held image.
	ReviewedBy string     `json:"reviewedBy,omitempty"`
	ReviewedAt *time.Time `json:"reviewedAt,omitempty"`
}

// Published reports whether the moderation lets the image become public.
func (m *Moderation) Published() bool { return m == nil || m.Verdict == VerdictPassed }

// ModerationPolicy configures how the checks combine.
type ModerationPolicy struct {
	// AIFailOpen publishes images when the AI hook is unavailable (flagged "AI moderation skipped"); otherwise
	// (fail closed) such images are held for review.
	AIFailOpen bool
	// DuplicateDistance is the largest Hamming distance between 64-bit perceptual hashes treated as the same image
	// (0 = exact hash only; negative disables duplicate detection).
	DuplicateDistance int
}

// DefaultDuplicateDistance tolerates re-encoding, resizing and small crops of the same photo.
const DefaultDuplicateDistance = 6

// SimilarImage is an earlier image whose perceptual hash is close to a new upload's.
type SimilarImage struct {
	AssetID  string
	OwnerOrg string
	Distance int
}

// ModerationInput gathers what the checks found for one image.
type ModerationInput struct {
	OwnerOrg         string
	MetadataStripped bool
	// AI is the gateway's verdict; nil when AIErr is set or AI moderation is off.
	AI      *AIVerdict
	AIErr   error
	AIOff   bool
	Similar []SimilarImage
	// Slot, ProductTypeCode and Rules make the AI labels slot-aware: props and people hold only a main image, and
	// not even that for apparel when the rules allow on-model pictures. The zero Rules hold nothing extra.
	Slot            ImageSlot
	ProductTypeCode string
	Rules           ImageRules
}

// Moderate combines the AI verdict and duplicate matches into a moderation result:
//
//   - AI BLOCK (prohibited content) blocks the image;
//   - AI REVIEW (watermarks, contact details, not a product photo …) holds it for staff review;
//   - an unavailable AI holds it when failing closed, or passes it with a note when failing open;
//   - a near-identical image uploaded by another organisation holds it (possible stolen photo); one of the
//     organisation's own images is only noted (the same photo on several variants is normal).
func Moderate(p ModerationPolicy, in ModerationInput) Moderation {
	m := Moderation{Verdict: VerdictPassed, Reasons: []string{}, AI: AIOff, MetadataStripped: in.MetadataStripped}
	hold := func(reason string) {
		if m.Verdict == VerdictPassed {
			m.Verdict = VerdictHeld
		}
		m.Reasons = append(m.Reasons, reason)
	}
	holdWith := func(reason string, checks ...Check) {
		hold(reason)
		m.Checks = append(m.Checks, checks...)
	}
	switch {
	case in.AIOff:
	case in.AIErr != nil || in.AI == nil:
		m.AI = AIUnavailable
		if p.AIFailOpen {
			m.Reasons = append(m.Reasons, "AI moderation skipped (unavailable)")
		} else {
			holdWith("AI moderation unavailable; held for review", NewCheck(CodeHeldReview, VerdictHold, nil))
		}
	default:
		m.AI = AIChecked
		m.AILabels = in.AI.Labels
		m.MismatchScore = in.AI.MismatchScore
		aiChecks := AIChecks(in.Rules, in.Slot, in.ProductTypeCode, in.AI.Labels, in.AI.MismatchScore)
		switch in.AI.Decision {
		case AIAllow:
			if len(aiChecks) > 0 {
				holdWith("needs review"+labelSuffix(in.AI.Labels)+mismatchSuffix(in.AI.MismatchScore), aiChecks...)
			}
		case AIBlock:
			m.Verdict = VerdictBlocked
			m.Reasons = append(m.Reasons, "prohibited content"+labelSuffix(in.AI.Labels))
			m.Checks = append(m.Checks, NewCheck(CodeProhibited, VerdictReject, nil))
		default: // REVIEW and anything unexpected
			if len(aiChecks) == 0 {
				aiChecks = []Check{NewCheck(CodeHeldReview, VerdictHold, nil)}
			}
			holdWith("needs review"+labelSuffix(in.AI.Labels)+mismatchSuffix(in.AI.MismatchScore), aiChecks...)
		}
	}
	if p.DuplicateDistance >= 0 {
		for _, s := range in.Similar {
			if s.Distance > p.DuplicateDistance {
				continue
			}
			if m.DuplicateOf == "" {
				m.DuplicateOf = s.AssetID
			}
			if s.OwnerOrg != in.OwnerOrg {
				holdWith("matches an image uploaded by another seller", NewCheck(CodeHeldDuplicate, VerdictHold, nil))
				m.DuplicateOf, m.DuplicateHeld = s.AssetID, true
				break
			}
		}
		if m.DuplicateOf != "" && m.Verdict == VerdictPassed {
			m.Reasons = append(m.Reasons, "duplicate of your image "+m.DuplicateOf)
		}
	}
	return m
}

func mismatchSuffix(score *float64) string {
	if score == nil {
		return ""
	}
	return fmt.Sprintf(" (title and brand mismatch %.2f)", *score)
}

func labelSuffix(labels []string) string {
	if len(labels) == 0 {
		return ""
	}
	return fmt.Sprintf(" %v", labels)
}

// HashDistance is the Hamming distance between two 64-bit perceptual hashes.
func HashDistance(a, b uint64) int { return bits.OnesCount64(a ^ b) }

// ReviewDecision is a staff decision on a held image.
type ReviewDecision string

// Review decisions.
const (
	ReviewApprove ReviewDecision = "APPROVE"
	ReviewReject  ReviewDecision = "REJECT"
)

// ModeratedPurpose reports whether uploads of the purpose go through moderation (public images).
func ModeratedPurpose(p Purpose) bool {
	r, err := RulesFor(p)
	return err == nil && r.Public
}

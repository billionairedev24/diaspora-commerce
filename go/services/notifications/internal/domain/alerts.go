package domain

import (
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"
)

// AlertAudience is the back office an alert topic belongs to.
type AlertAudience string

// Back-office audiences.
const (
	AudienceSeller AlertAudience = "seller"
	AudienceStaff  AlertAudience = "staff"
)

// Valid reports whether a is a known audience.
func (a AlertAudience) Valid() bool { return a == AudienceSeller || a == AudienceStaff }

// AlertTopic is a back-office alert category a member can mute per channel (on top of the account's
// channel × category preferences). Security messages never belong to a topic.
type AlertTopic string

// Alert topics.
const (
	TopicSellerOrders     AlertTopic = "seller.orders"
	TopicSellerInventory  AlertTopic = "seller.inventory"
	TopicSellerPayouts    AlertTopic = "seller.payouts"
	TopicSellerCompliance AlertTopic = "seller.compliance"
	TopicSellerClaims     AlertTopic = "seller.claims"
	TopicStaffAssigned    AlertTopic = "staff.assigned"
	TopicStaffEscalations AlertTopic = "staff.escalations"
	TopicStaffApprovals   AlertTopic = "staff.approvals"
)

const (
	alertTemplateWildcard = "*"
	minutesPerDay         = 24 * 60
)

// AlertChannels are the channels a topic can be muted on.
var AlertChannels = []Channel{ChannelEmail, ChannelSMS}

// AlertTopicDef describes one topic: its audience, the template keys it covers (exact keys, or a prefix ending in
// "*") and the channels that stay on because the platform must reach the member (Locked).
type AlertTopicDef struct {
	Topic     AlertTopic
	Audience  AlertAudience
	Templates []string
	Locked    []Channel
}

// AlertTopics is the catalogue, in display order. Topics without templates have no producer yet; the member's choice
// is kept and applies as soon as one sends.
var AlertTopics = []AlertTopicDef{
	{Topic: TopicSellerOrders, Audience: AudienceSeller, Templates: []string{"seller.order.*"}},
	{Topic: TopicSellerInventory, Audience: AudienceSeller, Templates: []string{"seller.inventory.*"}},
	{Topic: TopicSellerPayouts, Audience: AudienceSeller,
		Templates: []string{"payout.paid", "payout.failed", "seller.payouts.*"}},
	// Stripe requirements, enforcement and moderation outcomes: email always reaches the owners.
	{Topic: TopicSellerCompliance, Audience: AudienceSeller, Locked: []Channel{ChannelEmail},
		Templates: []string{"seller.compliance.*", "seller.listings.*", "seller.suspended", "seller.reinstated",
			"listing.rejected", "listing.taken_down"}},
	{Topic: TopicSellerClaims, Audience: AudienceSeller, Locked: []Channel{ChannelEmail},
		Templates: []string{"claim.seller_response_due", "claim.seller_response_reminder", "claim.seller_response_lapsed",
			"claim.decided.seller", "claim.withdrawn"}},
	{Topic: TopicStaffAssigned, Audience: AudienceStaff, Templates: []string{"staff.assignment.*"}},
	{Topic: TopicStaffEscalations, Audience: AudienceStaff, Locked: []Channel{ChannelEmail},
		Templates: []string{"staff.escalation.*"}},
	{Topic: TopicStaffApprovals, Audience: AudienceStaff, Templates: []string{"staff.approval.*"}},
}

// ErrUnknownTopic is returned for a topic outside the catalogue (or of another audience).
var ErrUnknownTopic = errors.New("unknown alert topic")

// AlertTopicOf returns the topic definition for a topic key.
func AlertTopicOf(t AlertTopic) (AlertTopicDef, bool) {
	for _, d := range AlertTopics {
		if d.Topic == t {
			return d, true
		}
	}
	return AlertTopicDef{}, false
}

// TopicForTemplate returns the topic a template key belongs to.
func TopicForTemplate(templateKey string) (AlertTopicDef, bool) {
	for _, d := range AlertTopics {
		for _, p := range d.Templates {
			if p == templateKey || (strings.HasSuffix(p, alertTemplateWildcard) &&
				strings.HasPrefix(templateKey, strings.TrimSuffix(p, alertTemplateWildcard))) {
				return d, true
			}
		}
	}
	return AlertTopicDef{}, false
}

// IsLocked reports whether the topic stays on for a channel.
func (d AlertTopicDef) IsLocked(c Channel) bool { return slices.Contains(d.Locked, c) }

// AlertPreference is a member's choice for one topic and channel.
type AlertPreference struct {
	Topic   AlertTopic `json:"topic"`
	Channel Channel    `json:"channel"`
	Enabled bool       `json:"enabled"`
	Locked  bool       `json:"locked"`
}

// PersonalQuietHours is a member's own do-not-disturb window for SMS and push (recipient local time). Minutes are
// offsets from local midnight; End before Start spans midnight.
type PersonalQuietHours struct {
	Enabled     bool `json:"enabled"`
	StartMinute int  `json:"-"`
	EndMinute   int  `json:"-"`
}

// DefaultPersonalQuietHours is what a member who never chose sees: off, 21:00–07:00 ready to switch on.
var DefaultPersonalQuietHours = PersonalQuietHours{Enabled: false, StartMinute: 21 * 60, EndMinute: 7 * 60}

// Window returns the quiet window, or ok=false when it is off or empty.
func (q PersonalQuietHours) Window() (QuietHours, bool) {
	if !q.Enabled || q.StartMinute == q.EndMinute {
		return QuietHours{}, false
	}
	return QuietHours{Start: minutes(q.StartMinute), End: minutes(q.EndMinute)}, true
}

// AlertSettings are a member's topic switches for one audience and their personal quiet hours.
type AlertSettings struct {
	Audience    AlertAudience
	Preferences []AlertPreference
	Quiet       PersonalQuietHours
}

// ResolveAlerts lists every topic × channel of an audience with stored choices applied (default on; locked channels
// always on).
func ResolveAlerts(audience AlertAudience, stored []AlertPreference) []AlertPreference {
	set := map[[2]string]bool{}
	for _, s := range stored {
		set[[2]string{string(s.Topic), string(s.Channel)}] = s.Enabled
	}
	out := []AlertPreference{}
	for _, d := range AlertTopics {
		if d.Audience != audience {
			continue
		}
		for _, ch := range AlertChannels {
			en, ok := set[[2]string{string(d.Topic), string(ch)}]
			if !ok || d.IsLocked(ch) {
				en = true
			}
			out = append(out, AlertPreference{Topic: d.Topic, Channel: ch, Enabled: en, Locked: d.IsLocked(ch)})
		}
	}
	return out
}

// ValidateAlerts checks a change for an audience: known topics of that audience, alert channels only, locked
// channels never turned off, and a quiet window of whole minutes within a day.
func ValidateAlerts(audience AlertAudience, prefs []AlertPreference, quiet *PersonalQuietHours) error {
	if !audience.Valid() {
		return errors.Join(ErrInvalidRequest, fmt.Errorf("audience must be %s or %s", AudienceSeller, AudienceStaff))
	}
	for _, p := range prefs {
		d, ok := AlertTopicOf(p.Topic)
		if !ok || d.Audience != audience {
			return errors.Join(ErrInvalidRequest, fmt.Errorf("%w %q for %s", ErrUnknownTopic, p.Topic, audience))
		}
		if !slices.Contains(AlertChannels, p.Channel) {
			return errors.Join(ErrInvalidRequest, fmt.Errorf("channel %q has no alert switches", p.Channel))
		}
		if !p.Enabled && d.IsLocked(p.Channel) {
			return errors.Join(ErrInvalidRequest, fmt.Errorf("%s alerts by %s cannot be turned off", p.Topic, p.Channel))
		}
	}
	if quiet != nil {
		for _, m := range []int{quiet.StartMinute, quiet.EndMinute} {
			if m < 0 || m >= minutesPerDay {
				return errors.Join(ErrInvalidRequest, errors.New("quiet hours must be times of day (00:00–23:59)"))
			}
		}
		if quiet.Enabled && quiet.StartMinute == quiet.EndMinute {
			return errors.Join(ErrInvalidRequest, errors.New("quiet hours need different start and end times"))
		}
	}
	return nil
}

// ParseClockMinutes parses "21:30" into minutes after midnight.
func ParseClockMinutes(v string) (int, error) {
	d, err := parseClock(v)
	if err != nil {
		return 0, errors.Join(ErrInvalidRequest, err)
	}
	return int(d.Minutes()), nil
}

func minutes(m int) time.Duration { return time.Duration(m) * time.Minute }

// FormatClockMinutes formats minutes after midnight as "HH:MM".
func FormatClockMinutes(m int) string { return fmt.Sprintf("%02d:%02d", m/60, m%60) }

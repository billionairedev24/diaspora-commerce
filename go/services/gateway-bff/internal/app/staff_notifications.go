package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffNotificationsService is the Notifications → Templates module of the staff console (S268;
// contracts/openapi/gateway-bff-staff.yaml, tag Notifications) over notifications' versioned templates: the list,
// a key's versions per locale and channel with the key's sample data, live previews (stored versions or drafts),
// new versions and test-sends to the staff member's own address. Viewing needs notifications:read; new versions and
// test-sends need notifications:templates:write (checked here before notifications checks them again).
type StaffNotificationsService interface {
	Templates(ctx context.Context, c *domain.Caller, q string) (domain.NotificationTemplateList, error)
	Template(ctx context.Context, c *domain.Caller, key string) (domain.NotificationTemplateDetail, error)
	Preview(ctx context.Context, c *domain.Caller, key string, req domain.NotificationPreviewRequest) (domain.NotificationPreview, error)
	CreateVersion(ctx context.Context, c *domain.Caller, key string, req domain.NotificationVersionDraft) (domain.NotificationTemplateVersion, error)
	TestSend(ctx context.Context, c *domain.Caller, key string, req domain.NotificationPreviewRequest) (domain.NotificationTestSendResult, error)
}

type staffNotificationsImpl struct{ r *staffResolver }

var _ StaffNotificationsService = (*staffNotificationsImpl)(nil)

// NewStaffNotificationsService returns the notification templates use case.
func NewStaffNotificationsService(d StaffDeps) StaffNotificationsService {
	return &staffNotificationsImpl{r: newStaffResolver(d)}
}

const (
	notificationsModule = "notifications"
	maxTemplateSubject  = 300
	maxTemplateHTML     = 50_000
	maxTemplateText     = 5_000
)

// upstreamTemplate is notifications' Template.
type upstreamTemplate struct {
	Key       string `json:"key"`
	Locale    string `json:"locale"`
	Channel   string `json:"channel"`
	Version   int    `json:"version"`
	Category  string `json:"category"`
	Subject   string `json:"subject"`
	BodyHTML  string `json:"bodyHtml"`
	BodyText  string `json:"bodyText"`
	CreatedBy string `json:"createdBy"`
	CreatedAt string `json:"createdAt"`
}

func (t upstreamTemplate) version() domain.NotificationTemplateVersion {
	return domain.NotificationTemplateVersion{Locale: t.Locale, Channel: t.Channel, Version: t.Version, Category: t.Category,
		Subject: t.Subject, BodyHTML: t.BodyHTML, BodyText: t.BodyText, CreatedBy: strPtr(t.CreatedBy), CreatedAt: optTime(t.CreatedAt)}
}

func canWriteTemplates(a domain.StaffAccess) bool {
	return a.Can(domain.PermNotificationsTemplatesWrite) && a.Allows(notificationsModule, domain.LevelEdit)
}

func templateNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such notification template.")
}

func channelRank(ch string) int {
	if i := slices.Index(domain.NotificationChannels, ch); i >= 0 {
		return i
	}
	return len(domain.NotificationChannels)
}

func (s *staffNotificationsImpl) list(ctx context.Context, sc staffCtx, key string) ([]upstreamTemplate, error) {
	var q url.Values
	if key != "" {
		q = url.Values{"key": {key}}
	}
	var page struct {
		Items []upstreamTemplate `json:"items"`
	}
	if err := sc.get(ctx, sc.up.Notifications, "/v1/templates", q, &page); err != nil {
		return nil, err
	}
	return page.Items, nil
}

func (s *staffNotificationsImpl) Templates(ctx context.Context, c *domain.Caller, q string) (domain.NotificationTemplateList, error) {
	if len(q) > 120 {
		return domain.NotificationTemplateList{}, domain.ValidationProblem(map[string]string{"q": "Too long"})
	}
	sc, err := s.r.require(ctx, c, notificationsModule, domain.LevelView)
	if err != nil {
		return domain.NotificationTemplateList{}, err
	}
	latest, err := s.list(ctx, sc, "")
	if err != nil {
		return domain.NotificationTemplateList{}, err
	}
	term := strings.ToLower(strings.TrimSpace(q))
	byKey := map[string]*domain.NotificationTemplateSummary{}
	for _, t := range latest {
		if term != "" && !strings.Contains(strings.ToLower(t.Key+" "+t.Category+" "+t.Channel), term) {
			continue
		}
		sum, ok := byKey[t.Key]
		if !ok {
			sum = &domain.NotificationTemplateSummary{Key: t.Key, Category: t.Category, Channels: []string{}, Locales: []string{}}
			byKey[t.Key] = sum
		}
		if !slices.Contains(sum.Channels, t.Channel) {
			sum.Channels = append(sum.Channels, t.Channel)
		}
		if !slices.Contains(sum.Locales, t.Locale) {
			sum.Locales = append(sum.Locales, t.Locale)
		}
		sum.LatestVersion = max(sum.LatestVersion, t.Version)
		if at := optTime(t.CreatedAt); at != nil && (sum.UpdatedAt == nil || at.After(*sum.UpdatedAt)) {
			sum.UpdatedAt, sum.UpdatedBy = at, strPtr(t.CreatedBy)
		}
		if t.Channel == "email" {
			sum.Category = t.Category
		}
	}
	out := domain.NotificationTemplateList{Items: []domain.NotificationTemplateSummary{}, CanEdit: canWriteTemplates(sc.access)}
	for _, sum := range byKey {
		sort.SliceStable(sum.Channels, func(i, j int) bool { return channelRank(sum.Channels[i]) < channelRank(sum.Channels[j]) })
		sort.Strings(sum.Locales)
		out.Items = append(out.Items, *sum)
	}
	sort.Slice(out.Items, func(i, j int) bool { return out.Items[i].Key < out.Items[j].Key })
	out.Total = len(out.Items)
	return out, nil
}

func (s *staffNotificationsImpl) Template(ctx context.Context, c *domain.Caller, key string) (domain.NotificationTemplateDetail, error) {
	if !domain.ValidTemplateKey(key) {
		return domain.NotificationTemplateDetail{}, templateNotFound()
	}
	sc, err := s.r.require(ctx, c, notificationsModule, domain.LevelView)
	if err != nil {
		return domain.NotificationTemplateDetail{}, err
	}
	versions, err := s.list(ctx, sc, key)
	if err != nil {
		return domain.NotificationTemplateDetail{}, err
	}
	if len(versions) == 0 {
		return domain.NotificationTemplateDetail{}, templateNotFound()
	}
	out := domain.NotificationTemplateDetail{Key: key, Variants: []domain.NotificationTemplateVariant{}, Sample: s.sample(ctx, sc, key),
		CanEdit: canWriteTemplates(sc.access), CanTestSend: canWriteTemplates(sc.access)}
	byVariant := map[string]*domain.NotificationTemplateVariant{}
	var order []string
	for _, t := range versions {
		id := t.Locale + "|" + t.Channel
		v, ok := byVariant[id]
		if !ok {
			v = &domain.NotificationTemplateVariant{Locale: t.Locale, Channel: t.Channel, Versions: []domain.NotificationTemplateVersion{}}
			byVariant[id] = v
			order = append(order, id)
		}
		v.Versions = append(v.Versions, t.version())
		v.LatestVersion = max(v.LatestVersion, t.Version)
	}
	for _, id := range order {
		v := byVariant[id]
		sort.SliceStable(v.Versions, func(i, j int) bool { return v.Versions[i].Version > v.Versions[j].Version })
		out.Variants = append(out.Variants, *v)
		if out.Category == "" || v.Channel == "email" {
			out.Category = v.Versions[0].Category
		}
	}
	sort.SliceStable(out.Variants, func(i, j int) bool {
		a, b := out.Variants[i], out.Variants[j]
		if a.Locale != b.Locale {
			return a.Locale == "en" || (b.Locale != "en" && a.Locale < b.Locale)
		}
		return channelRank(a.Channel) < channelRank(b.Channel)
	})
	return out, nil
}

// sample reads the key's sample data; when notifications cannot answer, the editor starts from empty data.
func (s *staffNotificationsImpl) sample(ctx context.Context, sc staffCtx, key string) domain.NotificationSample {
	out := domain.NotificationSample{Source: "none", Area: "other", EventTypes: []string{}, Data: map[string]any{}}
	var up domain.NotificationSample
	if err := sc.get(ctx, sc.up.Notifications, "/v1/templates/"+url.PathEscape(key)+"/sample", nil, &up); err != nil {
		s.r.d.Log.DebugContext(ctx, "template sample unavailable", "key", key, "error", err.Error())
		return out
	}
	if up.Source != "" {
		out.Source = up.Source
	}
	if up.Area != "" {
		out.Area = up.Area
	}
	if up.EventTypes != nil {
		out.EventTypes = up.EventTypes
	}
	if up.Data != nil {
		out.Data = up.Data
	}
	return out
}

func validateDraftParts(channel, subject, html, text string, errs map[string]string) {
	if len([]rune(subject)) > maxTemplateSubject {
		errs["subject"] = "Keep it under 300 characters"
	}
	if len(html) > maxTemplateHTML {
		errs["bodyHtml"] = "The HTML is too large (50,000 characters at most)"
	}
	if len([]rune(text)) > maxTemplateText {
		errs["bodyText"] = "Keep it under 5,000 characters"
	}
	if strings.TrimSpace(text) == "" {
		errs["bodyText"] = "Write the text"
	}
	if channel == "email" && strings.TrimSpace(html) == "" {
		errs["bodyHtml"] = "Emails need an HTML body"
	}
	if channel != "email" && strings.TrimSpace(html) != "" {
		errs["bodyHtml"] = "Only emails have an HTML body"
	}
	if (channel == "email" || channel == "push") && strings.TrimSpace(subject) == "" {
		if channel == "email" {
			errs["subject"] = "Emails need a subject"
		} else {
			errs["subject"] = "Push notifications need a title"
		}
	}
}

func validatePreview(req *domain.NotificationPreviewRequest) error {
	errs := map[string]string{}
	if !slices.Contains(domain.NotificationChannels, req.Channel) {
		errs["channel"] = "Choose email, sms, push or chat"
	}
	if req.Locale == "" {
		req.Locale = "en"
	}
	if !domain.ValidTemplateLocale(req.Locale) {
		errs["locale"] = "Use a locale such as en or fr-CA"
	}
	if req.Version < 0 {
		errs["version"] = "Use 0 for the latest version"
	}
	if req.Data == nil {
		req.Data = map[string]any{}
	}
	if d := req.Draft; d != nil {
		if d.Category != "" && !slices.Contains(domain.NotificationCategories, d.Category) {
			errs["draft.category"] = "Choose security, transactional, reminder or marketing"
		}
		draftErrs := map[string]string{}
		validateDraftParts(req.Channel, d.Subject, d.BodyHTML, d.BodyText, draftErrs)
		for k, v := range draftErrs {
			errs["draft."+k] = v
		}
	}
	if len(errs) > 0 {
		return domain.ValidationProblem(errs)
	}
	return nil
}

var (
	missingKey = regexp.MustCompile(`map has no entry for key "([^"]+)"`)
	renderPart = regexp.MustCompile(`template render failed: (subject|text|html): (.*)`)
)

// renderProblem turns notifications' 400 (template does not compile, or a placeholder is not in the data) into a 422
// on the part that failed: subject, bodyText or bodyHtml (prefixed draft. for drafts), else data.
func renderProblem(up *port.UpstreamProblem, draft bool) *domain.Problem {
	field, msg := "data", up.Detail
	if m := renderPart.FindStringSubmatch(up.Detail); m != nil {
		field = map[string]string{"subject": "subject", "text": "bodyText", "html": "bodyHtml"}[m[1]]
		msg = m[2]
		if draft {
			field = "draft." + field
		}
	}
	if m := missingKey.FindStringSubmatch(up.Detail); m != nil {
		msg = "Not in the sample data: " + m[1]
	}
	if msg == "" {
		msg = "This template does not render"
	}
	return domain.ValidationProblem(map[string]string{field: msg}).WithCause(up)
}

// postTemplate calls notifications and returns its raw error, so callers can read template problems before mapErr.
func postTemplate(ctx context.Context, sc staffCtx, path string, body, out any) error {
	if sc.up.Notifications == nil {
		return domain.UpstreamUnavailable("notifications")
	}
	return sc.up.Notifications.Call(ctx, sc.bearer, port.StaffCall{Method: http.MethodPost, Path: path, Body: body}, out) //nolint:wrapcheck // mapped by the callers
}

func previewBody(req domain.NotificationPreviewRequest) jobj {
	body := jobj{"channel": req.Channel, "locale": req.Locale, "version": req.Version, "data": req.Data}
	if req.Draft != nil {
		body["draft"] = req.Draft
	}
	return body
}

func (s *staffNotificationsImpl) render(ctx context.Context, sc staffCtx, key string, req domain.NotificationPreviewRequest) (domain.NotificationPreview, error) {
	var up struct {
		Subject  string `json:"subject"`
		HTML     string `json:"html"`
		Text     string `json:"text"`
		Version  int    `json:"version"`
		Category string `json:"category"`
	}
	if err := postTemplate(ctx, sc, "/v1/templates/"+url.PathEscape(key)+"/preview", previewBody(req), &up); err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok {
			switch p.Status {
			case http.StatusBadRequest:
				return domain.NotificationPreview{}, renderProblem(p, req.Draft != nil)
			case http.StatusNotFound:
				return domain.NotificationPreview{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found",
					"No version of this template for that channel and locale.").WithCause(err)
			}
		}
		return domain.NotificationPreview{}, mapErr("notifications", err)
	}
	out := domain.NotificationPreview{Subject: up.Subject, HTML: up.HTML, Text: up.Text, Version: up.Version, Category: up.Category}
	if req.Channel == "sms" || req.Channel == "chat" {
		seg := domain.CountSmsSegments(up.Text)
		out.SMS = &seg
	}
	return out, nil
}

func (s *staffNotificationsImpl) Preview(ctx context.Context, c *domain.Caller, key string, req domain.NotificationPreviewRequest) (domain.NotificationPreview, error) {
	if !domain.ValidTemplateKey(key) {
		return domain.NotificationPreview{}, templateNotFound()
	}
	if err := validatePreview(&req); err != nil {
		return domain.NotificationPreview{}, err
	}
	sc, err := s.r.require(ctx, c, notificationsModule, domain.LevelView)
	if err != nil {
		return domain.NotificationPreview{}, err
	}
	return s.render(ctx, sc, key, req)
}

func (s *staffNotificationsImpl) CreateVersion(ctx context.Context, c *domain.Caller, key string, req domain.NotificationVersionDraft) (domain.NotificationTemplateVersion, error) {
	if !domain.ValidTemplateKey(key) {
		return domain.NotificationTemplateVersion{}, templateNotFound()
	}
	errs := map[string]string{}
	if req.Locale == "" {
		req.Locale = "en"
	}
	if !domain.ValidTemplateLocale(req.Locale) {
		errs["locale"] = "Use a locale such as en or fr-CA"
	}
	if !slices.Contains(domain.NotificationChannels, req.Channel) {
		errs["channel"] = "Choose email, sms, push or chat"
	}
	if !slices.Contains(domain.NotificationCategories, req.Category) {
		errs["category"] = "Choose security, transactional, reminder or marketing"
	}
	validateDraftParts(req.Channel, req.Subject, req.BodyHTML, req.BodyText, errs)
	if len(errs) > 0 {
		return domain.NotificationTemplateVersion{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, notificationsModule, domain.LevelEdit)
	if err != nil {
		return domain.NotificationTemplateVersion{}, err
	}
	if !sc.access.Can(domain.PermNotificationsTemplatesWrite) {
		return domain.NotificationTemplateVersion{}, needsPermission(domain.PermNotificationsTemplatesWrite)
	}
	// Every placeholder must be a field the key's event carries (its sample data): a field it does not carry would
	// fail every send. Keys without a sample (new keys) are checked by rendering only.
	sample := s.sample(ctx, sc, key)
	if sample.Source != "none" {
		for field, src := range map[string]string{"subject": req.Subject, "bodyHtml": req.BodyHTML, "bodyText": req.BodyText} {
			var missing []string
			for _, p := range domain.TemplatePlaceholders(src) {
				if _, ok := sample.Data[p]; !ok {
					missing = append(missing, p)
				}
			}
			if len(missing) > 0 {
				errs[field] = "Not in the sample data: " + strings.Join(missing, ", ")
			}
		}
		if len(errs) > 0 {
			return domain.NotificationTemplateVersion{}, domain.ValidationProblem(errs)
		}
	}
	draft := &domain.NotificationDraft{Category: req.Category, Subject: req.Subject, BodyHTML: req.BodyHTML, BodyText: req.BodyText}
	rendered, err := s.render(ctx, sc, key, domain.NotificationPreviewRequest{Channel: req.Channel, Locale: req.Locale, Data: sample.Data, Draft: draft})
	if err != nil {
		if p, ok := domain.AsProblem(err); ok && p.Status == http.StatusUnprocessableEntity {
			fields := map[string]string{}
			for k, v := range p.Errors {
				fields[strings.TrimPrefix(k, "draft.")] = v
			}
			return domain.NotificationTemplateVersion{}, domain.ValidationProblem(fields).WithCause(err)
		}
		return domain.NotificationTemplateVersion{}, err
	}
	if rendered.SMS != nil && req.Channel == "sms" && rendered.SMS.Segments > domain.MaxSmsSegments {
		return domain.NotificationTemplateVersion{}, domain.ValidationProblem(map[string]string{"bodyText": "This SMS is " +
			strconv.Itoa(rendered.SMS.Segments) + " segments (" + rendered.SMS.Encoding + ") with the sample data; keep it to 3 or fewer"})
	}
	var created upstreamTemplate
	body := jobj{"key": key, "locale": req.Locale, "channel": req.Channel, "category": req.Category, "subject": req.Subject,
		"bodyHtml": req.BodyHTML, "bodyText": req.BodyText}
	if err := sc.call(ctx, sc.up.Notifications, port.StaffCall{Method: http.MethodPost, Path: "/v1/templates", Body: body}, &created); err != nil {
		return domain.NotificationTemplateVersion{}, err
	}
	if created.CreatedAt == "" {
		created.CreatedAt = sc.now.UTC().Format(time.RFC3339)
	}
	if created.CreatedBy == "" {
		created.CreatedBy = sc.me.ID
	}
	return created.version(), nil
}

func (s *staffNotificationsImpl) TestSend(ctx context.Context, c *domain.Caller, key string, req domain.NotificationPreviewRequest) (domain.NotificationTestSendResult, error) {
	if !domain.ValidTemplateKey(key) {
		return domain.NotificationTestSendResult{}, templateNotFound()
	}
	if err := validatePreview(&req); err != nil {
		return domain.NotificationTestSendResult{}, err
	}
	if req.Channel == "push" {
		return domain.NotificationTestSendResult{}, domain.ValidationProblem(map[string]string{"channel": "Push notifications can't be test-sent; use the preview"})
	}
	sc, err := s.r.require(ctx, c, notificationsModule, domain.LevelEdit)
	if err != nil {
		return domain.NotificationTestSendResult{}, err
	}
	if !sc.access.Can(domain.PermNotificationsTemplatesWrite) {
		return domain.NotificationTestSendResult{}, needsPermission(domain.PermNotificationsTemplatesWrite)
	}
	var up struct {
		To      string `json:"to"`
		Receipt struct {
			Provider string `json:"provider"`
		} `json:"receipt"`
	}
	if err := postTemplate(ctx, sc, "/v1/templates/"+url.PathEscape(key)+"/test-send", previewBody(req), &up); err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok {
			switch {
			case p.Status == http.StatusUnprocessableEntity && p.Code == "notifications.no-own-contact":
				return domain.NotificationTestSendResult{}, domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeValidationFailed,
					"No address to send to", "Test messages go only to your own email or phone, and none is on your account.").WithCause(err)
			case p.Status == http.StatusBadRequest:
				return domain.NotificationTestSendResult{}, renderProblem(p, req.Draft != nil)
			case p.Status == http.StatusNotFound:
				return domain.NotificationTestSendResult{}, templateNotFound().WithCause(err)
			}
		}
		return domain.NotificationTestSendResult{}, mapErr("notifications", err)
	}
	return domain.NotificationTestSendResult{To: up.To, Channel: req.Channel, Provider: strPtr(up.Receipt.Provider)}, nil
}

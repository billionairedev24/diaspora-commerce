package app

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffContentPagesService is Settings → Published pages (contracts/openapi/gateway-bff-staff.yaml, tag Published
// pages): the index of every public page, and for legal documents and help pages the editor behind it: preview data,
// a draft that changes nothing public, publication with a reason (legal documents need a second approver), the
// history of versions and rollback. Content owns the drafts, versions and audit rows; this adapts them to one shape.
type StaffContentPagesService interface {
	Pages(ctx context.Context, c *domain.Caller) (domain.ContentPageList, error)
	Page(ctx context.Context, c *domain.Caller, kind, key string) (domain.ContentPage, error)
	Version(ctx context.Context, c *domain.Caller, kind, key, version string) (domain.ContentDocument, error)
	SaveDraft(ctx context.Context, c *domain.Caller, kind, key string, in domain.ContentDraftInput) (domain.ContentDocument, error)
	DiscardDraft(ctx context.Context, c *domain.Caller, kind, key string) error
	Publish(ctx context.Context, c *domain.Caller, kind, key string, in domain.ContentPublishInput) (domain.ContentPage, error)
	Rollback(ctx context.Context, c *domain.Caller, kind, key string, in domain.ContentRollbackInput) (domain.ContentPage, error)
}

type staffContentPagesImpl struct{ r *staffResolver }

var _ StaffContentPagesService = (*staffContentPagesImpl)(nil)

// NewStaffContentPagesService returns the Published pages use case.
func NewStaffContentPagesService(d StaffDeps) StaffContentPagesService {
	return &staffContentPagesImpl{r: newStaffResolver(d)}
}

const (
	contentModule    = "siteCfg"
	permLegalEdit    = "content:legal.edit"
	permSiteEdit     = "content:site.edit"
	minReasonLength  = 5
	maxReasonLength  = 500
	maxChangeSummary = 1000
)

func contentNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "There is no such page.")
}

func isNotFound(err error) bool {
	var p *domain.Problem
	return errors.As(err, &p) && p.Status == http.StatusNotFound
}

// ---------------------------------------------------------------- access

type contentAccess struct {
	sc                    staffCtx
	canEdit, canPublish   bool
	secondApprover        bool
	publishReasonNotGiven string
}

func (s *staffContentPagesImpl) need(ctx context.Context, c *domain.Caller, kind string, write bool) (contentAccess, error) {
	if kind != domain.ContentKindLegal && kind != domain.ContentKindHelp {
		return contentAccess{}, contentNotFound()
	}
	sc, err := s.r.require(ctx, c, contentModule, domain.LevelView)
	if err != nil {
		return contentAccess{}, err
	}
	if sc.up.Content == nil {
		return contentAccess{}, domain.UpstreamUnavailable("content")
	}
	a := contentAccess{sc: sc}
	perm := permSiteEdit
	if kind == domain.ContentKindLegal {
		perm = permLegalEdit
		a.secondApprover = true
	}
	a.canEdit = sc.access.Can(perm) && sc.access.Allows(contentModule, domain.LevelEdit)
	a.canPublish = a.canEdit
	switch {
	case !a.canEdit:
		a.publishReasonNotGiven = "Your roles can view this page but not change it."
	case kind == domain.ContentKindLegal && !sc.access.Allows(contentModule, domain.LevelApprove):
		a.canPublish = false
		a.publishReasonNotGiven = "Publishing a legal document needs the Approve level on Site content & legal. You can save drafts."
	}
	if write && !a.canEdit {
		if !sc.access.Allows(contentModule, domain.LevelEdit) {
			return a, staffModuleDenied(contentModule, domain.LevelEdit)
		}
		return a, needsPermission(perm)
	}
	return a, nil
}

func (a contentAccess) view() domain.ContentAccess {
	out := domain.ContentAccess{CanEdit: a.canEdit, CanPublish: a.canPublish, SecondApprover: a.secondApprover}
	if a.publishReasonNotGiven != "" {
		out.Reason = ptr(a.publishReasonNotGiven)
	}
	return out
}

// ---------------------------------------------------------------- index

func (s *staffContentPagesImpl) Pages(ctx context.Context, c *domain.Caller) (domain.ContentPageList, error) {
	legalAccess, err := s.need(ctx, c, domain.ContentKindLegal, false)
	if err != nil {
		return domain.ContentPageList{}, err
	}
	sc := legalAccess.sc
	helpAccess, _ := s.need(ctx, c, domain.ContentKindHelp, false)
	out := domain.ContentPageList{Items: []domain.ContentPageRow{}}
	if sc.storefront != "" {
		out.StorefrontURL = ptr(sc.storefront)
	}

	var overview []jobj
	if err := sc.get(ctx, sc.up.Content, "/v1/legal/overview", nil, &overview); err != nil {
		return out, err
	}
	var help any
	if err := sc.get(ctx, sc.up.Content, "/v1/help/admin/articles", nil, &help); err != nil {
		return out, err
	}
	articles := jpage(help).items

	people := []string{}
	for _, o := range overview {
		people = append(people, jstr(o, "publishedBy"), jstr(o, "draftUpdatedBy"))
	}
	for _, a := range articles {
		people = append(people, jstr(a, "updatedBy"))
	}
	who := sc.names.labels(ctx, sc, personRef("updatedBy", "Updated by"), people...)

	sort.SliceStable(overview, func(i, j int) bool {
		return legalOrder(jstr(overview[i], "key")) < legalOrder(jstr(overview[j], "key"))
	})
	for _, o := range overview {
		key := jstr(o, "key")
		if !domain.IsLegalDocument(key) {
			continue
		}
		row := s.row(sc, domain.ContentKindLegal, key, jstr(o, "title"), domain.LegalGroupTitle(key), "/legal/"+key)
		row.Status = "PUBLISHED"
		if boolean(o, "draftBanner") {
			row.Status = "DRAFT_FOR_COUNSEL"
		}
		row.Version, row.EffectiveDate = jstr(o, "version"), strOrNil(jstr(o, "effectiveDate"))
		row.UpdatedAt, row.UpdatedByName = strOrNil(jstr(o, "publishedAt")), nameOrNil(who, jstr(o, "publishedBy"))
		row.HasDraft = boolean(o, "hasDraft")
		row.DraftUpdatedByName = nameOrNil(who, jstr(o, "draftUpdatedBy"))
		row.Editor, row.CanEdit, row.Locales = "page", legalAccess.canEdit, domain.ContentLocales
		out.Items = append(out.Items, row)
	}
	for _, a := range articles {
		slug := jstr(a, "slug")
		row := s.row(sc, domain.ContentKindHelp, slug, jstr(a, "title"), jstr(a, "topicTitle"), helpPath(slug))
		row.Status = "PUBLISHED"
		if jstr(a, "status") != "PUBLISHED" {
			row.Status, row.LivePath, row.LiveURL = "HIDDEN", nil, nil
		}
		row.Version = jstr(a, "revision")
		row.UpdatedAt, row.UpdatedByName = strOrNil(jstr(a, "updatedAt")), nameOrNil(who, jstr(a, "updatedBy"))
		row.HasDraft = boolean(a, "hasDraft")
		row.Editor, row.CanEdit, row.Locales = "page", helpAccess.canEdit, []string{"en"}
		out.Items = append(out.Items, row)
	}
	out.Items = append(out.Items, s.recordRows(sc)...)
	return out, nil
}

func (s *staffContentPagesImpl) row(sc staffCtx, kind, key, title, group, path string) domain.ContentPageRow {
	row := domain.ContentPageRow{Kind: kind, Key: key, Title: title, Group: group, Locales: []string{"en"}}
	row.LivePath = ptr(path)
	if u := sc.storefrontLink(path); u != "" {
		row.LiveURL = ptr(u)
	}
	return row
}

// recordRows are the public surfaces edited in their own tables or screens, plus the pages whose text still ships
// with the code (shown so staff can see they need a developer, rather than hunting for an editor that does not exist).
func (s *staffContentPagesImpl) recordRows(sc staffCtx) []domain.ContentPageRow {
	edit := func(perm string) bool {
		return sc.access.Can(perm) && sc.access.Allows(contentModule, domain.LevelEdit)
	}
	record := func(key, title, group, path, resource, id string, canEdit bool) domain.ContentPageRow {
		row := s.row(sc, "record", key, title, group, path)
		row.Status, row.Editor, row.CanEdit = "PUBLISHED", "record", canEdit
		row.Resource = ptr(resource)
		if id != "" {
			row.ResourceID = ptr(id)
		}
		return row
	}
	rows := []domain.ContentPageRow{
		record("site-settings", "Contacts & social links", "Site", "/", "site-settings", "site", edit(permSiteEdit)),
		record("maintenance", "Maintenance notice", "Site", "/", "maintenance", "", edit(permSiteEdit)),
		record("announcements", "Announcement bar", "Marketing", "/", "announcements", "", sc.access.Can("content:placements.edit") && sc.access.Allows("marketing", domain.LevelEdit)),
		record("placements", "Homepage banners & tiles", "Marketing", "/", "placements", "", sc.access.Can("content:placements.edit") && sc.access.Allows("marketing", domain.LevelEdit)),
	}
	tpl := s.row(sc, "record", "notification-templates", "Email, SMS and push messages", "Notifications", "")
	tpl.LivePath, tpl.LiveURL = nil, nil
	tpl.Status, tpl.Editor, tpl.CanEdit = "PUBLISHED", "record", sc.access.Allows("notifications", domain.LevelEdit)
	tpl.ConsoleHref = ptr("/settings?section=notifications")
	rows = append(rows, tpl)
	about := s.row(sc, "record", "about", "About AfroTransact", "Company", "/about")
	about.Status, about.Editor, about.CanEdit = "DEVELOPER_ONLY", "developer", false
	return append(rows, about)
}

func strOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func nameOrNil(names map[string]string, id string) *string {
	if id == "" {
		return nil
	}
	return strOrNil(names[id])
}

// ---------------------------------------------------------------- one page

func (s *staffContentPagesImpl) Page(ctx context.Context, c *domain.Caller, kind, key string) (domain.ContentPage, error) {
	a, err := s.need(ctx, c, kind, false)
	if err != nil {
		return domain.ContentPage{}, err
	}
	if kind == domain.ContentKindLegal {
		return s.legalPage(ctx, a, key)
	}
	return s.helpPage(ctx, a, key)
}

func (s *staffContentPagesImpl) Version(ctx context.Context, c *domain.Caller, kind, key, version string) (domain.ContentDocument, error) {
	a, err := s.need(ctx, c, kind, false)
	if err != nil {
		return domain.ContentDocument{}, err
	}
	sc := a.sc
	if kind == domain.ContentKindLegal {
		if !domain.IsLegalDocument(key) {
			return domain.ContentDocument{}, contentNotFound()
		}
		var o jobj
		if err := sc.get(ctx, sc.up.Content, "/v1/legal/"+url.PathEscape(key)+"/versions/"+url.PathEscape(version)+"/source", nil, &o); err != nil {
			return domain.ContentDocument{}, err
		}
		who := sc.names.labels(ctx, sc, personRef("publishedBy", "Published by"), jstr(o, "publishedBy"))
		return legalDocument(o, legalTitleOf(key), who), nil
	}
	if _, err := strconv.Atoi(version); err != nil || !domain.IsHelpSlug(key) {
		return domain.ContentDocument{}, contentNotFound()
	}
	var o jobj
	if err := sc.get(ctx, sc.up.Content, "/v1/help/articles/"+url.PathEscape(key)+"/revisions/"+url.PathEscape(version), nil, &o); err != nil {
		return domain.ContentDocument{}, err
	}
	who := sc.names.labels(ctx, sc, personRef("publishedBy", "Published by"), jstr(o, "publishedBy"))
	return helpRevisionDocument(o, who), nil
}

// legalTitleOf is the readable name of a document when content did not send one.
func legalTitleOf(key string) string { return domain.Humanize(key) }

// legalDocument maps content's version source (or draft) to the shared document shape.
func legalDocument(o jobj, title string, who map[string]string) domain.ContentDocument {
	title = firstNonEmpty(jstr(o, "title"), title)
	d := domain.ContentDocument{Version: jstr(o, "version"), EffectiveDate: strOrNil(jstr(o, "effectiveDate")), Title: title,
		Bodies: stringMap(o["bodies"]), NeedsReview: stringList(o["needsReview"]), ChangeSummary: strOrNil(jstr(o, "changeSummary"))}
	if v, ok := o["draftBanner"].(bool); ok {
		d.DraftBanner = &v
	}
	if _, ok := o["rowVersion"]; ok {
		rv := int64(num(o, "rowVersion"))
		d.RowVersion = &rv
	}
	d.UpdatedAt = strOrNil(firstNonEmpty(jstr(o, "updatedAt"), jstr(o, "publishedAt")))
	d.UpdatedByName = nameOrNil(who, firstNonEmpty(jstr(o, "updatedBy"), jstr(o, "publishedBy")))
	return d
}

func stringMap(v any) map[string]string {
	out := map[string]string{}
	if m, ok := v.(map[string]any); ok {
		for k, val := range m {
			if s, ok := val.(string); ok {
				out[k] = s
			}
		}
	}
	return out
}

func stringList(v any) []string {
	out := []string{}
	if l, ok := v.([]any); ok {
		for _, it := range l {
			if s, ok := it.(string); ok {
				out = append(out, s)
			}
		}
	}
	sort.Strings(out)
	return out
}

func (s *staffContentPagesImpl) legalPage(ctx context.Context, a contentAccess, key string) (domain.ContentPage, error) {
	sc := a.sc
	if !domain.IsLegalDocument(key) {
		return domain.ContentPage{}, contentNotFound()
	}
	base := "/v1/legal/" + url.PathEscape(key)
	var history []jobj
	if err := sc.get(ctx, sc.up.Content, base+"/history", nil, &history); err != nil {
		return domain.ContentPage{}, err
	}
	var current jobj
	for _, h := range history {
		if jstr(h, "status") == "CURRENT" {
			current = h
			break
		}
	}
	if current == nil {
		return domain.ContentPage{}, contentNotFound()
	}
	var published jobj
	if err := sc.get(ctx, sc.up.Content, base+"/versions/"+url.PathEscape(jstr(current, "version"))+"/source", nil, &published); err != nil {
		return domain.ContentPage{}, err
	}
	var draft jobj
	if err := sc.get(ctx, sc.up.Content, base+"/draft", nil, &draft); err != nil {
		if !isNotFound(err) {
			return domain.ContentPage{}, err
		}
		draft = nil
	}
	people := []string{jstr(published, "publishedBy"), jstr(draft, "updatedBy")}
	for _, h := range history {
		people = append(people, jstr(h, "publishedBy"))
	}
	who := sc.names.labels(ctx, sc, personRef("publishedBy", "Published by"), people...)
	title := titleOfLegal(published)
	page := domain.ContentPage{Kind: domain.ContentKindLegal, Key: key, Title: title, Group: domain.LegalGroupTitle(key),
		LivePath: ptr("/legal/" + key), Locales: domain.ContentLocales, Published: legalDocument(published, title, who),
		Versions: []domain.ContentVersionRef{}, Placeholders: s.placeholders(ctx, sc), Topics: []domain.FieldOption{}, Access: a.view()}
	if u := sc.storefrontLink("/legal/" + key); u != "" {
		page.LiveURL = ptr(u)
	}
	if draft != nil {
		d := legalDocument(draft, title, who)
		page.Draft = &d
	}
	for _, h := range history {
		page.Versions = append(page.Versions, domain.ContentVersionRef{Version: jstr(h, "version"), Status: jstr(h, "status"),
			EffectiveDate: strOrNil(jstr(h, "effectiveDate")), ChangeSummary: strOrNil(jstr(h, "changeSummary")),
			PublishedAt: strOrNil(jstr(h, "publishedAt")), PublishedByName: nameOrNil(who, jstr(h, "publishedBy")),
			DraftBanner: boolPtr(h, "draftBanner"), Locales: stringList(h["locales"])})
	}
	return page, nil
}

func titleOfLegal(published jobj) string {
	if t := jstr(published, "title"); t != "" {
		return t
	}
	return legalTitleOf(jstr(published, "key"))
}

func boolPtr(o jobj, k string) *bool {
	if v, ok := o[k].(bool); ok {
		return &v
	}
	return nil
}

// placeholders are the values legal Markdown fills {phone}, {legal}, {address}… with (content's LegalTemplate), so the
// preview of a draft reads exactly as it will when shoppers see it.
func (s *staffContentPagesImpl) placeholders(ctx context.Context, sc staffCtx) map[string]string {
	out := map[string]string{}
	var cfg jobj
	if err := sc.get(ctx, sc.up.Content, "/v1/site-config/settings", nil, &cfg); err != nil {
		return out
	}
	if emails, ok := at(cfg, "contacts.emails").(map[string]any); ok {
		for k, v := range emails {
			if str, ok := v.(string); ok {
				out[k] = str
			}
		}
	}
	out["phone"] = jstr(cfg, "contacts.supportPhoneDisplay")
	out["phoneE164"] = jstr(cfg, "contacts.supportPhone")
	out["smsProgramName"] = jstr(cfg, "smsProgramName")
	if addr, ok := at(cfg, "contacts.address").(map[string]any); ok {
		street := jstr(addr, "line1")
		if l2 := jstr(addr, "line2"); l2 != "" {
			street += ", " + l2
		}
		out["address"] = jstr(addr, "name") + " · " + street + " · " + jstr(addr, "city") + ", " + jstr(addr, "region") + " " + jstr(addr, "postalCode")
	}
	return out
}

func (s *staffContentPagesImpl) helpPage(ctx context.Context, a contentAccess, slug string) (domain.ContentPage, error) {
	sc := a.sc
	if !domain.IsHelpSlug(slug) {
		return domain.ContentPage{}, contentNotFound()
	}
	var raw any
	if err := sc.get(ctx, sc.up.Content, "/v1/help/admin/articles", nil, &raw); err != nil {
		return domain.ContentPage{}, err
	}
	var article jobj
	for _, it := range jpage(raw).items {
		if jstr(it, "slug") == slug {
			article = it
		}
	}
	if article == nil {
		return domain.ContentPage{}, contentNotFound()
	}
	base := "/v1/help/articles/" + url.PathEscape(slug)
	var revisions []jobj
	if err := sc.get(ctx, sc.up.Content, base+"/revisions", nil, &revisions); err != nil {
		return domain.ContentPage{}, err
	}
	var draft jobj
	if err := sc.get(ctx, sc.up.Content, base+"/draft", nil, &draft); err != nil {
		if !isNotFound(err) {
			return domain.ContentPage{}, err
		}
		draft = nil
	}
	people := []string{jstr(article, "updatedBy"), jstr(draft, "updatedBy")}
	for _, r := range revisions {
		people = append(people, jstr(r, "publishedBy"))
	}
	who := sc.names.labels(ctx, sc, personRef("updatedBy", "Updated by"), people...)
	page := domain.ContentPage{Kind: domain.ContentKindHelp, Key: slug, Title: jstr(article, "title"), Group: jstr(article, "topicTitle"),
		Locales: []string{"en"}, Versions: []domain.ContentVersionRef{}, Placeholders: map[string]string{}, Topics: helpTopicOptions, Access: a.view()}
	if jstr(article, "status") == "PUBLISHED" {
		page.LivePath = ptr(helpPath(slug))
		if u := sc.storefrontLink(helpPath(slug)); u != "" {
			page.LiveURL = ptr(u)
		}
	}
	page.Published = helpDocument(article, jstr(article, "revision"))
	page.Published.UpdatedAt, page.Published.UpdatedByName = strOrNil(jstr(article, "updatedAt")), nameOrNil(who, jstr(article, "updatedBy"))
	if draft != nil {
		d := helpDocument(draft, "draft")
		rv := int64(num(draft, "rowVersion"))
		d.RowVersion, d.ChangeSummary = &rv, strOrNil(jstr(draft, "changeSummary"))
		d.UpdatedAt, d.UpdatedByName = strOrNil(jstr(draft, "updatedAt")), nameOrNil(who, jstr(draft, "updatedBy"))
		page.Draft = &d
	}
	for _, r := range revisions {
		page.Versions = append(page.Versions, domain.ContentVersionRef{Version: jstr(r, "revision"), Status: "PUBLISHED",
			ChangeSummary: strOrNil(jstr(r, "changeSummary")), Reason: strOrNil(jstr(r, "reason")), PublishedAt: strOrNil(jstr(r, "publishedAt")),
			PublishedByName: nameOrNil(who, jstr(r, "publishedBy")), Locales: []string{"en"}})
	}
	if len(page.Versions) > 0 {
		page.Versions[0].Status = "CURRENT"
		for i := 1; i < len(page.Versions); i++ {
			page.Versions[i].Status = "ARCHIVED"
		}
	}
	return page, nil
}

func helpDocument(o jobj, version string) domain.ContentDocument {
	d := domain.ContentDocument{Version: version, Title: jstr(o, "title"), Summary: ptr(jstr(o, "summary")), Topic: ptr(jstr(o, "topic")),
		Bodies: map[string]string{"en": helpSectionsMarkdown(o)}, NeedsReview: []string{}}
	so := int(num(o, "sortOrder"))
	d.SortOrder = &so
	return d
}

func helpRevisionDocument(o jobj, who map[string]string) domain.ContentDocument {
	d := helpDocument(o, jstr(o, "revision"))
	d.ChangeSummary, d.Reason = strOrNil(jstr(o, "changeSummary")), strOrNil(jstr(o, "reason"))
	d.UpdatedAt, d.UpdatedByName = strOrNil(jstr(o, "publishedAt")), nameOrNil(who, jstr(o, "publishedBy"))
	return d
}

// ---------------------------------------------------------------- validation (also done live in the editor)

var (
	headingLine = regexp.MustCompile(`(?m)^## +(.+?) *$`)
	mdLink      = regexp.MustCompile(`\[[^\]]*]\(([^)\s]*)[^)]*\)`)
	linkScheme  = regexp.MustCompile(`^(https?://|mailto:|tel:|/|#)`)
)

// contentProblems are the rules every save and publication holds, so a page cannot be saved in a state the editor
// would have flagged: English present, no repeated section names, links that go somewhere safe.
func contentProblems(kind string, in domain.ContentDraftInput) map[string]string {
	errs := map[string]string{}
	en := strings.TrimSpace(in.Bodies["en"])
	if en == "" {
		errs["bodies.en"] = "The English text is required: it is the binding version"
	}
	for _, loc := range bodyLocales(in.Bodies) {
		if !slices.Contains(domain.ContentLocales, loc) {
			errs["bodies."+loc] = "Locale must be one of en, fr, es, ar, yo"
			continue
		}
		if kind == domain.ContentKindHelp && loc != "en" {
			errs["bodies."+loc] = "Help pages are written in English only"
			continue
		}
		text := in.Bodies[loc]
		seen := map[string]bool{}
		for _, m := range headingLine.FindAllStringSubmatch(text, -1) {
			h := strings.ToLower(strings.TrimSpace(m[1]))
			if seen[h] {
				errs["bodies."+loc] = fmt.Sprintf("Two sections are called “%s”. Give each its own heading.", strings.TrimSpace(m[1]))
				break
			}
			seen[h] = true
		}
		for _, m := range mdLink.FindAllStringSubmatch(text, -1) {
			if dest := m[1]; dest == "" || !linkScheme.MatchString(dest) {
				if _, taken := errs["bodies."+loc]; !taken {
					errs["bodies."+loc] = fmt.Sprintf("The link “%s” does not go anywhere. Use https://, /path, #section, mailto: or tel:.", dest)
				}
			}
		}
	}
	for _, loc := range in.NeedsReview {
		if loc == "en" || !slices.Contains(domain.ContentLocales, loc) {
			errs["needsReview"] = "Only translations (fr, es, ar, yo) are marked for review"
		}
	}
	if len(in.ChangeSummary) > maxChangeSummary {
		errs["changeSummary"] = "At most 1,000 characters"
	}
	if kind == domain.ContentKindHelp {
		if strings.TrimSpace(in.Title) == "" {
			errs["title"] = "Required"
		}
		if strings.TrimSpace(in.Summary) == "" {
			errs["summary"] = "Required"
		}
		if _, sectionErrs := parseHelpSections(in.Bodies["en"]); sectionErrs != nil {
			errs["bodies.en"] = sectionErrs["sections"]
		}
	}
	return errs
}

func bodyLocales(m map[string]string) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// ---------------------------------------------------------------- draft

func (s *staffContentPagesImpl) SaveDraft(ctx context.Context, c *domain.Caller, kind, key string, in domain.ContentDraftInput) (domain.ContentDocument, error) {
	a, err := s.need(ctx, c, kind, true)
	if err != nil {
		return domain.ContentDocument{}, err
	}
	sc := a.sc
	if errs := contentProblems(kind, in); len(errs) > 0 {
		return domain.ContentDocument{}, domain.ValidationProblem(errs)
	}
	if kind == domain.ContentKindLegal {
		if !domain.IsLegalDocument(key) {
			return domain.ContentDocument{}, contentNotFound()
		}
		body := jobj{"version": in.Version, "effectiveDate": in.EffectiveDate, "bodies": in.Bodies, "needsReview": nonNil(in.NeedsReview),
			"changeSummary": in.ChangeSummary}
		if in.DraftBanner != nil {
			body["draftBanner"] = *in.DraftBanner
		}
		if in.RowVersion != nil {
			body["rowVersion"] = *in.RowVersion
		}
		var saved jobj
		if err := sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/legal/" + url.PathEscape(key) + "/draft", Body: body}, &saved); err != nil {
			return domain.ContentDocument{}, err
		}
		who := sc.names.labels(ctx, sc, personRef("updatedBy", "Updated by"), jstr(saved, "updatedBy"))
		return legalDocument(saved, legalTitleOf(key), who), nil
	}
	if !domain.IsHelpSlug(key) {
		return domain.ContentDocument{}, contentNotFound()
	}
	sections, _ := parseHelpSections(in.Bodies["en"])
	body := jobj{"topic": in.Topic, "title": in.Title, "summary": in.Summary, "sections": sections, "changeSummary": in.ChangeSummary}
	if in.SortOrder != nil {
		body["sortOrder"] = *in.SortOrder
	}
	if in.RowVersion != nil {
		body["rowVersion"] = *in.RowVersion
	}
	var saved jobj
	if err := sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/help/articles/" + url.PathEscape(key) + "/draft", Body: body}, &saved); err != nil {
		return domain.ContentDocument{}, err
	}
	who := sc.names.labels(ctx, sc, personRef("updatedBy", "Updated by"), jstr(saved, "updatedBy"))
	d := helpDocument(saved, "draft")
	rv := int64(num(saved, "rowVersion"))
	d.RowVersion, d.ChangeSummary = &rv, strOrNil(jstr(saved, "changeSummary"))
	d.UpdatedAt, d.UpdatedByName = strOrNil(jstr(saved, "updatedAt")), nameOrNil(who, jstr(saved, "updatedBy"))
	return d, nil
}

func nonNil(l []string) []string {
	if l == nil {
		return []string{}
	}
	return l
}

func (s *staffContentPagesImpl) DiscardDraft(ctx context.Context, c *domain.Caller, kind, key string) error {
	a, err := s.need(ctx, c, kind, true)
	if err != nil {
		return err
	}
	path := "/v1/help/articles/" + url.PathEscape(key) + "/draft"
	if kind == domain.ContentKindLegal {
		if !domain.IsLegalDocument(key) {
			return contentNotFound()
		}
		path = "/v1/legal/" + url.PathEscape(key) + "/draft"
	} else if !domain.IsHelpSlug(key) {
		return contentNotFound()
	}
	return a.sc.call(ctx, a.sc.up.Content, port.StaffCall{Method: http.MethodDelete, Path: path}, nil)
}

// ---------------------------------------------------------------- publish and rollback

func (s *staffContentPagesImpl) Publish(ctx context.Context, c *domain.Caller, kind, key string, in domain.ContentPublishInput) (domain.ContentPage, error) {
	a, err := s.need(ctx, c, kind, true)
	if err != nil {
		return domain.ContentPage{}, err
	}
	if !a.canPublish {
		return domain.ContentPage{}, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", a.publishReasonNotGiven)
	}
	if n := len(strings.TrimSpace(in.Reason)); n < minReasonLength || n > maxReasonLength {
		return domain.ContentPage{}, domain.ValidationProblem(map[string]string{"reason": "Say why you are publishing (5 to 500 characters)"})
	}
	sc := a.sc
	body := jobj{"reason": strings.TrimSpace(in.Reason), "rowVersion": in.RowVersion}
	switch kind {
	case domain.ContentKindLegal:
		if !domain.IsLegalDocument(key) {
			return domain.ContentPage{}, contentNotFound()
		}
		if err := sc.post(ctx, sc.up.Content, "/v1/legal/"+url.PathEscape(key)+"/draft/publication", body, nil); err != nil {
			return domain.ContentPage{}, err
		}
	default:
		if !domain.IsHelpSlug(key) {
			return domain.ContentPage{}, contentNotFound()
		}
		if err := sc.post(ctx, sc.up.Content, "/v1/help/articles/"+url.PathEscape(key)+"/draft/publication", body, nil); err != nil {
			return domain.ContentPage{}, err
		}
	}
	return s.Page(ctx, c, kind, key)
}

func (s *staffContentPagesImpl) Rollback(ctx context.Context, c *domain.Caller, kind, key string, in domain.ContentRollbackInput) (domain.ContentPage, error) {
	a, err := s.need(ctx, c, kind, true)
	if err != nil {
		return domain.ContentPage{}, err
	}
	sc := a.sc
	switch kind {
	case domain.ContentKindLegal:
		if !domain.IsLegalDocument(key) {
			return domain.ContentPage{}, contentNotFound()
		}
		// A rollback of a legal document is a new draft with the older text; a second person publishes it.
		if err := sc.post(ctx, sc.up.Content, "/v1/legal/"+url.PathEscape(key)+"/draft/restoration", jobj{"version": in.Version}, nil); err != nil {
			return domain.ContentPage{}, err
		}
	default:
		if !domain.IsHelpSlug(key) {
			return domain.ContentPage{}, contentNotFound()
		}
		if !a.canPublish {
			return domain.ContentPage{}, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", a.publishReasonNotGiven)
		}
		if _, err := strconv.Atoi(in.Version); err != nil {
			return domain.ContentPage{}, domain.ValidationProblem(map[string]string{"version": "Choose a revision to go back to"})
		}
		if n := len(strings.TrimSpace(in.Reason)); n < minReasonLength || n > maxReasonLength {
			return domain.ContentPage{}, domain.ValidationProblem(map[string]string{"reason": "Say why you are going back (5 to 500 characters)"})
		}
		if err := sc.post(ctx, sc.up.Content, "/v1/help/articles/"+url.PathEscape(key)+"/revisions/"+url.PathEscape(in.Version)+"/restoration",
			jobj{"reason": strings.TrimSpace(in.Reason)}, nil); err != nil {
			return domain.ContentPage{}, err
		}
	}
	return s.Page(ctx, c, kind, key)
}

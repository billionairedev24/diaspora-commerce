package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- markets (content)

func marketsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Content, "/v1/markets/draft/countries", nil, &raw); err != nil {
			return nil, err
		}
		out := []jobj{}
		for _, c := range jpage(raw).items {
			if q.Status == "" || jstr(c, "status") == q.Status {
				out = append(out, c)
			}
		}
		return out, nil
	}
	return &staffResource{
		key: "markets", module: "markets", title: "Countries", statusKey: "status",
		columns: []domain.Column{col("code", "Code", "mono", 10), col("name", "Country", "strong", 30), col("liveRegions", "Live states", "number", 15),
			col("regionCount", "States", "number", 12), col("status", "Status", "status", 33)},
		facets:     domain.StaffFacets("LIVE", "COMING_SOON", "PLANNED", "PAUSED"),
		idOf:       func(o jobj) string { return jstr(o, "code") },
		list:       listAll(load),
		get:        getByPath("/v1/markets/draft/countries/", upContent),
		titleOf:    func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string { return jstr(o, "liveRegions") + " states live · draft" },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "status", "regions") },
		tables: func(o jobj) []domain.DetailTable {
			rows := objs(o, "regions")
			for _, r := range rows {
				on := []string{}
				if caps, ok := r["capabilities"].(map[string]any); ok {
					for k, v := range caps {
						if b, _ := v.(bool); b {
							on = append(on, domain.Humanize(k))
						}
					}
				}
				r["switchedOn"] = strings.Join(on, ", ")
			}
			return []domain.DetailTable{tableOf("States and provinces", rows, []domain.Column{col("code", "Code", "mono", 12), col("name", "Name", "strong", 28), col("kind", "Kind", "text", 15), col("switchedOn", "Switched on", "text", 45)}, "code")}
		},
		actions: []staffAction{
			{key: "publish", label: "Publish markets", tone: "primary", permission: "content:markets.edit", form: noteForm("Publish the draft as a new version", "Publish", false),
				run: func(ctx context.Context, sc staffCtx, _ jobj, v jobj) error {
					return sc.post(ctx, sc.up.Content, "/v1/markets/publications", omitEmpty(jobj{"note": vs(v, "note")}), nil)
				}},
			{key: "activate-all", label: "Activate every state", tone: "neutral", permission: "content:markets.edit", confirm: "Switch on every capability in every state (draft)?",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Content, "/v1/markets/draft/countries/"+url.PathEscape(jstr(o, "code"))+"/activate-all", jobj{}, nil)
				}},
			{key: "deactivate-all", label: "Deactivate every state", tone: "danger", permission: "content:markets.edit", confirm: "Switch off every capability in every state (draft)?",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Content, "/v1/markets/draft/countries/"+url.PathEscape(jstr(o, "code"))+"/deactivate-all", jobj{}, nil)
				}},
		},
		edit: &staffEdit{permission: "content:markets.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit country (draft)", SubmitLabel: "Save draft", Fields: []domain.FormField{
					withValue(withOptions(ff("status", "Status", "select", true), domain.StaffOptions("LIVE", "PAUSED", "COMING_SOON", "PLANNED")), jstr(o, "status")),
					withValue(withMax(ff("launchNote", "Launch note", "text", false), 200), jstr(o, "launchNote"))}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPatch, Path: "/v1/markets/draft/countries/" + url.PathEscape(jstr(o, "code")),
					Body: jobj{"status": vs(v, "status"), "launchNote": vs(v, "launchNote")}}, nil)
			}},
	}
}

// ---------------------------------------------------------------- site settings (content)

var staffSocials = []string{"instagram", "facebook", "tiktok", "x", "youtube", "linkedin", "whatsapp"}

func siteSettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Content, "/v1/site-config/settings", nil, &o); err != nil {
			return nil, err
		}
		o["id"] = "site"
		for _, s := range objs(o, "socials") {
			o["social."+jstr(s, "network")] = jstr(s, "url")
		}
		return o, nil
	}
	social := func(o jobj, network string) string { s, _ := o["social."+network].(string); return s }
	return &staffResource{
		key: "site-settings", module: "siteCfg", title: "Site settings",
		columns: []domain.Column{col("contacts.supportPhoneDisplay", "Support phone", "text", 22), col("contacts.emails.support", "Support email", "text", 28),
			col("smsProgramName", "SMS program", "text", 20), col("revision", "Revision", "number", 12), col("updatedAt", "Updated", "datetime", 18)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "site")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        get,
		titleOf:    func(jobj) string { return "Contacts & social links" },
		subtitleOf: func(o jobj) string { return "Revision " + jstr(o, "revision") + " · " + jstr(o, "updatedBy") },
		sections: func(o jobj) []domain.DetailSection {
			links := domain.DetailSection{Title: "Social links", Fields: []domain.DetailField{}}
			for _, s := range objs(o, "socials") {
				links.Fields = append(links.Fields, domain.DetailField{Label: domain.Humanize(jstr(s, "network")), Value: jstr(s, "url"), Kind: "link", Href: jstr(s, "url")})
			}
			out := autoSections(o, "", "socials", "identityProviders", "locales")
			for _, sec := range out {
				if sec.Title == "Details" {
					kept := []domain.DetailField{}
					for _, f := range sec.Fields {
						if !strings.HasPrefix(f.Label, "Social ") {
							kept = append(kept, f)
						}
					}
					sec.Fields = kept
				}
			}
			return append(out, links)
		},
		version: func(o jobj) string { return jstr(o, "revision") },
		edit: &staffEdit{permission: "content:site.edit",
			form: func(o jobj) domain.Form {
				fields := []domain.FormField{
					withValue(withPattern(ff("supportPhone", "Support phone (E.164)", "text", true), `^\+[1-9][0-9]{7,14}$`, "Use the international format, e.g. +18324300000. Customers and sellers who tap the WhatsApp chat button reach this number, so it must be a number WhatsApp can message."), jstr(o, "contacts.supportPhone")),
					withValue(withMax(ff("supportPhoneDisplay", "Phone as shown", "text", true), 30), jstr(o, "contacts.supportPhoneDisplay")),
					withValue(ff("supportEmail", "Support email", "email", true), jstr(o, "contacts.emails.support")),
					withValue(withMax(ff("smsProgramName", "SMS program name", "text", true), 60), jstr(o, "smsProgramName")),
					withValue(withPattern(ff("whatsappCommunityUrl", "WhatsApp community link (email footers)", "text", false),
						`^https://(chat\.whatsapp\.com/|(www\.)?whatsapp\.com/channel/)`, "Use the invite link, like https://chat.whatsapp.com/AbCdEf123456"),
						jstr(o, "whatsappCommunityUrl")),
					withValue(withMax(ff("summary", "What changed", "text", true), 200), ""),
				}
				for _, n := range staffSocials {
					fields = append(fields, withValue(withPattern(ff("social."+n, domain.Humanize(n), "text", false), `^https://`, "Links start with https://"), social(o, n)))
				}
				return domain.Form{Title: "Edit contacts & social links", SubmitLabel: "Publish", Fields: fields}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				contacts, _ := o["contacts"].(map[string]any)
				if contacts == nil {
					contacts = jobj{}
				}
				emails, _ := contacts["emails"].(map[string]any)
				if emails == nil {
					emails = jobj{}
				}
				emails["support"] = vs(v, "supportEmail")
				contacts["emails"], contacts["supportPhone"], contacts["supportPhoneDisplay"] = emails, vs(v, "supportPhone"), vs(v, "supportPhoneDisplay")
				socials := []jobj{}
				for i, n := range staffSocials {
					if u := vs(v, "social."+n); u != "" {
						socials = append(socials, jobj{"network": n, "url": u, "visible": true, "order": i})
					}
				}
				body := jobj{"expectedRevision": int(num(o, "revision")), "summary": vs(v, "summary"), "contacts": contacts, "socials": socials,
					"smsProgramName": vs(v, "smsProgramName"), "whatsappCommunityUrl": vs(v, "whatsappCommunityUrl")}
				for _, k := range []string{"appLinks", "paymentMethods", "identityProviders", "locales", "market"} {
					if val, ok := o[k]; ok {
						body[k] = val
					}
				}
				return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/site-config", Body: body}, nil)
			}},
	}
}

// ---------------------------------------------------------------- legal documents (content)

func legalResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var idx jobj
		if err := sc.get(ctx, sc.up.Content, "/v1/legal", nil, &idx); err != nil {
			return nil, err
		}
		return objs(idx, "documents"), nil
	}
	list := listAll(load)
	return &staffResource{
		key: "legal-documents", module: "siteCfg", title: "Legal documents",
		columns: []domain.Column{col("title", "Document", "strong", 36), col("key", "Key", "mono", 20), col("version", "Version", "text", 14), col("effectiveDate", "Effective", "date", 30)},
		idOf:    func(o jobj) string { return jstr(o, "key") },
		list:    list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			o, err := findIn(list, "key")(ctx, sc, id)
			if err != nil {
				return nil, err
			}
			var versions any
			if sc.get(ctx, sc.up.Content, "/v1/legal/"+url.PathEscape(id)+"/versions", nil, &versions) == nil {
				o["versions"] = jpage(versions).items
			}
			return o, nil
		},
		titleOf: func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string {
			return "Version " + jstr(o, "version") + " · effective " + jstr(o, "effectiveDate")
		},
		sections: func(o jobj) []domain.DetailSection { return autoSections(o, "", "versions") },
		tables: func(o jobj) []domain.DetailTable {
			rows, _ := o["versions"].([]jobj)
			return []domain.DetailTable{tableOf("Versions", rows, []domain.Column{col("version", "Version", "text", 15), col("effectiveDate", "Effective", "date", 20),
				col("status", "Status", "status", 20), col("changeSummary", "What changed", "text", 45)}, "version")}
		},
		links: func(o jobj) []domain.RecordLink {
			if h := jstr(o, "href"); h != "" {
				return []domain.RecordLink{{Label: "Open the published page", Href: h}}
			}
			return nil
		},
		actions: []staffAction{
			{key: "raise-version", label: "New version", tone: "primary", permission: "content:legal.edit",
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Raise the version (the current one is archived)", SubmitLabel: "Publish version", Fields: []domain.FormField{
						withPattern(ff("version", "Version", "text", true), `^[0-9]+\.[0-9]+$`, "Major.minor, e.g. 3.3"), ff("effectiveDate", "Effective date", "date", true),
						withMax(ff("changeSummary", "What changed", "textarea", true), 1000), ff("body", "Document (Markdown, English)", "textarea", true),
						ff("draftBanner", "Show the draft banner", "toggle", false)}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Content, "/v1/legal/"+url.PathEscape(jstr(o, "key"))+"/versions", jobj{"version": vs(v, "version"), "effectiveDate": vs(v, "effectiveDate"),
						"changeSummary": vs(v, "changeSummary"), "bodies": jobj{"en": vs(v, "body")}, "draftBanner": vb(v, "draftBanner")}, nil)
				}},
		},
		edit: &staffEdit{permission: "content:legal.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Amend the current version", SubmitLabel: "Save", Fields: []domain.FormField{
					withValue(ff("effectiveDate", "Effective date", "date", true), jstr(o, "effectiveDate")),
					withValue(ff("draftBanner", "Show the draft banner", "toggle", false), boolean(o, "draftBanner"))}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPatch, Path: "/v1/legal/" + url.PathEscape(jstr(o, "key")) + "/current",
					Body: jobj{"effectiveDate": vs(v, "effectiveDate"), "draftBanner": vb(v, "draftBanner")}}, nil)
			}},
	}
}

// ---------------------------------------------------------------- announcements, placements and ads (content)

func placementStaffActions(key string, act func(string, func(jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error) []staffAction {
	actions := []staffAction{
		{key: "submit", label: "Send for review", tone: "primary", permission: "content:placements.edit", confirm: "Send this placement for review?", when: inState("DRAFT", "REJECTED"), run: act("/submit", func(jobj) jobj { return nil })},
		{key: "approve", label: "Approve", tone: "primary", permission: "content:placements.approve", level: domain.LevelApprove, confirm: "Approve and schedule?", when: inState("IN_REVIEW", "SUBMITTED"), run: act("/approve", func(jobj) jobj { return nil })},
		{key: "reject", label: "Reject", tone: "danger", permission: "content:placements.approve", level: domain.LevelApprove, form: noteForm("Reject back to draft", "Reject", true), when: inState("IN_REVIEW", "SUBMITTED"),
			run: act("/reject", func(v jobj) jobj { return jobj{"note": vs(v, "note")} })},
		{key: "end", label: "End now", tone: "danger", permission: "content:placements.edit", also: []string{"content:placements.approve"}, confirm: "End this placement now?", when: inState("SCHEDULED", "LIVE"),
			run: act("/end", func(jobj) jobj { return nil })},
	}
	if key != "placements" {
		return actions
	}
	actions = append(actions, staffAction{
		key: "delete", label: "Delete", tone: "danger", permission: "advert:delete", confirm: "Permanently delete this advert?",
		when: func(sc staffCtx, o jobj) string {
			if !boolean(o, "paid") {
				return "Only paid adverts can be deleted."
			}
			return inState("DRAFT", "ENDED")(sc, o)
		}, removes: true,
		run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
			return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodDelete, Path: "/v1/placements/" + url.PathEscape(jstr(o, "id"))}, nil)
		},
	})
	return actions
}

func placementsResource(key string) *staffResource {
	announcements := key == "announcements"
	title := "Placements & ads"
	if announcements {
		title = "Announcements"
	}
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.Content, "/v1/placements/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	headline := func(o jobj) string {
		return firstNonEmpty(jstr(o, "payload.text"), jstr(o, "payload.title"), jstr(o, "payload.headline"))
	}
	payloadOf := func(slot string, v jobj, prev jobj) jobj {
		p := jobj{}
		if prev != nil {
			if m, ok := prev["payload"].(map[string]any); ok {
				for k, val := range m {
					p[k] = val
				}
			}
		}
		if slot == "announcement_bar" {
			p["text"] = vs(v, "text")
		} else {
			p["title"] = vs(v, "text")
		}
		if h := vs(v, "href"); h != "" {
			p["href"] = h
		}
		return p
	}
	slots := domain.StaffOptions("hero_carousel", "display_banner", "home_tiles", "home_product_row", "announcement_bar")
	baseFields := func(o jobj) []domain.FormField {
		return []domain.FormField{
			withValue(withMax(ff("name", "Name", "text", true), 120), jstr(o, "name")),
			withValue(withMax(ff("text", "Text or headline", "text", true), 140), headline(o)),
			withValue(withPattern(ff("href", "Link", "text", false), `^/`, "A storefront path starting with /"), jstr(o, "payload.href")),
			withValue(ff("startsAt", "Starts", "datetime", true), jstr(o, "schedule.startsAt")),
			withValue(ff("endsAt", "Ends", "datetime", false), jstr(o, "schedule.endsAt")),
		}
	}
	return &staffResource{
		key: key, module: "marketing", title: title, statusKey: "status", exportable: true,
		columns: []domain.Column{col("name", "Name", "strong", 28), col("slot", "Slot", "mono", 18), col("advertiser", "Advertiser", "text", 16),
			col("schedule.startsAt", "Starts", "date", 12), col("schedule.endsAt", "Ends", "date", 12), col("status", "Status", "status", 14)},
		facets: domain.StaffFacets("DRAFT", "IN_REVIEW", "SCHEDULED", "LIVE", "ENDED", "REJECTED"),
		list: listAll(func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
			v := url.Values{}
			if q.Status != "" {
				v.Set("status", q.Status)
			}
			if announcements {
				v.Set("slot", "announcement_bar")
			}
			var raw any
			if err := sc.get(ctx, sc.up.Content, "/v1/placements", v, &raw); err != nil {
				return nil, err
			}
			return jpage(raw).items, nil
		}),
		get:     getByPath("/v1/placements/", upContent),
		titleOf: func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string {
			s := jstr(o, "slot")
			if boolean(o, "paid") {
				s += " · paid by " + jstr(o, "advertiser")
			}
			return s
		},
		version: func(o jobj) string { return jstr(o, "version") },
		actions: placementStaffActions(key, act),
		edit: &staffEdit{permission: "content:placements.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit draft", SubmitLabel: "Save draft", Fields: baseFields(o)}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				schedule := omitEmpty(jobj{"startsAt": vs(v, "startsAt"), "endsAt": vs(v, "endsAt")})
				body := jobj{"slot": jstr(o, "slot"), "name": vs(v, "name"), "payload": payloadOf(jstr(o, "slot"), v, o), "schedule": schedule,
					"priority": int(num(o, "priority")), "paid": boolean(o, "paid"), "expectedVersion": int64(num(o, "version"))}
				for _, k := range []string{"targeting", "frequencyCap"} {
					if val, ok := o[k]; ok && val != nil {
						body[k] = val
					}
				}
				if a := jstr(o, "advertiser"); a != "" {
					body["advertiser"] = a
				}
				return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPut, Path: "/v1/placements/" + url.PathEscape(jstr(o, "id")), Body: body}, nil)
			}},
		create: &staffCreate{permission: "content:placements.edit",
			form: func(context.Context, staffCtx) domain.Form {
				fields := baseFields(jobj{})
				if !announcements {
					fields = append([]domain.FormField{withOptions(ff("slot", "Slot", "select", true), slots)}, fields...)
					fields = append(fields, withMax(ff("advertiser", "Advertiser (paid ads)", "text", false), 80))
				}
				t := "New announcement"
				if !announcements {
					t = "New placement or ad"
				}
				return domain.Form{Title: t, SubmitLabel: "Create draft", Fields: fields}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				slot := "announcement_bar"
				if !announcements {
					slot = vs(v, "slot")
				}
				body := jobj{"slot": slot, "name": vs(v, "name"), "payload": payloadOf(slot, v, nil),
					"schedule": omitEmpty(jobj{"startsAt": vs(v, "startsAt"), "endsAt": vs(v, "endsAt")}), "priority": 10,
					"paid": vs(v, "advertiser") != "", "targeting": jobj{"markets": []string{"us"}}}
				if a := vs(v, "advertiser"); a != "" {
					body["advertiser"] = a
				}
				var out jobj
				if err := sc.post(ctx, sc.up.Content, "/v1/placements", body, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

// ---------------------------------------------------------------- identity providers (identity)

func identityProvidersResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Identity, "/v1/admin/identity-providers", nil, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	list := listAll(load)
	status := func(o jobj) string {
		if boolean(o, "enabled") {
			return "ENABLED"
		}
		return "DISABLED"
	}
	set := func(on bool) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
			return sc.call(ctx, sc.up.Identity, port.StaffCall{Method: http.MethodPut, Path: "/v1/admin/identity-providers/" + url.PathEscape(jstr(o, "id")), Body: jobj{"enabled": on}}, nil)
		}
	}
	return &staffResource{
		key: "identity-providers", module: "dev", title: "Identity providers", status: status,
		columns: []domain.Column{col("displayName", "Provider", "strong", 30), col("configured", "Configured", "text", 20), col("updatedBy", "Changed by", "text", 25), col("status", "Sign-in button", "status", 25)},
		cells:   func(o jobj) map[string]any { return map[string]any{"status": status(o)} },
		list:    list, get: findIn(list, "id"),
		titleOf: func(o jobj) string { return jstr(o, "displayName") },
		subtitleOf: func(o jobj) string {
			if boolean(o, "enabled") {
				return "Shown on sign-in and sign-up"
			}
			return "Hidden from sign-in and sign-up"
		},
		actions: []staffAction{
			{key: "enable", label: "Show button", tone: "primary", permission: "access:admin", confirm: "Show this social login on the storefront?",
				when: func(_ staffCtx, o jobj) string {
					switch {
					case boolean(o, "enabled"):
						return "Already shown."
					case !boolean(o, "configured"):
						return "Not configured at identity yet (client id and secret)."
					}
					return ""
				}, run: set(true)},
			{key: "disable", label: "Hide button", tone: "danger", permission: "access:admin", confirm: "Hide this social login? People who use it must sign in another way.",
				when: func(_ staffCtx, o jobj) string {
					if !boolean(o, "enabled") {
						return "Already hidden."
					}
					return ""
				}, run: set(false)},
		},
	}
}

// ---------------------------------------------------------------- privacy requests (content)

func privacyRequestsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw any
		var query url.Values
		if q.Status != "" {
			query = url.Values{"status": {q.Status}}
		}
		if err := sc.get(ctx, sc.up.Content, "/v1/privacy/requests", query, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	resolve := func(outcome string) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.Content, "/v1/privacy/requests/"+url.PathEscape(jstr(o, "reference"))+"/resolution",
				omitEmpty(jobj{"outcome": outcome, "note": vs(v, "note")}), nil)
		}
	}
	open := func(_ staffCtx, o jobj) string {
		if jstr(o, "status") != "RECEIVED" {
			return "This request is already " + strings.ToLower(jstr(o, "status")) + "."
		}
		return ""
	}
	return &staffResource{
		key: "privacy-requests", module: "siteCfg", title: "Privacy requests", statusKey: "status",
		columns: []domain.Column{col("reference", "Reference", "mono", 14), col("type", "Request", "strong", 14), col("email", "Email", "text", 26),
			col("state", "State", "text", 8), col("dueAt", "Due", "date", 14), col("submittedAt", "Received", "datetime", 14), col("status", "Status", "status", 10)},
		facets:  domain.StaffFacets("RECEIVED", "COMPLETED", "DENIED"),
		idOf:    func(o jobj) string { return jstr(o, "reference") },
		list:    listAll(load),
		get:     getByPath("/v1/privacy/requests/", upContent),
		titleOf: func(o jobj) string { return jstr(o, "reference") + " · " + domain.Humanize(jstr(o, "type")) },
		subtitleOf: func(o jobj) string {
			return jstr(o, "email") + " · " + jstr(o, "state") + " · due " + jstr(o, "dueAt")
		},
		sections: func(o jobj) []domain.DetailSection { return autoSections(o, "status", "id", "version") },
		actions: []staffAction{
			{key: "complete", label: "Mark completed", tone: "primary", permission: "content:legal.edit", when: open,
				form: noteForm("What was done (kept with the request)", "Mark completed", false), run: resolve("COMPLETED")},
			{key: "deny", label: "Deny", tone: "danger", permission: "content:legal.edit", when: open,
				form: noteForm("Why the request is denied (the requester can appeal)", "Deny request", true), run: resolve("DENIED")},
		},
	}
}

package app

import (
	"context"
	"net/http"
	"net/url"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// staffResources is the registry of every table of the console.
func staffResources() map[string]*staffResource {
	all := []*staffResource{
		usersResource(), rolesResource(), applicationsResource(),
	}
	all = append(all, partnersResources()...)
	all = append(all, ledgerResources()...)
	all = append(all, platformSettingsResources()...)
	all = append(all, identityAdminResources()...)
	all = append(all,
		supportCasesResource(), auditResource(),
		ordersResource(), productsResource(), moderationResource(), inventoryResource(), purchaseOrdersResource(), routesResource(),
		dispatchShipmentsResource(), sosAlertsResource(), vehicleDefectsResource(), driverEventsResource(), pickupLocationsResource(),
		claimsResource(), returnsResource(), payoutsResource(), feeSchedulesResource(), taxRatesResource(), taxCodesResource(), taxRulesResource(), taxReviewResource(),
		marketsResource(), siteSettingsResource(), maintenanceResource(), taxFilingsResource(), taxNexusResource(), taxNexusResource(), legalResource(), helpArticlesResource(), privacyRequestsResource(), placementsResource("announcements"), placementsResource("placements"), couponsResource(),
		identityProvidersResource(), exchangeRatesResource(),
	)
	out := make(map[string]*staffResource, len(all))
	for _, r := range all {
		out[r.key] = r
	}
	return out
}

func platformRoleOptions() []domain.FieldOption {
	out := []domain.FieldOption{}
	for _, r := range domain.DefaultPlatformRoles {
		out = append(out, domain.FieldOption{Value: r, Label: domain.PlatformRoleLabel(r)})
	}
	return out
}

// ---------------------------------------------------------------- users (identity)

func usersResource() *staffResource {
	userStatus := func(o jobj) string {
		if boolean(o, "enabled") {
			return "ACTIVE"
		}
		return "DISABLED"
	}
	reasonQ := func(v jobj) url.Values { return url.Values{"reason": {vs(v, "reason")}} }
	return &staffResource{
		key: "users", module: "rbac", title: "People", exportable: true, searchPlaceholder: "Search by name or email",
		columns: []domain.Column{col("name", "Name", "strong", 24), col("email", "Email", "text", 30), col("principalType", "Type", "text", 12),
			col("roles", "Roles", "text", 22), col("status", "Status", "status", 12)},
		status: userStatus,
		cells:  func(o jobj) map[string]any { return map[string]any{"status": userStatus(o)} },
		list: listPaged("/v1/admin/users", upIdentity, func(q staffQuery, v url.Values) {
			v.Del("status")
			if q.Q != "" {
				v.Set("q", q.Q)
			}
		}),
		get:        getByPath("/v1/admin/users/", upIdentity),
		titleOf:    func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string { return jstr(o, "email") },
		activity: func(ctx context.Context, sc staffCtx, o jobj) []domain.ActivityEntry {
			var page jobj
			if sc.get(ctx, sc.up.Identity, "/v1/admin/access-log", url.Values{"userId": {jstr(o, "id")}, "size": {"20"}}, &page) != nil {
				return nil
			}
			out := []domain.ActivityEntry{}
			for _, e := range objs(page, "items") {
				text := strings.TrimSpace(domain.Humanize(jstr(e, "action")) + " " + firstNonEmpty(jstr(e, "targetRole"), jstr(e, "subject")) + " " + jstr(e, "detail"))
				out = append(out, domain.ActivityEntry{At: timeOf(e, "at"), Actor: jstr(e, "actorId"), Text: text})
			}
			return out
		},
		actions: []staffAction{
			{key: "assign-role", label: "Assign role", tone: "primary", permission: "access:admin",
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Assign a role", SubmitLabel: "Assign", Fields: []domain.FormField{
						withOptions(ff("roleKey", "Role", "select", true), platformRoleOptions()), withMax(ff("reason", "Reason", "text", true), 300)}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.call(ctx, sc.up.Identity, port.StaffCall{Method: http.MethodPut, Path: "/v1/admin/users/" + url.PathEscape(jstr(o, "id")) + "/roles/" + url.PathEscape(vs(v, "roleKey")), Query: reasonQ(v)}, nil)
				}},
			{key: "remove-role", label: "Remove role", tone: "neutral", permission: "access:admin",
				form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
					opts := []domain.FieldOption{}
					for _, r := range strs(o, "roles") {
						opts = append(opts, domain.FieldOption{Value: r, Label: domain.PlatformRoleLabel(r)})
					}
					return &domain.Form{Title: "Remove a role (takes effect on the next token)", SubmitLabel: "Remove", Fields: []domain.FormField{
						withOptions(ff("roleKey", "Role", "select", true), opts), withMax(ff("reason", "Reason", "text", true), 300)}}
				},
				when: func(_ staffCtx, o jobj) string {
					if len(strs(o, "roles")) == 0 {
						return "This person has no roles."
					}
					return ""
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.call(ctx, sc.up.Identity, port.StaffCall{Method: http.MethodDelete, Path: "/v1/admin/users/" + url.PathEscape(jstr(o, "id")) + "/roles/" + url.PathEscape(vs(v, "roleKey")), Query: reasonQ(v)}, nil)
				}},
			{key: "disable", label: "Disable account", tone: "danger", permission: "access:admin", form: reasonForm("Disable the account (all sessions end)", "Disable"),
				when: func(_ staffCtx, o jobj) string {
					if !boolean(o, "enabled") {
						return "Already disabled."
					}
					return ""
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(jstr(o, "id"))+"/disable", jobj{"reason": vs(v, "reason")}, nil)
				}},
			{key: "enable", label: "Enable account", tone: "neutral", permission: "access:admin", confirm: "Re-enable this account?",
				when: func(_ staffCtx, o jobj) string {
					if boolean(o, "enabled") {
						return "Already enabled."
					}
					return ""
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(jstr(o, "id"))+"/enable", jobj{}, nil)
				}},
		},
		create: &staffCreate{permission: "access:admin",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New staff account", SubmitLabel: "Create account", Fields: []domain.FormField{
					withMax(ff("firstName", "First name", "text", true), 80), withMax(ff("lastName", "Last name", "text", true), 80),
					withPattern(ff("email", "Work email", "email", true), `^[^@\s]+@afrotransact\.com$`, "Staff accounts use an @afrotransact.com address"),
					withPattern(ff("phone", "Mobile", "text", true), `^\+[1-9][0-9]{7,14}$`, "Use the international format, e.g. +18325550100"),
					withMax(ff("password", "Temporary password", "password", true), 128),
					withOptions(ff("roles", "Roles", "multiselect", true), platformRoleOptions()),
				}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				var out jobj
				body := jobj{"firstName": vs(v, "firstName"), "lastName": vs(v, "lastName"), "email": vs(v, "email"), "phone": vs(v, "phone"),
					"password": vs(v, "password"), "roles": vlist(v, "roles")}
				if err := sc.post(ctx, sc.up.Identity, "/v1/admin/users", body, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

// ---------------------------------------------------------------- roles (identity)

func rolesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Identity, "/v1/admin/roles", url.Values{"portal": {"platform"}}, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	list := listAll(load)
	permPath := func(o jobj, v jobj) port.StaffCall {
		return port.StaffCall{Path: "/v1/admin/roles/" + url.PathEscape(jstr(o, "key")) + "/permissions/" + url.PathEscape(vs(v, "permissionKey")),
			Query: url.Values{"reason": {vs(v, "reason")}}}
	}
	permForm := func(title, submit string) func(context.Context, staffCtx, jobj) *domain.Form {
		return func(context.Context, staffCtx, jobj) *domain.Form {
			return &domain.Form{Title: title, SubmitLabel: submit, Fields: []domain.FormField{
				withPattern(ff("permissionKey", "Permission", "text", true), `^[a-z]+:[a-z.:-]+$`, "A permission key, e.g. refunds:issue"),
				withMax(ff("reason", "Reason", "text", true), 300)}}
		}
	}
	return &staffResource{
		key: "roles", module: "rbac", title: "Roles",
		columns: []domain.Column{col("name", "Role", "strong", 24), col("key", "Key", "mono", 26), col("description", "What it can do", "text", 38), col("permissionCount", "Permissions", "number", 12)},
		cells: func(o jobj) map[string]any {
			return map[string]any{"permissionCount": float64(len(strs(o, "permissions")))}
		},
		idOf: func(o jobj) string { return jstr(o, "key") },
		list: list, get: findIn(list, "key"),
		titleOf: func(o jobj) string { return jstr(o, "name") }, subtitleOf: func(o jobj) string { return jstr(o, "key") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "Role", Fields: []domain.DetailField{{Label: "Key", Value: jstr(o, "key"), Kind: "mono"}, {Label: "Portal", Value: jstr(o, "portal"), Kind: "text"},
					{Label: "Description", Value: jstr(o, "description"), Kind: "text"}}},
				{Title: "Permissions", Fields: []domain.DetailField{{Label: "Granted", Value: strings.Join(strs(o, "permissions"), ", "), Kind: "text"}}},
			}
		},
		actions: []staffAction{
			{key: "add-permission", label: "Add permission", tone: "neutral", permission: "access:admin", form: permForm("Add a permission to the role", "Add"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					c := permPath(o, v)
					c.Method = http.MethodPut
					return sc.call(ctx, sc.up.Identity, c, nil)
				}},
			{key: "remove-permission", label: "Remove permission", tone: "danger", permission: "access:admin", form: permForm("Remove a permission (holders lose it on their next token)", "Remove"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					c := permPath(o, v)
					c.Method = http.MethodDelete
					return sc.call(ctx, sc.up.Identity, c, nil)
				}},
		},
	}
}

// ---------------------------------------------------------------- applications (seller, two-step review)

// staffBFFPath is where each web app proxies the BFF (config.Web.BFFPath default): document links are same-origin URLs
// that the browser opens with the staff session.
const staffBFFPath = "/api/bff"

func applicationsResource() *staffResource {
	// A reason fits some decisions only: seller seeds each code for APPROVE, REJECT or ANY, so Approve offers "checks
	// passed", Reject adds sanctions and duplicates, and the rest offer what can go wrong.
	fits := func(appliesTo, action string) bool {
		approval := action == "APPROVE" || action == "FINAL_APPROVE"
		switch appliesTo {
		case "APPROVE":
			return approval
		case "REJECT":
			return action == "REJECT"
		default:
			return !approval || appliesTo == ""
		}
	}
	reasonOptions := func(ctx context.Context, sc staffCtx, action string) []domain.FieldOption {
		var codes []jobj
		opts := []domain.FieldOption{}
		if sc.get(ctx, sc.up.Seller, "/v1/review-reason-codes", nil, &codes) == nil {
			for _, c := range codes {
				if !fits(jstr(c, "appliesTo"), action) {
					continue
				}
				opts = append(opts, domain.FieldOption{Value: jstr(c, "code"), Label: firstNonEmpty(jstr(c, "label"), domain.Humanize(jstr(c, "code")))})
			}
		}
		if len(opts) == 0 {
			opts = domain.StaffOptions("ALL_CHECKS_PASSED", "TIN_MISMATCH", "DOCUMENT_UNREADABLE", "PROHIBITED_CATEGORY", "OTHER")
		}
		return opts
	}
	decision := func(path, action, title, submit, intro string, noteRequired bool, extra ...domain.FormField) (func(context.Context, staffCtx, jobj) *domain.Form, func(context.Context, staffCtx, jobj, jobj) error) {
		noteLabel := "Note (internal: the applicant does not see it)"
		if action == "REQUEST_INFO" || action == "REJECT" {
			noteLabel = "Message to the applicant"
		}
		form := func(ctx context.Context, sc staffCtx, _ jobj) *domain.Form {
			reason := withOptions(ff("reasonCode", "Reason", "select", true), reasonOptions(ctx, sc, action))
			if len(reason.Options) == 1 {
				reason.Value = reason.Options[0].Value // Approve has one reason: it is already chosen
			}
			fields := []domain.FormField{reason}
			fields = append(fields, extra...)
			fields = append(fields, withMax(ff("note", noteLabel, "textarea", noteRequired), 2000))
			return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: fields}
		}
		// The console's Idempotency-Key is relayed: seller replays a retried decision instead of refusing it. Slow: a
		// final approval activates the Stripe subscription before it answers.
		run := func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			body := jobj{"action": action, "reasonCode": vs(v, "reasonCode"), "note": vs(v, "note"),
				"actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)}
			if l := vlist(v, "failedChecks"); len(l) > 0 {
				body["failedChecks"] = l
			}
			if items := vs(v, "requestedItems"); items != "" {
				body["requestedItems"] = strings.Split(items, "\n")
			}
			return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPost, Path: "/v1/applications/" + url.PathEscape(jstr(o, "id")) + path,
				Body: omitEmpty(body), IdempotencyKey: sc.idem, Slow: true}, nil)
		}
		return form, run
	}
	approveForm, approveRun := decision("/review", "APPROVE", "Approve for final review (step 1)", "Approve",
		"The documents and details check out. The application goes to a different approver for the final decision; the applicant is not told yet.", false)
	flagForm, flagRun := decision("/review", "FLAG", "Flag for senior review", "Flag",
		"Something does not check out and needs a senior decision. The application moves to the final approver with the failed checks and your note. The applicant is not told, and cannot change anything until someone uses Return to applicant (you can, even though you flagged it).", false,
		withOptions(ff("failedChecks", "Failed checks", "multiselect", true), domain.StaffOptions("TIN_MISMATCH", "IDENTITY_UNVERIFIED", "DOCUMENT_UNREADABLE", "PROHIBITED_CATEGORY", "ADDRESS_UNVERIFIED")))
	infoForm, infoRun := decision("/review", "REQUEST_INFO", "Return to the applicant", "Return to applicant",
		"The applicant is emailed what is missing, with your message, and can edit and resubmit. The review pauses until they do; then it starts again at step 1. You can use this on a flagged application too, including one you flagged: a failed check the applicant can fix (a tax ID that does not match, an unreadable document) is best sent back to them.", true,
		domain.FormField{Key: "requestedItems", Label: "What is missing (one per line)", Type: "textarea", Required: true})
	finalForm, finalRun := decision("/decision", "FINAL_APPROVE", "Give final approval (step 2)", "Approve",
		"Approves the seller or provider: their account opens and their plan starts. This cannot be undone here.", false)
	finalInfoForm, finalInfoRun := decision("/decision", "REQUEST_INFO", "Return to the applicant", "Return to applicant",
		"Use this when a flagged or reviewed application needs the applicant to fix or add something. They are emailed what is missing, with your message, and can edit and resubmit; it then starts again at step 1.", true,
		domain.FormField{Key: "requestedItems", Label: "What is missing (one per line)", Type: "textarea", Required: true})
	backForm, backRun := decision("/decision", "SEND_BACK", "Send back to step 1", "Send back",
		"Returns the application to the reviewer to check again; your note tells them what to look at.", true)
	rejectForm, rejectRun := decision("/decision", "REJECT", "Reject the application", "Reject",
		"Declines the application and tells the applicant, with the reason you pick.", true)
	// Step 2 (final approve, send back, reject) follows seller's state machine: awaiting final approval or flagged,
	// never by the step-one reviewer.
	finalStep := func(sc staffCtx, o jobj) string {
		if r := stateReason(jstr(o, "status"), "AWAITING_FINAL_APPROVAL", "FLAGGED"); r != "" {
			return r
		}
		if rid := jstr(o, "reviewerId"); rid != "" && rid == sc.me.ID {
			return "You reviewed step 1 — a different approver must decide step 2."
		}
		return ""
	}
	// past: the application already went past the action; seller answers a repeat idempotently and names who decided.
	past := func(statuses ...string) func(jobj) bool {
		return func(o jobj) bool { return slices.Contains(statuses, strings.ToUpper(jstr(o, "status"))) }
	}
	stepOneDone := past("AWAITING_FINAL_APPROVAL", "FLAGGED", "INFO_REQUESTED", "APPROVED", "REJECTED")
	decided := past("APPROVED", "REJECTED")
	return &staffResource{
		key: "applications", module: "apps", title: "Applications", statusKey: "status", exportable: true,
		columns: []domain.Column{col("reference", "Reference", "mono", 13), col("displayName", "Business", "strong", 25), col("kind", "Kind", "text", 10),
			col("state", "State", "text", 8), col("planCode", "Plan", "text", 10), col("submittedAt", "Submitted", "date", 14), col("status", "Status", "status", 20)},
		facets: domain.StaffFacets("PENDING_REVIEW", "FLAGGED", "AWAITING_FINAL_APPROVAL", "INFO_REQUESTED", "APPROVED", "REJECTED"),
		list:   listPaged("/v1/applications", upSeller, nil),
		get:    getByPath("/v1/applications/", upSeller),
		cells: func(o jobj) map[string]any {
			return map[string]any{"displayName": cellValue(firstNonEmpty(jstr(o, "displayName"), jstr(o, "business.storeName"), jstr(o, "businessDetails.legalName")))}
		},
		// The documents the applicant uploaded open in a new tab for the reviewer; seller logs each opening.
		file: func(ctx context.Context, sc staffCtx, id, assetID string) (string, error) {
			var doc jobj
			if err := sc.get(ctx, sc.up.Seller, "/v1/applications/"+url.PathEscape(id)+"/documents/"+url.PathEscape(assetID), nil, &doc); err != nil {
				return "", err
			}
			u := jstr(doc, "url")
			if u == "" {
				return "", domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That document is not available.")
			}
			return u, nil
		},
		links: func(o jobj) []domain.RecordLink {
			docs, _ := o["documents"].([]any)
			out := make([]domain.RecordLink, 0, len(docs))
			for _, d := range docs {
				doc, ok := d.(map[string]any)
				if !ok || jstr(doc, "assetId") == "" {
					continue
				}
				out = append(out, domain.RecordLink{Label: "View " + firstNonEmpty(jstr(doc, "label"), domain.Humanize(jstr(doc, "kind"))),
					Href: staffBFFPath + "/v1/staff/resources/applications/" + url.PathEscape(jstr(o, "id")) + "/files/" + url.PathEscape(jstr(doc, "assetId")), External: true})
			}
			return out
		},
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "displayName"), jstr(o, "business.storeName"), jstr(o, "reference"))
		},
		subtitleOf: func(o jobj) string {
			return strings.TrimSpace(jstr(o, "reference") + " · " + domain.Humanize(jstr(o, "kind")))
		},
		actions: []staffAction{
			{key: "review-approve", label: "Approve (step 1)", tone: "primary", permission: "applications:review", level: domain.LevelApprove, form: approveForm, when: inState("PENDING_REVIEW"), settled: stepOneDone, run: approveRun},
			{key: "review-flag", label: "Flag for senior review", tone: "neutral", permission: "applications:review", level: domain.LevelApprove, form: flagForm, when: inState("PENDING_REVIEW"), settled: stepOneDone, run: flagRun},
			{key: "review-request-info", label: "Return to applicant", tone: "neutral", permission: "applications:review", level: domain.LevelApprove, form: infoForm, when: inState("PENDING_REVIEW", "FLAGGED"), settled: past("INFO_REQUESTED", "AWAITING_FINAL_APPROVAL", "APPROVED", "REJECTED"), run: infoRun},
			{key: "final-approve", label: "Final approval", tone: "primary", permission: "applications:approve", level: domain.LevelApprove, form: finalForm, when: finalStep, settled: decided, run: finalRun},
			{key: "final-request-info", label: "Return to applicant (step 2)", tone: "neutral", permission: "applications:approve", level: domain.LevelApprove, form: finalInfoForm, when: finalStep, settled: past("INFO_REQUESTED", "APPROVED", "REJECTED"), run: finalInfoRun},
			{key: "send-back", label: "Send back", tone: "neutral", permission: "applications:approve", level: domain.LevelApprove, form: backForm, when: finalStep, settled: decided, run: backRun},
			{key: "reject", label: "Reject", tone: "danger", permission: "applications:approve", level: domain.LevelApprove, form: rejectForm, when: finalStep, settled: decided, run: rejectRun},
		},
	}
}

// ---------------------------------------------------------------- support cases (trust-ops, Lola hand-offs)

func supportCasesResource() *staffResource {
	return &staffResource{
		key: "support-cases", module: "support", title: "Support cases", statusKey: "status", exportable: true,
		columns: []domain.Column{col("reference", "Case", "mono", 12), col("customerId", "Customer", "mono", 18), col("reason", "Reason", "text", 22),
			col("assignee", "Assignee", "text", 16), col("openedAt", "Opened", "datetime", 16), col("status", "Status", "status", 16)},
		facets:  domain.StaffFacets("OPEN", "IN_PROGRESS", "RESOLVED"),
		list:    listPaged("/v1/support-cases", upTrustOps, nil),
		get:     getByPath("/v1/support-cases/", upTrustOps),
		titleOf: func(o jobj) string { return strings.TrimSpace(jstr(o, "reference") + " · " + jstr(o, "reason")) },
		subtitleOf: func(o jobj) string {
			return "Customer " + jstr(o, "customerId") + " · " + domain.Humanize(jstr(o, "source"))
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Open customer 360", Href: "/support?customer=" + url.QueryEscape(jstr(o, "customerId")) + "&case=" + url.QueryEscape(jstr(o, "id"))}}
		},
		actions: []staffAction{
			{key: "assign", label: "Assign to me", tone: "neutral", permission: "support:respond", confirm: "Take this case?", when: inState("OPEN", "IN_PROGRESS"),
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.TrustOps, "/v1/support-cases/"+url.PathEscape(jstr(o, "id"))+"/assign", jobj{}, nil)
				}},
			{key: "resolve", label: "Resolve", tone: "primary", permission: "support:respond", when: inState("OPEN", "IN_PROGRESS"),
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Resolve the case", SubmitLabel: "Resolve", Fields: []domain.FormField{withMax(ff("resolution", "Resolution", "textarea", true), 2000)}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TrustOps, "/v1/support-cases/"+url.PathEscape(jstr(o, "id"))+"/resolve", jobj{"resolution": vs(v, "resolution")}, nil)
				}},
		},
	}
}

// ---------------------------------------------------------------- audit log (trust-ops)

func auditResource() *staffResource {
	return &staffResource{
		key: "audit", module: "audit", title: "Audit log", exportable: true, searchPlaceholder: "Entity id",
		columns: []domain.Column{col("seq", "#", "number", 8), col("occurredAt", "When", "datetime", 16), col("actorId", "Actor", "strong", 16),
			col("action", "Action", "mono", 18), col("entityType", "Entity", "text", 12), col("summary", "Summary", "text", 30)},
		idOf: func(o jobj) string { return jstr(o, "seq") },
		list: listPaged("/v1/audit/entries", upTrustOps, func(q staffQuery, v url.Values) {
			v.Del("status")
			if q.Q != "" {
				v.Set("entityId", q.Q)
			}
		}),
		get:     getByPath("/v1/audit/entries/", upTrustOps),
		titleOf: func(o jobj) string { return jstr(o, "action") + " #" + jstr(o, "seq") },
		subtitleOf: func(o jobj) string {
			return jstr(o, "actorId") + " · " + jstr(o, "entityType") + " " + jstr(o, "entityId")
		},
	}
}

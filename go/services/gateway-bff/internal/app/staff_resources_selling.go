package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Selling approvals (docs/runbooks/selling-approvals.md). The case type "Selling approval" sits in the staff queue next to
// Applications: the same two-step review, assignment, who-has-it, SLAs and escalation (the seller serves both from
// the same review settings). Staff also see the approvals sellers hold (pause, restore, withdraw) and edit the selling
// rules and gated brands (selling-rules:manage), every change with a reason and an audit trail.

const (
	sellingCasesPath     = "/v1/selling-applications"
	sellingRulesPath     = "/v1/selling-rules"
	sellingApprovalsPath = "/v1/selling-approvals"
)

func sellingResources() []*staffResource {
	return []*staffResource{sellingCasesResource(), sellerApprovalsResource(), sellingRulesResource()}
}

// docKinds are the documents a rule can ask for, as staff read them.
var docKinds = []domain.FieldOption{
	{Value: "PURCHASE_INVOICE", Label: "Purchase invoice (within 180 days)"}, {Value: "PHOTOS_ALL_SIDES", Label: "Photos of all sides"},
	{Value: "BRAND_AUTHORIZATION", Label: "Brand authorization"}, {Value: "SAFETY_CERTIFICATE", Label: "Safety or lab certificate"},
	{Value: "PERMIT_LICENCE", Label: "Permit or licence (with expiry)"}, {Value: "LIABILITY_INSURANCE", Label: "Liability insurance naming AfroTransact (with expiry)"},
}

func docKindLabel(kind string) string {
	for _, o := range docKinds {
		if o.Value == kind {
			return o.Label
		}
	}
	return domain.Humanize(kind)
}

func scopeTypeLabel(t string) string {
	return map[string]string{"CATEGORY": "Category", "PRODUCT_TYPE": "Product type", "BRAND": "Brand", "BRAND_IN_CATEGORY": "Brand in a category",
		"PRODUCT": "Single product", "CAPABILITY": "Seller capability"}[t]
}

func levelWords(level string) string {
	return map[string]string{"SELL": "Open: sell now", "APPLY": "Apply to sell", "NOT_AVAILABLE": "Not available"}[level]
}

// ---------------------------------------------------------------- the case: "Selling approval"

func sellingCasesResource() *staffResource {
	fits := func(appliesTo, action string) bool {
		approval := action == "APPROVE" || action == "FINAL_APPROVE"
		switch appliesTo {
		case "APPROVE":
			return approval
		case "REJECT":
			return action == "REJECT" || action == "DISQUALIFY"
		default:
			return !approval || appliesTo == ""
		}
	}
	reasonOptions := func(ctx context.Context, sc staffCtx, action string) []domain.FieldOption {
		var codes []jobj
		opts := []domain.FieldOption{}
		if sc.get(ctx, sc.up.Seller, "/v1/review-reason-codes", nil, &codes) == nil {
			for _, c := range codes {
				if fits(jstr(c, "appliesTo"), action) {
					opts = append(opts, domain.FieldOption{Value: jstr(c, "code"), Label: firstNonEmpty(jstr(c, "label"), domain.Humanize(jstr(c, "code")))})
				}
			}
		}
		if len(opts) == 0 {
			opts = domain.StaffOptions("ALL_CHECKS_PASSED", "DOCUMENT_UNREADABLE", "BRAND_AUTH_INVALID", "NOT_ELIGIBLE", "OTHER")
		}
		return opts
	}
	decision := func(path, action, title, submit, intro string, noteRequired bool, extra ...domain.FormField) (func(context.Context, staffCtx, jobj) *domain.Form, func(context.Context, staffCtx, jobj, jobj) error) {
		noteLabel := "Note (internal: the seller does not see it)"
		if action == "REQUEST_INFO" || action == "REJECT" || action == "DISQUALIFY" {
			noteLabel = "Message to the seller"
		}
		form := func(ctx context.Context, sc staffCtx, _ jobj) *domain.Form {
			reason := withOptions(ff("reasonCode", "Reason", "select", true), reasonOptions(ctx, sc, action))
			if len(reason.Options) == 1 {
				reason.Value = reason.Options[0].Value
			}
			fields := []domain.FormField{reason}
			fields = append(fields, extra...)
			fields = append(fields, withMax(ff("note", noteLabel, "textarea", noteRequired), 2000))
			return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: fields}
		}
		run := func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			body := jobj{"action": action, "reasonCode": vs(v, "reasonCode"), "note": vs(v, "note"), "reapplyAfter": vs(v, "reapplyAfter"),
				"actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)}
			if l := vlist(v, "failedChecks"); len(l) > 0 {
				body["failedChecks"] = l
			}
			if items := vs(v, "requestedItems"); items != "" {
				body["requestedItems"] = strings.Split(items, "\n")
			}
			return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPost, Path: sellingCasesPath + "/" + url.PathEscape(jstr(o, "id")) + path,
				Body: omitEmpty(body), IdempotencyKey: sc.idem, Slow: true}, nil)
		}
		return form, run
	}
	missingItems := domain.FormField{Key: "requestedItems", Label: "What is missing (one per line)", Type: "textarea", Required: true}
	approveForm, approveRun := decision("/review", "APPROVE", "Approve for final review (step 1)", "Approve",
		"The documents check out: the dates, the brand authorization, the invoice and the insurance. The case goes to a different approver for the final decision; the seller is not told yet.", false)
	flagForm, flagRun := decision("/review", "FLAG", "Flag for senior review", "Flag",
		"Something does not check out and needs a senior decision. The case moves to the final approver with the failed checks and your note. The seller is not told.", false,
		withOptions(ff("failedChecks", "Failed checks", "multiselect", true), domain.StaffOptions("DOCUMENT_UNREADABLE", "BRAND_AUTH_INVALID", "INVOICE_TOO_OLD", "INSURANCE_INSUFFICIENT", "PERMIT_MISSING", "SAFETY_CERT_MISSING")))
	infoForm, infoRun := decision("/review", "REQUEST_INFO", "Ask the seller for more", "Return to seller",
		"The seller is told what is missing, with your message, and can send new documents. The review pauses until they do; then it starts again at step 1.", true, missingItems)
	finalForm, finalRun := decision("/decision", "FINAL_APPROVE", "Give final approval (step 2)", "Approve",
		"The seller is approved for this scope. Every listing there goes live after the usual checks, until the earliest document expires. Listings paused for an earlier expiry come back.", false)
	finalInfoForm, finalInfoRun := decision("/decision", "REQUEST_INFO", "Ask the seller for more (step 2)", "Return to seller",
		"Use this when a flagged or reviewed case needs the seller to fix or add something. It then starts again at step 1.", true, missingItems)
	backForm, backRun := decision("/decision", "SEND_BACK", "Send back to step 1", "Send back",
		"Returns the case to the reviewer to check again; your note tells them what to look at.", true)
	rejectForm, rejectRun := decision("/decision", "REJECT", "Reject the application", "Reject",
		"Declines this application and tells the seller why. They can apply again at once.", true)
	disqualifyForm, disqualifyRun := decision("/decision", "DISQUALIFY", "Cannot apply (not appealable)", "Disqualify",
		"The seller cannot sell here: they are told the reason and the day they may apply again. This cannot be appealed. Use it for fraud, counterfeits or a safety record that disqualifies them; use Reject for a fixable application.", true,
		withValue(ff("reapplyAfter", "They may apply again from", "date", true), time.Now().UTC().AddDate(0, 0, 90).Format(time.DateOnly)))
	finalStep := func(sc staffCtx, o jobj) string {
		if r := stateReason(jstr(o, "status"), "AWAITING_FINAL_APPROVAL", "FLAGGED"); r != "" {
			return r
		}
		if rid := jstr(o, "reviewerId"); rid != "" && rid == sc.me.ID {
			return "You reviewed step 1: a different approver must decide step 2."
		}
		return ""
	}
	past := func(statuses ...string) func(jobj) bool {
		return func(o jobj) bool { return slices.Contains(statuses, strings.ToUpper(jstr(o, "status"))) }
	}
	stepOneDone := past("AWAITING_FINAL_APPROVAL", "FLAGGED", "INFO_REQUESTED", "APPROVED", "REJECTED")
	decided := past("APPROVED", "REJECTED")

	assignRun := func(action string) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			body := jobj{"action": action, "assigneeId": vs(v, "assigneeId"), "note": vs(v, "note"), "reason": vs(v, "reason"),
				"actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)}
			return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPost, Path: sellingCasesPath + "/" + url.PathEscape(jstr(o, "id")) + "/assignment",
				Body: omitEmpty(body), IdempotencyKey: sc.idem}, nil)
		}
	}
	noteOnly := func(title, submit, intro string) func(context.Context, staffCtx, jobj) *domain.Form {
		return func(context.Context, staffCtx, jobj) *domain.Form {
			return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: []domain.FormField{withMax(ff("note", "Note (internal)", "textarea", false), 500)}}
		}
	}
	assignForm := func(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
		var people []jobj
		opts := []domain.FieldOption{}
		if sc.get(ctx, sc.up.Seller, sellingCasesPath+"/"+url.PathEscape(jstr(o, "id"))+"/assignment/candidates", nil, &people) == nil {
			for _, p := range people {
				opts = append(opts, domain.FieldOption{Value: jstr(p, "id"), Label: firstNonEmpty(jstr(p, "name"), "Unknown user")})
			}
		}
		pick := withOptions(ff("assigneeId", "Assign to", "select", true), opts)
		pick.Help = "Only people who can hold this case at its stage are listed: they hold the permission, are not the applicant and, for approval, did not do the first review."
		return &domain.Form{Title: "Assign or take over", SubmitLabel: "Assign", Intro: "Gives the case to one person. The person is told, and the change is recorded with their name.",
			Fields: []domain.FormField{pick, withMax(ff("note", "Note (internal)", "textarea", false), 500)}}
	}
	escalateForm := func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: "Escalate now", SubmitLabel: "Escalate", Intro: "Raises the escalation one level now: the people set up for that level are told and can take over or reassign.",
			Fields: []domain.FormField{withMax(ff("reason", "Why are you escalating?", "textarea", true), 500)}}
	}
	workActions := []staffAction{
		{key: "claim", label: "Claim", tone: "primary", permission: "applications:review", also: []string{"applications:approve"}, when: flagReason("Claim", "You can't take this case: it is with someone, or you may not hold it."), run: assignRun("CLAIM")},
		{key: "assign", label: "Assign…", tone: "neutral", permission: "applications:assign", also: []string{"applications:approve", "applications:review"}, form: assignForm, when: flagReason("Assign", "Only a supervisor, or the person an escalation went to, can assign."), run: assignRun("ASSIGN")},
		{key: "release", label: "Release to the pool", tone: "neutral", permission: "applications:review", also: []string{"applications:approve", "applications:assign"}, form: noteOnly("Release to the pool", "Release", "Anyone who can hold this case will be able to claim it."), when: flagReason("Release", "Nobody holds this case, or it is not yours to release."), run: assignRun("RELEASE")},
		{key: "escalate", label: "Escalate now", tone: "danger", permission: "applications:review", also: []string{"applications:approve", "applications:assign"}, form: escalateForm, when: flagReason("Escalate", "Only the person who has the case or a supervisor can escalate, and it is not at the top level yet."), run: assignRun("ESCALATE")},
	}
	return &staffResource{
		key: "selling-approvals", module: "apps", title: "Selling approvals", statusKey: "status", exportable: true,
		columns: []domain.Column{col("reference", "Reference", "mono", 11), col("scopeLabel", "Approval for", "strong", 18), col("orgName", "Seller", "text", 16), col("kindWords", "Type", "text", 9),
			col("with", "With", "text", 12), col("waiting", "Waiting", "duration", 8), col("sla", "SLA", "text", 8), col("escalation", "Escalation", "text", 9),
			col("submittedAt", "Submitted", "date", 10), col("status", "Status", "status", 12)},
		facets: applicationQueues(),
		list:   listPaged(sellingCasesPath, upSeller, func(q staffQuery, v url.Values) { applicationQueueParams(q.Status, v) }),
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			o, err := getByPath(sellingCasesPath+"/", upSeller)(ctx, sc, id)
			if err == nil && o != nil {
				nameFromTimeline(o)
			}
			return o, err
		},
		names: []nameRef{personRef("reviewerId", "Reviewer"), personRef("finalApproverId", "Final approver"), orgRef("orgId")},
		cells: func(o jobj) map[string]any {
			cells := workflowCells(o)
			cells["kindWords"] = sellingKindWords(o)
			return cells
		},
		banner:   returnedBanner,
		activity: func(_ context.Context, _ staffCtx, o jobj) []domain.ActivityEntry { return timelineActivity(o) },
		sections: sellingCaseSections,
		file: func(ctx context.Context, sc staffCtx, id, assetID string) (string, error) {
			var doc jobj
			if err := sc.get(ctx, sc.up.Seller, sellingCasesPath+"/"+url.PathEscape(id)+"/documents/"+url.PathEscape(assetID), nil, &doc); err != nil {
				return "", err
			}
			u := jstr(doc, "url")
			if u == "" {
				return "", domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That document is not available.")
			}
			return u, nil
		},
		links: func(o jobj) []domain.RecordLink {
			out := []domain.RecordLink{}
			for _, d := range objs(o, "documents") {
				if jstr(d, "assetId") == "" {
					continue
				}
				out = append(out, domain.RecordLink{Label: "View " + firstNonEmpty(jstr(d, "label"), domain.Humanize(jstr(d, "kind"))),
					Href: staffBFFPath + "/v1/staff/resources/selling-approvals/" + url.PathEscape(jstr(o, "id")) + "/files/" + url.PathEscape(jstr(d, "assetId")), External: true})
			}
			return out
		},
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "scopeLabel"), jstr(o, "reference")) + " · " + firstNonEmpty(jstr(o, "orgName"), "Seller")
		},
		subtitleOf: func(o jobj) string {
			return strings.TrimSpace(jstr(o, "reference") + " · Selling approval · " + stageLine(o))
		},
		actions: append(workActions, []staffAction{
			{key: "review-approve", label: "Approve (step 1)", tone: "primary", permission: "applications:review", level: domain.LevelApprove, form: approveForm, when: inState("PENDING_REVIEW"), settled: stepOneDone, run: approveRun},
			{key: "review-flag", label: "Flag for senior review", tone: "neutral", permission: "applications:review", level: domain.LevelApprove, form: flagForm, when: inState("PENDING_REVIEW"), settled: stepOneDone, run: flagRun},
			{key: "review-request-info", label: "Ask the seller for more", tone: "neutral", permission: "applications:review", level: domain.LevelApprove, form: infoForm, when: inState("PENDING_REVIEW", "FLAGGED"), settled: past("INFO_REQUESTED", "AWAITING_FINAL_APPROVAL", "APPROVED", "REJECTED"), run: infoRun},
			{key: "final-approve", label: "Final approval", tone: "primary", permission: "applications:approve", level: domain.LevelApprove, form: finalForm, when: finalStep, settled: decided, run: finalRun},
			{key: "final-request-info", label: "Ask the seller for more (step 2)", tone: "neutral", permission: "applications:approve", level: domain.LevelApprove, form: finalInfoForm, when: finalStep, settled: past("INFO_REQUESTED", "APPROVED", "REJECTED"), run: finalInfoRun},
			{key: "send-back", label: "Send back", tone: "neutral", permission: "applications:approve", level: domain.LevelApprove, form: backForm, when: finalStep, settled: decided, run: backRun},
			{key: "reject", label: "Reject", tone: "danger", permission: "applications:approve", level: domain.LevelApprove, form: rejectForm, when: finalStep, settled: decided, run: rejectRun},
			{key: "disqualify", label: "Cannot apply (not appealable)", tone: "danger", permission: "applications:approve", level: domain.LevelApprove, form: disqualifyForm, when: finalStep, settled: decided, run: disqualifyRun},
		}...),
	}
}

func sellingKindWords(o jobj) string {
	if jstr(o, "source") == "MIGRATION" {
		return "From bakery flag"
	}
	if jstr(o, "kind") == "RENEWAL" {
		return "Renewal"
	}
	return "New"
}

// sellingCaseSections lays out who has it, what was asked, the documents with their dates, the seller's current approval and the decision.
func sellingCaseSections(o jobj) []domain.DetailSection {
	out := workflowSections(o)
	asked := []domain.DetailField{fieldOf("Approval for", jstr(o, "scopeLabel"), "text"), fieldOf("Kind of approval", scopeTypeLabel(jstr(o, "scopeType")), "text"),
		fieldOf("Seller", jstr(o, "orgName"), "text"), fieldOf("Applied by", jstr(o, "applicantName"), "text"), fieldOf("Type", sellingKindWords(o), "text"),
		fieldOf("Submitted", jstr(o, "submittedAt"), "datetime"), fieldOf("Times submitted", strconv.Itoa(int(num(o, "submissionCount"))), "text")}
	if n := jstr(o, "note"); n != "" {
		asked = append(asked, fieldOf("Seller's note", n, "text"))
	}
	out = append(out, domain.DetailSection{Title: "Application", Fields: asked})
	docs := []domain.DetailField{}
	have := map[string]bool{}
	for _, d := range objs(o, "documents") {
		have[jstr(d, "kind")] = true
		text := firstNonEmpty(jstr(d, "fileName"), "file")
		if v := jstr(d, "issuedOn"); v != "" {
			text += " · dated " + v
		}
		if v := jstr(d, "expiresOn"); v != "" {
			text += " · expires " + v
		}
		if b, _ := d["namesAfroTransact"].(bool); b {
			text += " · names AfroTransact as additional insured"
		}
		docs = append(docs, fieldOf(firstNonEmpty(jstr(d, "label"), docKindLabel(jstr(d, "kind"))), text, "text"))
	}
	for _, r := range objs(o, "required") {
		if k := jstr(r, "kind"); !have[k] {
			word := "Missing"
			if b, _ := r["optional"].(bool); b {
				word = "Not sent (optional: or a reason why)"
			}
			docs = append(docs, fieldOf(firstNonEmpty(jstr(r, "label"), docKindLabel(k)), word, "text"))
		}
	}
	if len(docs) == 0 {
		docs = append(docs, fieldOf("Documents", "None asked for: decide on the application", "text"))
	}
	out = append(out, domain.DetailSection{Title: "Documents", Fields: docs})
	if a, ok := o["approval"].(map[string]any); ok && len(a) > 0 {
		cur := []domain.DetailField{fieldOf("Status", domain.Humanize(jstr(a, "status")), "text")}
		if v := jstr(a, "expiresAt"); v != "" {
			cur = append(cur, fieldOf("Runs until", v, "datetime"))
		}
		if v := jstr(a, "reason"); v != "" {
			cur = append(cur, fieldOf("Reason", v, "text"))
		}
		if b, _ := a["listingsPaused"].(bool); b {
			cur = append(cur, fieldOf("Listings", "Paused (never deleted; they return when it is renewed)", "text"))
		}
		out = append(out, domain.DetailSection{Title: "The seller's approval now", Fields: cur})
	}
	dec := []domain.DetailField{}
	if v := jstr(o, "reviewerName"); v != "" {
		dec = append(dec, fieldOf("Reviewer (step 1)", v, "text"))
	}
	if v := jstr(o, "finalApproverName"); v != "" {
		dec = append(dec, fieldOf("Approver (step 2)", v, "text"))
	}
	if v := jstr(o, "reasonCode"); v != "" {
		dec = append(dec, fieldOf("Reason", domain.Humanize(v), "text"))
	}
	if v := jstr(o, "decisionNote"); v != "" {
		dec = append(dec, fieldOf("Message to the seller", v, "text"))
	}
	if items := strs(o, "requestedItems"); len(items) > 0 {
		dec = append(dec, fieldOf("Asked of the seller", strings.Join(items, ", "), "text"))
	}
	if items := strs(o, "failedChecks"); len(items) > 0 {
		dec = append(dec, fieldOf("Failed checks", strings.Join(items, ", "), "text"))
	}
	if b, _ := o["disqualified"].(bool); b {
		dec = append(dec, fieldOf("Cannot apply", "Not appealable. May apply again from "+jstr(o, "reapplyAfter"), "text"))
	}
	if len(dec) > 0 {
		out = append(out, domain.DetailSection{Title: "Decision", Fields: dec})
	}
	return out
}

// ---------------------------------------------------------------- approvals sellers hold

func sellerApprovalsResource() *staffResource {
	reasonedForm := func(title, submit, intro string, withDate bool) func(context.Context, staffCtx, jobj) *domain.Form {
		return func(context.Context, staffCtx, jobj) *domain.Form {
			fields := []domain.FormField{withMax(withMin(ff("reason", "Reason (the seller reads it)", "textarea", true), 10), 500)}
			if withDate {
				fields = append(fields, withValue(ff("reapplyAfter", "They may apply again from", "date", true), time.Now().UTC().AddDate(0, 0, 90).Format(time.DateOnly)))
			}
			return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: fields}
		}
	}
	act := func(action string) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			body := omitEmpty(jobj{"reason": vs(v, "reason"), "reapplyAfter": vs(v, "reapplyAfter"),
				"actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)})
			return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPost, Path: sellingApprovalsPath + "/" + url.PathEscape(jstr(o, "id")) + "/" + action, Body: body}, nil)
		}
	}
	return &staffResource{
		key: "seller-approvals", module: "apps", title: "Seller approvals", statusKey: "status", exportable: true,
		columns: []domain.Column{col("orgName", "Seller", "strong", 18), col("label", "Approved for", "text", 22), col("scopeTypeWords", "Kind", "text", 12), col("expiresAt", "Runs until", "date", 11),
			col("daysWords", "Expires in", "text", 12), col("pauseWords", "Listings", "text", 14), col("status", "Status", "status", 12)},
		facets: domain.StaffFacets("APPROVED", "APPLIED", "NEEDS_ATTENTION", "EXPIRED", "NOT_AVAILABLE"),
		list: listPaged(sellingApprovalsPath, upSeller, func(q staffQuery, v url.Values) {
			if q.Q != "" {
				v.Set("orgId", q.Q)
			}
		}),
		get:   getByPath(sellingApprovalsPath+"/", upSeller),
		names: []nameRef{orgRef("orgId")},
		cells: func(o jobj) map[string]any {
			days := "No expiry"
			if _, ok := o["daysToExpiry"].(float64); ok {
				d := int(num(o, "daysToExpiry"))
				switch {
				case d < 0:
					days = fmt.Sprintf("Expired %d days ago", -d)
				case d == 0:
					days = "Today"
				default:
					days = fmt.Sprintf("%d days", d)
				}
			}
			listings := "Live"
			if b, _ := o["listingsPaused"].(bool); b {
				listings = "Paused"
			}
			return map[string]any{"scopeTypeWords": scopeTypeLabel(jstr(o, "scopeType")), "daysWords": days, "pauseWords": listings}
		},
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "label"), jstr(o, "scopeKey")) + " · " + firstNonEmpty(jstr(o, "orgName"), "Seller")
		},
		subtitleOf: func(o jobj) string {
			return domain.Humanize(jstr(o, "status")) + " · " + scopeTypeLabel(jstr(o, "scopeType"))
		},
		sections: func(o jobj) []domain.DetailSection {
			f := []domain.DetailField{fieldOf("Seller", jstr(o, "orgName"), "text"), fieldOf("Approved for", jstr(o, "label"), "text"), fieldOf("Status", domain.Humanize(jstr(o, "status")), "text")}
			for _, p := range [][3]string{{"approvedAt", "Approved", "datetime"}, {"expiresAt", "Runs until (earliest document expiry)", "datetime"}, {"reapplyAfter", "May apply again from", "text"},
				{"reason", "Reason shown to the seller", "text"}, {"pauseReason", "Why listings are paused", "text"}} {
				if v := jstr(o, p[0]); v != "" {
					f = append(f, fieldOf(p[1], v, p[2]))
				}
			}
			out := []domain.DetailSection{{Title: "Approval", Fields: f}}
			docs := []domain.DetailField{}
			for _, d := range objs(o, "documents") {
				text := firstNonEmpty(jstr(d, "fileName"), "file")
				if v := jstr(d, "expiresOn"); v != "" {
					text += " · expires " + v
				}
				docs = append(docs, fieldOf(firstNonEmpty(jstr(d, "label"), docKindLabel(jstr(d, "kind"))), text, "text"))
			}
			if len(docs) > 0 {
				out = append(out, domain.DetailSection{Title: "Documents on file", Fields: docs})
			}
			return out
		},
		actions: []staffAction{
			{key: "pause", label: "Pause listings", tone: "danger", permission: "applications:approve", level: domain.LevelApprove, when: inState("APPROVED", "EXPIRED"),
				form: reasonedForm("Pause the listings in this scope", "Pause", "Use it for a performance problem. The seller's listings in this scope go to Paused (never deleted) and the seller is told why. They come back when you restore it.", false), run: act("pause")},
			{key: "restore", label: "Restore listings", tone: "primary", permission: "applications:approve", level: domain.LevelApprove,
				when: func(_ staffCtx, o jobj) string {
					if b, _ := o["paused"].(bool); b {
						return ""
					}
					return "The listings were not paused by staff."
				}, form: reasonedForm("Restore the listings", "Restore", "The seller's paused listings in this scope go live again.", false), run: act("restore")},
			{key: "revoke", label: "Withdraw (cannot apply)", tone: "danger", permission: "applications:approve", level: domain.LevelApprove,
				form: reasonedForm("Withdraw the approval", "Withdraw", "The seller loses this approval, their listings in the scope are paused, and they cannot apply again before the date you set. This cannot be appealed.", true), run: act("revoke")},
		},
	}
}

// ---------------------------------------------------------------- the rules

func sellingRulesResource() *staffResource {
	kindOptions := func(optional bool) []domain.FieldOption { return docKinds }
	selected := func(o jobj, optional bool) []string {
		out := []string{}
		for _, d := range objs(o, "documents") {
			if b, _ := d["optional"].(bool); b == optional {
				out = append(out, jstr(d, "kind"))
			}
		}
		return out
	}
	docsOf := func(v jobj) []jobj {
		docs := []jobj{}
		seen := map[string]bool{}
		for _, k := range vlist(v, "required") {
			seen[k] = true
			docs = append(docs, jobj{"kind": k, "optional": false})
		}
		for _, k := range vlist(v, "optionalDocs") {
			if !seen[k] {
				docs = append(docs, jobj{"kind": k, "optional": true})
			}
		}
		return docs
	}
	levelOptions := []domain.FieldOption{{Value: "SELL", Label: "Sell now (no approval)"}, {Value: "APPLY", Label: "Apply to sell (approval needed)"}, {Value: "NOT_AVAILABLE", Label: "Not available"}}
	body := func(v jobj, reason, actorName string) jobj {
		b := jobj{"level": vs(v, "level"), "label": vs(v, "label"), "documents": docsOf(v), "sellerReason": vs(v, "sellerReason"), "note": vs(v, "note"),
			"reapplyAfterDays": int(vn(v, "reapplyAfterDays")), "reason": reason, "actorName": actorName}
		if m := vs(v, "materials"); m != "" {
			b["materials"] = strings.FieldsFunc(m, func(r rune) bool { return r == ',' || r == '\n' })
		}
		b["watch"] = vs(v, "watch") == "YES"
		if a := vs(v, "active"); a != "" {
			b["active"] = a == "YES"
		}
		return b
	}
	yesNo := domain.StaffOptions("YES", "NO")
	return &staffResource{
		key: "selling-rules", module: "catalog", title: "Selling rules", statusKey: "level",
		searchPlaceholder: "Search a category, brand or rule",
		columns: []domain.Column{col("label", "Rule", "strong", 26), col("scopeTypeWords", "Applies to", "text", 16), col("levelWords", "Level", "text", 18), col("docsWords", "Documents", "text", 30),
			col("activeWords", "State", "text", 8), col("sourceWords", "Source", "text", 8), col("updatedAt", "Changed", "date", 10)},
		facets: []domain.Facet{{Value: "", Label: "All"}, {Value: "APPLY", Label: "Apply to sell"}, {Value: "NOT_AVAILABLE", Label: "Not available"}, {Value: "SELL", Label: "Open"}},
		list: listPaged(sellingRulesPath, upSeller, func(q staffQuery, v url.Values) {
			v.Del("status")
			if q.Status != "" {
				v.Set("level", q.Status)
			}
			if q.Q != "" {
				v.Set("text", q.Q)
			}
		}),
		get: getByPath(sellingRulesPath+"/", upSeller),
		cells: func(o jobj) map[string]any {
			docs := []string{}
			for _, d := range objs(o, "documents") {
				w := firstNonEmpty(jstr(d, "label"), docKindLabel(jstr(d, "kind")))
				if b, _ := d["optional"].(bool); b {
					w += " (optional)"
				}
				docs = append(docs, w)
			}
			active := "On"
			if b, _ := o["active"].(bool); !b {
				active = "Off"
			}
			if len(docs) == 0 {
				docs = append(docs, "None")
			}
			return map[string]any{"scopeTypeWords": scopeTypeLabel(jstr(o, "scopeType")), "levelWords": levelWords(jstr(o, "level")), "docsWords": strings.Join(docs, ", "),
				"activeWords": active, "sourceWords": map[string]string{"SEED": "Seeded", "STAFF": "Staff"}[jstr(o, "source")]}
		},
		titleOf: func(o jobj) string { return jstr(o, "label") },
		subtitleOf: func(o jobj) string {
			return scopeTypeLabel(jstr(o, "scopeType")) + " · " + levelWords(jstr(o, "level"))
		},
		version: func(o jobj) string { return strconv.Itoa(int(num(o, "version"))) },
		sections: func(o jobj) []domain.DetailSection {
			f := []domain.DetailField{fieldOf("Applies to", scopeTypeLabel(jstr(o, "scopeType")), "text"), fieldOf("Key", jstr(o, "scopeKey"), "mono"), fieldOf("Level", levelWords(jstr(o, "level")), "text")}
			if v := jstr(o, "approvalLevel"); v != "" {
				f = append(f, fieldOf("Listing rules level", domain.Humanize(v), "text"))
			}
			if v := jstr(o, "reason"); v != "" {
				f = append(f, fieldOf("Reason shown to sellers", v, "text"))
			}
			if v := jstr(o, "note"); v != "" {
				f = append(f, fieldOf("Note", v, "text"))
			}
			if v := jstr(o, "capability"); v != "" {
				f = append(f, fieldOf("Satisfied by the capability", domain.Humanize(v), "text"))
			}
			if m := strs(o, "materials"); len(m) > 0 {
				w := "Applies only when the material is " + strings.Join(m, ", ")
				if b, _ := o["watch"].(bool); b {
					w += ", or the product is a watch"
				}
				f = append(f, fieldOf("Condition", w, "text"))
			}
			f = append(f, fieldOf("Days before a disqualified seller may apply again (suggested)", strconv.Itoa(int(num(o, "reapplyAfterDays"))), "text"))
			docs := []domain.DetailField{}
			for _, d := range objs(o, "documents") {
				w := "Required"
				if b, _ := d["optional"].(bool); b {
					w = "Optional: or the seller says why not"
				}
				docs = append(docs, fieldOf(firstNonEmpty(jstr(d, "label"), docKindLabel(jstr(d, "kind"))), w, "text"))
			}
			if len(docs) == 0 {
				docs = append(docs, fieldOf("Documents", "None asked for", "text"))
			}
			return []domain.DetailSection{{Title: "Rule", Fields: f}, {Title: "Documents the seller uploads", Fields: docs}}
		},
		activity: func(ctx context.Context, sc staffCtx, o jobj) []domain.ActivityEntry {
			var rows []jobj
			out := []domain.ActivityEntry{}
			if sc.get(ctx, sc.up.Seller, sellingRulesPath+"/"+url.PathEscape(jstr(o, "id"))+"/audit", nil, &rows) != nil {
				return out
			}
			for _, a := range rows {
				out = append(out, domain.ActivityEntry{At: timeOf(a, "changedAt"), Actor: firstNonEmpty(jstr(a, "actorName"), "Unknown user"), Text: domain.Humanize(jstr(a, "action")) + ": " + jstr(a, "reason")})
			}
			return out
		},
		edit: &staffEdit{permission: "selling-rules:manage",
			form: func(o jobj) domain.Form {
				mat := strings.Join(strs(o, "materials"), ", ")
				w := "NO"
				if b, _ := o["watch"].(bool); b {
					w = "YES"
				}
				active := "YES"
				if b, _ := o["active"].(bool); !b {
					active = "NO"
				}
				fields := []domain.FormField{
					withValue(withMax(ff("label", "Name shown to sellers", "text", true), 200), jstr(o, "label")),
					withValue(withOptions(ff("level", "What sellers need", "select", true), levelOptions), jstr(o, "level")),
					withValue(withOptions(ff("required", "Documents required", "multiselect", false), kindOptions(false)), selected(o, false)),
					withValue(withOptions(ff("optionalDocs", "Documents optional (or the seller says why not)", "multiselect", false), kindOptions(true)), selected(o, true)),
					withValue(withMax(ff("sellerReason", "Reason shown to sellers (Not available)", "textarea", false), 500), jstr(o, "reason")),
					withValue(withMax(ff("note", "Note", "textarea", false), 500), jstr(o, "note")),
					withValue(numberField(ff("reapplyAfterDays", "Days before a disqualified seller may apply again (suggested)", "number", true), 1, 730), num(o, "reapplyAfterDays")),
					withValue(withOptions(ff("active", "Rule is on", "select", true), yesNo), active),
				}
				if jstr(o, "scopeType") == "CATEGORY" {
					m := withValue(withMax(ff("materials", "Only when the material is (comma separated; empty = always)", "text", false), 200), mat)
					fields = append(fields, m, withValue(withOptions(ff("watch", "Also when the product is a watch", "select", true), yesNo), w))
				}
				fields = append(fields, withMax(withMin(ff("reason", "Reason for this change (kept in the audit trail)", "textarea", true), 10), 500))
				return domain.Form{Title: "Edit " + jstr(o, "label"), SubmitLabel: "Save",
					Intro: "A change that makes a scope stricter pauses the listings of sellers who do not qualify (never deletes them); a relaxed rule brings them back. Sellers already approved keep their approval.", Fields: fields,
					ChangeKeys: []string{"label", "level", "required", "optionalDocs", "sellerReason", "note", "reapplyAfterDays", "active", "materials", "watch"}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, version string) error {
				b := body(v, vs(v, "reason"), firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email))
				b["scopeType"], b["key"] = jstr(o, "scopeType"), jstr(o, "scopeKey")
				if n, err := strconv.Atoi(version); err == nil {
					b["version"] = n
				}
				if jstr(o, "scopeType") != "CATEGORY" {
					delete(b, "materials")
					delete(b, "watch")
				}
				return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPut, Path: sellingRulesPath + "/" + url.PathEscape(jstr(o, "id")), Body: b}, nil)
			}},
		create: &staffCreate{permission: "selling-rules:manage",
			form: func(context.Context, staffCtx) domain.Form {
				scope := withOptions(ff("scopeType", "What the rule is about", "select", true), []domain.FieldOption{{Value: "BRAND", Label: "A brand (gated in every category)"},
					{Value: "BRAND_IN_CATEGORY", Label: "A brand inside one category"}, {Value: "CATEGORY", Label: "A category or department"}, {Value: "PRODUCT_TYPE", Label: "A product type"},
					{Value: "PRODUCT", Label: "One product (barcode)"}})
				scope.Value = "BRAND"
				cat := withMax(ff("categoryCode", "Category code (for a brand inside a category, e.g. phones)", "text", false), 120)
				cat.ShowWhen = &domain.FieldCondition{Key: "scopeType", In: []string{"BRAND_IN_CATEGORY"}}
				return domain.Form{Title: "New selling rule", SubmitLabel: "Create rule",
					Intro: "Typically a gated brand. Sellers then need approval (with the documents you choose) to list that brand. Listings already live from sellers who are not approved are paused, never deleted.",
					Fields: []domain.FormField{scope, withMax(ff("key", "Brand name, code or barcode", "text", true), 200), cat, withMax(ff("label", "Name shown to sellers (optional)", "text", false), 200),
						withValue(withOptions(ff("level", "What sellers need", "select", true), levelOptions), "APPLY"),
						withValue(withOptions(ff("required", "Documents required", "multiselect", false), kindOptions(false)), []string{"BRAND_AUTHORIZATION", "PURCHASE_INVOICE", "PHOTOS_ALL_SIDES"}),
						withOptions(ff("optionalDocs", "Documents optional", "multiselect", false), kindOptions(true)),
						withMax(ff("sellerReason", "Reason shown to sellers (Not available)", "textarea", false), 500), withMax(ff("note", "Note", "textarea", false), 500),
						withValue(numberField(ff("reapplyAfterDays", "Days before a disqualified seller may apply again (suggested)", "number", true), 1, 730), 30),
						withMax(withMin(ff("reason", "Reason (kept in the audit trail)", "textarea", true), 10), 500)}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				b := body(v, vs(v, "reason"), firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email))
				b["scopeType"], b["key"], b["categoryCode"] = vs(v, "scopeType"), vs(v, "key"), vs(v, "categoryCode")
				var out jobj
				if err := sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPost, Path: sellingRulesPath, Body: omitEmpty(b)}, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

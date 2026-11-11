package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Application review workflow (seller-service): who holds each application, for how long, the SLA and the escalation.
// Everyone who can view Applications sees all of it, even for a case outside their own purview; what they may do is the
// `can*` flags seller computes for the caller (permission, two-person rule), so the console never re-derives rules.

// Queue tabs: the status facet of the Applications table. "" and ALL are the same list.
const (
	queueMine, queueReview, queueApproval = "MY", "AWAITING_REVIEW", "AWAITING_APPROVAL"
	queueEscalated, queueReturned         = "ESCALATED", "RETURNED"
	queueAll, queueOverdue, queueOpen     = "ALL", "OVERDUE", "UNASSIGNED"
)

func applicationQueues() []domain.Facet {
	return []domain.Facet{{Value: queueMine, Label: "My queue"}, {Value: queueReview, Label: "Awaiting review"},
		{Value: queueApproval, Label: "Awaiting approval"}, {Value: queueEscalated, Label: "Escalated"},
		{Value: queueReturned, Label: "Returned to applicant"}, {Value: queueAll, Label: "All"},
		{Value: queueOverdue, Label: "Overdue"}, {Value: queueOpen, Label: "Unassigned"}}
}

// applicationQueueParams maps a tab to seller's queue filters.
func applicationQueueParams(status string, v url.Values) {
	v.Del("status")
	switch status {
	case "", queueAll:
		v.Set("queue", queueAll)
	case queueOverdue:
		v.Set("queue", queueAll)
		v.Set("overdue", "true")
	case queueOpen:
		v.Set("queue", queueAll)
		v.Set("unassigned", "true")
	default:
		v.Set("queue", status)
	}
}

// ago reads a wait: "12 min", "3 h", "2 days".
func ago(seconds float64) string {
	s := int64(seconds)
	switch {
	case s < 60:
		return "under a minute"
	case s < 3600:
		return fmt.Sprintf("%d min", s/60)
	case s < 48*3600:
		return fmt.Sprintf("%d h", s/3600)
	default:
		return fmt.Sprintf("%d days", s/86400)
	}
}

// wf is the application's workflow document ({} when seller sent none).
func wf(o jobj) jobj {
	if m, ok := o["workflow"].(map[string]any); ok {
		return m
	}
	return jobj{}
}

func flagReason(flag, why string) func(staffCtx, jobj) string {
	return func(_ staffCtx, o jobj) string {
		if b, _ := wf(o)["can"+flag].(bool); b {
			return ""
		}
		return why
	}
}

// stageLine is "Awaiting review · with Amina Bello · since 2 days" (or the status when nobody on staff holds it).
func stageLine(o jobj) string {
	w := wf(o)
	stage := jstr(w, "stage")
	if stage == "" {
		return domain.Humanize(jstr(o, "status"))
	}
	label := map[string]string{"REVIEW": "Awaiting review", "APPROVAL": "Awaiting approval"}[stage]
	holder := jstr(w, "holder")
	if holder == "" {
		holder = "any " + map[string]string{"REVIEW": "reviewer", "APPROVAL": "approver"}[stage]
	}
	return label + " · " + holder + " · since " + ago(num(w, "waitingSeconds"))
}

// slaWord is the SLA state as a word.
func slaWord(w jobj) string {
	switch jstr(w, "slaState") {
	case "ON_TIME":
		return "On time"
	case "DUE_SOON":
		return "Due soon"
	case "OVERDUE":
		return "Overdue"
	}
	return ""
}

// workflowCells are the With / Waiting / SLA / Escalation columns of a row.
func workflowCells(o jobj) map[string]any {
	w := wf(o)
	out := map[string]any{"with": nil, "waiting": nil, "sla": nil, "escalation": nil}
	switch {
	case jstr(w, "stage") != "":
		holder := jstr(w, "assigneeName")
		if holder == "" {
			holder = "Any " + map[string]string{"REVIEW": "reviewer", "APPROVAL": "approver"}[jstr(w, "stage")]
		}
		out["with"] = holder
		out["waiting"] = num(w, "waitingSeconds")
		out["sla"] = slaWord(w)
		if lvl := int(num(w, "escalationLevel")); lvl > 0 {
			out["escalation"] = fmt.Sprintf("Level %d", lvl)
		}
	case strings.EqualFold(jstr(o, "status"), "INFO_REQUESTED"):
		out["with"] = "The applicant"
	}
	return out
}

// workflowSections is "Who has it", the escalation and the send-backs, led by the stage line.
func workflowSections(o jobj) []domain.DetailSection {
	w := wf(o)
	if len(w) == 0 {
		return nil
	}
	who := []domain.DetailField{fieldOf("Where it is", stageLine(o), "text")}
	if jstr(w, "stage") != "" {
		who = append(who, fieldOf("With", firstNonEmpty(jstr(w, "assigneeName"), "Nobody yet: "+jstr(w, "holder")), "text"))
		if jstr(w, "assigneeName") != "" {
			who = append(who, fieldOf("Assigned by", jstr(w, "assignedByName"), "text"), fieldOf("Assigned", jstr(w, "assignedAt"), "datetime"))
		}
		who = append(who, fieldOf("At this stage since", jstr(w, "stageEnteredAt"), "datetime"), fieldOf("Waiting", ago(num(w, "waitingSeconds")), "text"),
			fieldOf("Service level", slaWord(w)+" (due "+shortWhen(jstr(w, "slaDueAt"))+")", "text"))
	}
	out := []domain.DetailSection{{Title: "Who has it", Fields: who}}
	if lvl := int(num(w, "escalationLevel")); lvl > 0 {
		out = append(out, domain.DetailSection{Title: "Escalation", Fields: []domain.DetailField{
			fieldOf("Level", fmt.Sprintf("%d", lvl), "text"), fieldOf("Escalated", jstr(w, "escalatedAt"), "datetime"),
			fieldOf("Sent to", strings.Join(strs(w, "escalatedTo"), ", "), "text"), fieldOf("Why", jstr(w, "escalationReason"), "text")}})
	}
	if n := int(num(w, "returnCount")); n > 0 {
		out = append(out, domain.DetailSection{Title: "Sent back by approvers", Fields: []domain.DetailField{fieldOf("Times sent back to review", fmt.Sprintf("%d", n), "text")}})
	}
	return out
}

func shortWhen(iso string) string {
	if len(iso) >= 16 {
		return strings.Replace(iso[:16], "T", " ", 1) + " UTC"
	}
	return iso
}

// returnedBanner is the "returned by approver" notice at the top of a case that is back in the review queue.
func returnedBanner(o jobj) *domain.RecordBanner {
	w := wf(o)
	if jstr(w, "returnedByName") == "" || jstr(w, "stage") != "REVIEW" {
		return nil
	}
	text := "Sent back by " + jstr(w, "returnedByName") + " on " + shortWhen(jstr(w, "returnedAt"))
	if note := jstr(w, "returnNote"); note != "" {
		text += ": " + note
	}
	if n := int(num(w, "returnCount")); n > 1 {
		text += fmt.Sprintf(" (sent back %d times)", n)
	}
	return &domain.RecordBanner{Tone: "warn", Title: "Returned by approver", Text: text}
}

// timelineActivity lists the case's history with people's names (never ids).
func timelineActivity(o jobj) []domain.ActivityEntry {
	rows := objs(o, "timeline")
	out := make([]domain.ActivityEntry, 0, len(rows))
	for i := len(rows) - 1; i >= 0; i-- {
		t := rows[i]
		actor := firstNonEmpty(jstr(t, "actorName"), "The applicant")
		if jstr(t, "actorId") == "system" {
			actor = firstNonEmpty(jstr(t, "actorName"), "System")
		}
		text := domain.Humanize(jstr(t, "action"))
		if n := jstr(t, "note"); n != "" {
			text += ": " + n
		}
		out = append(out, domain.ActivityEntry{At: timeOf(t, "occurredAt"), Actor: actor, Text: text})
	}
	return out
}

// nameFromTimeline fills reviewerName / finalApproverName from the names written on the timeline when the decision was
// made, so a person identity cannot look up for this caller is still shown by name.
func nameFromTimeline(o jobj) {
	rows := objs(o, "timeline")
	for _, ref := range []struct{ id, name string }{{"reviewerId", "reviewerName"}, {"finalApproverId", "finalApproverName"}} {
		id := jstr(o, ref.id)
		if id == "" || jstr(o, ref.name) != "" {
			continue
		}
		for i := len(rows) - 1; i >= 0; i-- {
			if jstr(rows[i], "actorId") == id && jstr(rows[i], "actorName") != "" {
				o[ref.name] = jstr(rows[i], "actorName")
				break
			}
		}
	}
}

// assignmentRun posts one assignment action to seller; the console's Idempotency-Key is relayed.
func assignmentRun(action string) func(context.Context, staffCtx, jobj, jobj) error {
	return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
		body := jobj{"action": action, "assigneeId": vs(v, "assigneeId"), "note": vs(v, "note"), "reason": vs(v, "reason"),
			"actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)}
		return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPost, Path: "/v1/applications/" + url.PathEscape(jstr(o, "id")) + "/assignment",
			Body: omitEmpty(body), IdempotencyKey: sc.idem}, nil)
	}
}

func applicationAssignmentActions() []staffAction {
	noteForm := func(title, submit, intro string) func(context.Context, staffCtx, jobj) *domain.Form {
		return func(context.Context, staffCtx, jobj) *domain.Form {
			return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: []domain.FormField{withMax(ff("note", "Note (internal)", "textarea", false), 500)}}
		}
	}
	assignForm := func(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
		var people []jobj
		opts := []domain.FieldOption{}
		if sc.get(ctx, sc.up.Seller, "/v1/applications/"+url.PathEscape(jstr(o, "id"))+"/assignment/candidates", nil, &people) == nil {
			for _, p := range people {
				opts = append(opts, domain.FieldOption{Value: jstr(p, "id"), Label: firstNonEmpty(jstr(p, "name"), "Unknown user")})
			}
		}
		pick := withOptions(ff("assigneeId", "Assign to", "select", true), opts)
		pick.Help = "Only people who can hold this case at its stage are listed: they hold the permission, are not the applicant and, for approval, did not do the first review."
		return &domain.Form{Title: "Assign or take over", SubmitLabel: "Assign",
			Intro:  "Gives the case to one person. The person is told, and the change is recorded with their name.",
			Fields: []domain.FormField{pick, withMax(ff("note", "Note (internal)", "textarea", false), 500)}}
	}
	escalateForm := func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: "Escalate now", SubmitLabel: "Escalate",
			Intro:  "Raises the escalation one level now: the people set up for that level are told and can take over or reassign. The reason is kept on the timeline.",
			Fields: []domain.FormField{withMax(ff("reason", "Why are you escalating?", "textarea", true), 500)}}
	}
	return []staffAction{
		{key: "claim", label: "Claim", tone: "primary", permission: "applications:review", also: []string{"applications:approve"}, when: flagReason("Claim", "You can't take this case: it is with someone, or you may not hold it."), run: assignmentRun("CLAIM")},
		{key: "assign", label: "Assign…", tone: "neutral", permission: "applications:assign", also: []string{"applications:approve", "applications:review"}, form: assignForm, when: flagReason("Assign", "Only a supervisor, or the person an escalation went to, can assign."), run: assignmentRun("ASSIGN")},
		{key: "release", label: "Release to the pool", tone: "neutral", permission: "applications:review", also: []string{"applications:approve", "applications:assign"}, form: noteForm("Release to the pool", "Release", "Anyone who can hold this case will be able to claim it."), when: flagReason("Release", "Nobody holds this case, or it is not yours to release."), run: assignmentRun("RELEASE")},
		{key: "escalate", label: "Escalate now", tone: "danger", permission: "applications:review", also: []string{"applications:approve", "applications:assign"}, form: escalateForm, when: flagReason("Escalate", "Only the person who has the case or a supervisor can escalate, and it is not at the top level yet."), run: assignmentRun("ESCALATE")},
	}
}

// ---------------------------------------------------------------- settings: SLAs and escalation

const reviewSettingsPath = "/v1/application-review-settings"

func applicationReviewSettingsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Seller, reviewSettingsPath, nil, &o); err != nil {
			return nil, err
		}
		o["id"] = "current"
		o["name"] = "Application review"
		return o, nil
	}
	chainText := func(o jobj, chain, people string, level int) string {
		l := fmt.Sprintf("level%d", level)
		var parts []string
		if role := jstr(o, chain+"."+l+".roleKey"); role != "" {
			parts = append(parts, "everyone with the role "+domain.Humanize(strings.TrimPrefix(role, "platform.")))
		}
		parts = append(parts, strs(o, fmt.Sprintf("%s%dPeople", people, level))...)
		if len(parts) == 0 {
			return "Nobody set"
		}
		return strings.Join(parts, ", ")
	}
	mode := map[string]string{"NONE": "Off: cases wait in the pool", "ROUND_ROBIN": "Round robin", "LEAST_LOADED": "Least loaded"}
	yes := func(b bool) string { return map[bool]string{true: "Yes", false: "No"}[b] }
	return &staffResource{
		key: "application-review-settings", module: "apps", title: "Application review: SLAs and escalation",
		columns: []domain.Column{col("name", "Settings", "strong", 24), col("reviewSla", "Review SLA", "text", 14), col("approvalSla", "Approval SLA", "text", 14),
			col("autoAssign", "Auto-assignment", "text", 22), col("updatedByName", "Last changed by", "text", 20), col("updatedAt", "Changed", "datetime", 14)},
		idOf: func(jobj) string { return "current" },
		cells: func(o jobj) map[string]any {
			return map[string]any{"reviewSla": displayValue(num(o, "reviewSlaHours")) + " h", "approvalSla": displayValue(num(o, "approvalSlaHours")) + " h",
				"autoAssign": mode[jstr(o, "autoAssign")]}
		},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := load(ctx, sc)
			if err != nil {
				return staffList{}, err
			}
			n := int64(1)
			return staffList{items: []jobj{o}, total: &n}, nil
		},
		get:     func(ctx context.Context, sc staffCtx, _ string) (jobj, error) { return load(ctx, sc) },
		titleOf: func(jobj) string { return "Application review: SLAs and escalation" },
		subtitleOf: func(jobj) string {
			return "How long a case may wait, who is told when it is late, and how cases are handed out"
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "Service levels", Fields: []domain.DetailField{
					fieldOf("Review (step 1) must be done within", displayValue(num(o, "reviewSlaHours"))+" hours", "text"),
					fieldOf("Approval (step 2) must be done within", displayValue(num(o, "approvalSlaHours"))+" hours", "text"),
					fieldOf("Due soon after", displayValue(num(o, "dueSoonPercent"))+"% of the time", "text"),
					fieldOf("Escalate a case nobody has taken after", displayValue(num(o, "unassignedAfterHours"))+" hours", "text"),
					fieldOf("Level 2 after the service level plus", displayValue(num(o, "level2AfterHours"))+" hours", "text")}},
				{Title: "Escalation: review", Fields: []domain.DetailField{
					fieldOf("Level 1", chainText(o, "reviewChain", "reviewLevel", 1), "text"), fieldOf("Level 2", chainText(o, "reviewChain", "reviewLevel", 2), "text")}},
				{Title: "Escalation: approval", Fields: []domain.DetailField{
					fieldOf("Level 1", chainText(o, "approvalChain", "approvalLevel", 1), "text"), fieldOf("Level 2", chainText(o, "approvalChain", "approvalLevel", 2), "text")}},
				{Title: "Handing out cases", Fields: []domain.DetailField{
					fieldOf("Auto-assignment", mode[jstr(o, "autoAssign")], "text"),
					fieldOf("Only the assignee may decide an assigned case", yes(at(o, "enforceAssignee") == true), "text"),
					fieldOf("A case sent back goes to the original reviewer", yes(at(o, "sendBackToReviewer") == true), "text")}},
			}
		},
		activity: func(_ context.Context, _ staffCtx, o jobj) []domain.ActivityEntry {
			out := []domain.ActivityEntry{}
			for _, c := range objs(o, "changes") {
				out = append(out, domain.ActivityEntry{At: timeOf(c, "changedAt"), Actor: firstNonEmpty(jstr(c, "actorName"), "Unknown user"), Text: "Changed: " + jstr(c, "reason")})
			}
			return out
		},
		edit: &staffEdit{permission: "applications:assign", form: reviewSettingsForm, save: saveReviewSettings},
	}
}

func hoursField(key, label, help string) domain.FormField {
	f := numberField(ff(key, label, "number", true), 1, 720)
	f.Help = help
	return f
}

func reviewSettingsForm(o jobj) domain.Form {
	yesNo := domain.StaffOptions("YES", "NO")
	flag := func(b any) string { return map[bool]string{true: "YES", false: "NO"}[b == true] }
	levelFields := func(prefix, chain string) []domain.FormField {
		fs := []domain.FormField{}
		for _, lvl := range []int{1, 2} {
			role := withValue(ff(fmt.Sprintf("%sLevel%dRole", prefix, lvl), fmt.Sprintf("%s: level %d role (everyone holding it)", domain.Humanize(prefix), lvl), "text", false), at(o, fmt.Sprintf("%s.level%d.roleKey", chain, lvl)))
			role.Help = "A role key such as platform.application_supervisor. Leave empty to name people only."
			person := withLookup(ff(fmt.Sprintf("%sLevel%dPerson", prefix, lvl), fmt.Sprintf("%s: level %d person", domain.Humanize(prefix), lvl), "text", false), lookupStaff)
			if ids, ok := at(o, fmt.Sprintf("%s.level%d.userIds", chain, lvl)).([]any); ok && len(ids) > 0 {
				person = withValue(person, ids[0])
			}
			fs = append(fs, role, person)
		}
		return fs
	}
	fields := []domain.FormField{
		hoursField("reviewSlaHours", "Review (step 1) must be done within, hours", "1 to 720."),
		hoursField("approvalSlaHours", "Approval (step 2) must be done within, hours", "1 to 720."),
		withValue(numberField(ff("dueSoonPercent", "Due soon after this percent of the time", "number", true), 1, 99), at(o, "dueSoonPercent")),
		hoursField("unassignedAfterHours", "Escalate a case nobody has taken after, hours", "Counted from when it reached its stage."),
		hoursField("level2AfterHours", "Level 2 this many hours after the service level ends", ""),
		withValue(withOptions(ff("autoAssign", "Auto-assignment", "select", true), []domain.FieldOption{{Value: "NONE", Label: "Off: cases wait in the pool"}, {Value: "ROUND_ROBIN", Label: "Round robin"}, {Value: "LEAST_LOADED", Label: "Least loaded"}}), at(o, "autoAssign")),
		withValue(withOptions(ff("enforceAssignee", "Only the assignee may decide an assigned case", "select", true), yesNo), flag(at(o, "enforceAssignee"))),
		withValue(withOptions(ff("sendBackToReviewer", "A case sent back goes to the original reviewer", "select", true), yesNo), flag(at(o, "sendBackToReviewer"))),
	}
	for _, k := range []string{"reviewSlaHours", "approvalSlaHours", "unassignedAfterHours", "level2AfterHours"} {
		for i := range fields {
			if fields[i].Key == k {
				fields[i].Value = at(o, k)
			}
		}
	}
	fields = append(fields, levelFields("review", "reviewChain")...)
	fields = append(fields, levelFields("approval", "approvalChain")...)
	fields = append(fields, reasonField())
	keys := []string{"reviewSlaHours", "approvalSlaHours", "dueSoonPercent", "unassignedAfterHours", "level2AfterHours", "autoAssign", "enforceAssignee", "sendBackToReviewer",
		"reviewLevel1Role", "reviewLevel1Person", "reviewLevel2Role", "reviewLevel2Person", "approvalLevel1Role", "approvalLevel1Person", "approvalLevel2Role", "approvalLevel2Person"}
	return domain.Form{Title: "Application review: SLAs and escalation", SubmitLabel: "Save",
		Intro:  "Levels are in order: set level 1 before level 2. Every role and person must exist and be active. The change is recorded with your reason and what it was before.",
		Fields: fields, ChangeKeys: keys}
}

func saveReviewSettings(ctx context.Context, sc staffCtx, _ jobj, v jobj, _ string) error {
	target := func(prefix string, lvl int) jobj {
		t := jobj{}
		if role := strings.TrimSpace(vs(v, fmt.Sprintf("%sLevel%dRole", prefix, lvl))); role != "" {
			t["roleKey"] = role
		}
		if p := strings.TrimSpace(vs(v, fmt.Sprintf("%sLevel%dPerson", prefix, lvl))); p != "" {
			t["userIds"] = []string{p}
		}
		return t
	}
	chain := func(prefix string) jobj { return jobj{"level1": target(prefix, 1), "level2": target(prefix, 2)} }
	body := jobj{"reviewSlaHours": int(vn(v, "reviewSlaHours")), "approvalSlaHours": int(vn(v, "approvalSlaHours")), "dueSoonPercent": int(vn(v, "dueSoonPercent")),
		"unassignedAfterHours": int(vn(v, "unassignedAfterHours")), "level2AfterHours": int(vn(v, "level2AfterHours")), "autoAssign": vs(v, "autoAssign"),
		"enforceAssignee": vs(v, "enforceAssignee") == "YES", "sendBackToReviewer": vs(v, "sendBackToReviewer") == "YES",
		"reviewChain": chain("review"), "approvalChain": chain("approval"), "reason": vs(v, "reason"),
		"actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)}
	return sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPut, Path: reviewSettingsPath, Body: body}, nil)
}

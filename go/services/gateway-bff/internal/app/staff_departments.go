package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffDepartmentsService is Department requests in the staff console (contracts/openapi/gateway-bff-staff.yaml, tag
// Department requests) over the seller service: the queue of sellers' requests to sell in another catalog department,
// the decision on one (approve with an optional note, reject with a reason the seller reads) and an organisation's
// departments, which staff can replace with a reason. The seller service owns the rules and the audit: it records who
// decided and publishes seller.department_request.decided / seller.org.departments_changed, which trust-ops copies into
// the audit log. The BFF checks the permission (applications:review reads, applications:approve decides), joins the
// names (organisation, department, people) and filters, sorts and pages the queue (the service lists at most 200 per
// status).
type StaffDepartmentsService interface {
	List(ctx context.Context, c *domain.Caller, q StaffDepartmentQuery) (domain.StaffDepartmentRequestPage, error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.StaffDepartmentRequest, error)
	Approve(ctx context.Context, c *domain.Caller, id, note string) (domain.StaffDepartmentRequest, error)
	Reject(ctx context.Context, c *domain.Caller, id, reason string) (domain.StaffDepartmentRequest, error)
	OrgDepartments(ctx context.Context, c *domain.Caller, orgID string) (domain.StaffOrgDepartments, error)
	SetOrgDepartments(ctx context.Context, c *domain.Caller, orgID string, departments []string, reason string) (domain.StaffOrgDepartments, error)
}

// StaffDepartmentQuery is a table request: the status (PENDING, DECIDED, APPROVED or REJECTED; default PENDING), a
// filter on the department name and on the organisation name, the days the request was made (YYYY-MM-DD, inclusive),
// the sort (`<column>,asc|desc`) and the page (1-based).
type StaffDepartmentQuery struct {
	Status, Department, Organisation, From, To string
	Sort                                       []string
	Page, Size                                 int
}

type staffDepartmentsImpl struct{ r *staffResolver }

var _ StaffDepartmentsService = (*staffDepartmentsImpl)(nil)

// NewStaffDepartmentsService returns the Department requests use case.
func NewStaffDepartmentsService(d StaffDeps) StaffDepartmentsService {
	return &staffDepartmentsImpl{r: newStaffResolver(d)}
}

const (
	deptQueueLimit    = 200 // what the seller service lists per status
	deptMaxFilter     = 120
	deptMaxPage       = 100
	deptMinReject     = 3
	deptMinOverride   = 10
	deptMaxReason     = 1000
	deptNoteMax       = 500
	deptDefaultSize   = 25
	deptSortRequested = "requestedAt"
)

var (
	deptDateRe     = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	deptSortFields = []string{"organisation", "department", deptSortRequested, "status", "decidedAt"}
	// deptPromptLabels are the plain-language names of the evidence a department asks for.
	deptPromptLabels = map[string]string{"FOOD_PERMIT": "Food permit on file", "BEAUTY_LABELLING": "Beauty product labelling confirmed"}
)

// access resolves the staff member for the department screens: the module level (derived from the permissions) and,
// for a change, applications:approve.
func (s *staffDepartmentsImpl) access(ctx context.Context, c *domain.Caller, change bool) (staffCtx, error) {
	level := domain.LevelView
	if change {
		level = domain.LevelEdit
	}
	sc, err := s.r.require(ctx, c, domain.DepartmentRequestsModule, level)
	if err != nil {
		return sc, err
	}
	if change && !sc.access.Can(domain.PermApplicationsApprove) {
		return sc, needsPermission(domain.PermApplicationsApprove)
	}
	if sc.up.Seller == nil {
		return sc, domain.UpstreamUnavailable("seller")
	}
	return sc, nil
}

// orgAccess is the same for the Departments section of an organisation (reads need either permission).
func (s *staffDepartmentsImpl) orgAccess(ctx context.Context, c *domain.Caller, change bool) (staffCtx, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return sc, err
	}
	switch {
	case change && !sc.access.Can(domain.PermApplicationsApprove):
		return sc, needsPermission(domain.PermApplicationsApprove)
	case !change && !sc.access.CanAny(domain.PermApplicationsReview, domain.PermApplicationsApprove):
		return sc, needsPermission(domain.PermApplicationsReview)
	case sc.up.Seller == nil:
		return sc, domain.UpstreamUnavailable("seller")
	}
	return sc, nil
}

// catalogDepartments reads the catalog's departments (seller-service proxies the catalog tree), sorted by name.
func catalogDepartments(ctx context.Context, sc staffCtx) ([]domain.StaffDepartment, error) {
	var raw []jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/departments", nil, &raw); err != nil {
		return nil, err
	}
	out := make([]domain.StaffDepartment, 0, len(raw))
	for _, d := range raw {
		out = append(out, domain.StaffDepartment{Code: jstr(d, "code"), Name: firstNonEmpty(jstr(d, "name"), domain.Humanize(jstr(d, "code"))),
			Requires: sortedStrings(strs(d, "requires"))})
	}
	slices.SortFunc(out, func(a, b domain.StaffDepartment) int {
		return strings.Compare(strings.ToLower(a.Name), strings.ToLower(b.Name))
	})
	return out, nil
}

// departmentNames maps a department code to what staff read; a code the catalog no longer has reads as its words.
type departmentNames map[string]domain.StaffDepartment

func newDepartmentNames(list []domain.StaffDepartment) departmentNames {
	m := make(departmentNames, len(list))
	for _, d := range list {
		m[d.Code] = d
	}
	return m
}

func (n departmentNames) of(code string) domain.StaffDepartment {
	if d, ok := n[code]; ok {
		return d
	}
	return domain.StaffDepartment{Code: code, Name: domain.Humanize(code), Requires: []string{}}
}

func parseDay(name, raw string, errs map[string]string) time.Time {
	if raw == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.DateOnly, raw)
	if err != nil || !deptDateRe.MatchString(raw) {
		errs[name] = "Use a date like 2026-10-31."
	}
	return t
}

// sortOf reads `<column>,asc|desc`; the default is the oldest open request first and the newest decision first.
func deptSortOf(sorts []string, status string) (string, bool, error) {
	col, desc := deptSortRequested, false
	if status != domain.DeptRequestPending {
		col, desc = "decidedAt", true
	}
	if len(sorts) == 0 {
		return col, desc, nil
	}
	name, dir, _ := strings.Cut(sorts[0], ",")
	if !slices.Contains(deptSortFields, name) {
		return "", false, domain.ValidationProblem(map[string]string{"sort": "Sort by organisation, department, requested date, status or decided date."})
	}
	return name, strings.EqualFold(dir, "desc"), nil
}

func (s *staffDepartmentsImpl) List(ctx context.Context, c *domain.Caller, q StaffDepartmentQuery) (domain.StaffDepartmentRequestPage, error) {
	sc, err := s.access(ctx, c, false)
	if err != nil {
		return domain.StaffDepartmentRequestPage{}, err
	}
	errs := map[string]string{}
	status := strings.ToUpper(strings.TrimSpace(q.Status))
	switch status {
	case "":
		status = domain.DeptRequestPending
	case domain.DeptRequestPending, domain.DeptRequestApproved, domain.DeptRequestRejected, domain.DeptRequestDecided:
	default:
		errs["status"] = "Choose pending, approved, rejected or decided."
	}
	if len(q.Department) > deptMaxFilter {
		errs["department"] = "Too long"
	}
	if len(q.Organisation) > deptMaxFilter {
		errs["organisation"] = "Too long"
	}
	from, to := parseDay("from", q.From, errs), parseDay("to", q.To, errs)
	if len(errs) == 0 && !from.IsZero() && !to.IsZero() && from.After(to) {
		errs["from"] = "The first day is after the last day."
	}
	page, size := q.Page, q.Size
	if page == 0 {
		page = 1
	}
	if size == 0 {
		size = deptDefaultSize
	}
	if page < 1 || page > 10_000 || size < 1 || size > deptMaxPage {
		errs["page"] = "Out of range"
	}
	if len(errs) > 0 {
		return domain.StaffDepartmentRequestPage{}, domain.ValidationProblem(errs)
	}
	sortCol, desc, err := deptSortOf(q.Sort, status)
	if err != nil {
		return domain.StaffDepartmentRequestPage{}, err
	}

	statuses := []string{status}
	if status == domain.DeptRequestDecided {
		statuses = []string{domain.DeptRequestApproved, domain.DeptRequestRejected}
	}
	var all []jobj
	truncated := false
	pending := 0
	for _, st := range statuses {
		var raw []jobj
		if err := sc.get(ctx, sc.up.Seller, "/v1/department-requests", url.Values{"status": {st}}, &raw); err != nil {
			return domain.StaffDepartmentRequestPage{}, err
		}
		truncated = truncated || len(raw) >= deptQueueLimit
		if st == domain.DeptRequestPending {
			pending = len(raw)
		}
		all = append(all, raw...)
	}
	if status != domain.DeptRequestPending {
		// The tab badge counts the open queue whatever the tab shows.
		var raw []jobj
		if err := sc.get(ctx, sc.up.Seller, "/v1/department-requests", url.Values{"status": {domain.DeptRequestPending}}, &raw); err == nil {
			pending = len(raw)
		}
	}
	catalog, err := catalogDepartments(ctx, sc)
	if err != nil {
		// Without the catalog a department reads as its words; the queue is still useful.
		catalog = nil
	}
	depts := newDepartmentNames(catalog)
	orgIDs := make([]string, 0, len(all))
	for _, r := range all {
		orgIDs = append(orgIDs, jstr(r, "orgId"))
	}
	orgNames := sc.names.labels(ctx, sc, orgRef("orgId"), orgIDs...)

	type row struct {
		raw       jobj
		org, dept string
		at        time.Time
	}
	needle := func(s string) string { return strings.ToLower(strings.TrimSpace(s)) }
	wantDept, wantOrg := needle(q.Department), needle(q.Organisation)
	rows := make([]row, 0, len(all))
	for _, r := range all {
		x := row{raw: r, org: orgNames[jstr(r, "orgId")], dept: depts.of(jstr(r, "departmentCode")).Name, at: timeOf(r, "createdAt")}
		day := x.at.UTC().Truncate(24 * time.Hour)
		switch {
		case wantDept != "" && !strings.Contains(strings.ToLower(x.dept), wantDept):
		case wantOrg != "" && !strings.Contains(strings.ToLower(x.org), wantOrg):
		case !from.IsZero() && day.Before(from):
		case !to.IsZero() && day.After(to):
		default:
			rows = append(rows, x)
		}
	}
	key := func(x row) string {
		switch sortCol {
		case "organisation":
			return strings.ToLower(x.org)
		case "department":
			return strings.ToLower(x.dept)
		case "status":
			return jstr(x.raw, "status")
		case "decidedAt":
			return jstr(x.raw, "decidedAt")
		default:
			return x.at.UTC().Format(time.RFC3339Nano)
		}
	}
	slices.SortStableFunc(rows, func(a, b row) int {
		ka, kb := key(a), key(b)
		// Empty keys (a request nobody decided) sort last in both directions.
		switch {
		case ka == kb:
			return 0
		case ka == "":
			return 1
		case kb == "":
			return -1
		case desc:
			return strings.Compare(kb, ka)
		default:
			return strings.Compare(ka, kb)
		}
	})
	start := min((page-1)*size, len(rows))
	shown := rows[start:min(start+size, len(rows))]

	var people []string
	for _, x := range shown {
		people = append(people, jstr(x.raw, "requestedBy"), jstr(x.raw, "decidedBy"))
	}
	who := sc.names.labels(ctx, sc, personRef("requestedBy", "Requested by"), people...)
	out := domain.StaffDepartmentRequestPage{Items: make([]domain.StaffDepartmentRequestRow, 0, len(shown)), Total: len(rows), Page: page, Size: size,
		Pending: pending, Truncated: truncated, CanDecide: sc.access.Can(domain.PermApplicationsApprove)}
	for _, x := range shown {
		out.Items = append(out.Items, requestRow(x.raw, x.org, x.dept, who))
	}
	return out, nil
}

// requestRow is one table row from the service's request.
func requestRow(r jobj, org, dept string, who map[string]string) domain.StaffDepartmentRequestRow {
	row := domain.StaffDepartmentRequestRow{ID: jstr(r, "id"), OrgID: jstr(r, "orgId"), OrgName: org, DepartmentName: dept,
		RequestedByName: firstNonEmpty(who[jstr(r, "requestedBy")], "Unknown user"), RequestedAt: timeOf(r, "createdAt"), Status: jstr(r, "status")}
	if by := jstr(r, "decidedBy"); by != "" {
		name := firstNonEmpty(who[by], "Unknown user")
		row.DecidedByName = &name
	}
	if at := timeOf(r, "decidedAt"); !at.IsZero() {
		row.DecidedAt = &at
	}
	return row
}

func (s *staffDepartmentsImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.StaffDepartmentRequest, error) {
	sc, err := s.access(ctx, c, false)
	if err != nil {
		return domain.StaffDepartmentRequest{}, err
	}
	return s.detail(ctx, sc, id)
}

// detail reads one request with the organisation's departments and the department's compliance prompts.
func (s *staffDepartmentsImpl) detail(ctx context.Context, sc staffCtx, id string) (domain.StaffDepartmentRequest, error) {
	var r jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/department-requests/"+url.PathEscape(id), nil, &r); err != nil {
		return domain.StaffDepartmentRequest{}, err
	}
	orgID := jstr(r, "orgId")
	var scope jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/"+url.PathEscape(orgID)+"/catalog-scope", nil, &scope); err != nil {
		return domain.StaffDepartmentRequest{}, err
	}
	catalog, err := catalogDepartments(ctx, sc)
	if err != nil {
		catalog = nil
	}
	depts := newDepartmentNames(catalog)
	dept := depts.of(jstr(r, "departmentCode"))
	who := sc.names.labels(ctx, sc, personRef("requestedBy", "Requested by"), jstr(r, "requestedBy"), jstr(r, "decidedBy"))
	org := sc.names.name(ctx, sc, orgRef("orgId"), orgID)
	out := domain.StaffDepartmentRequest{StaffDepartmentRequestRow: requestRow(r, org, dept.Name, who),
		Prompts: []domain.StaffCompliancePrompt{}, CurrentDepartments: []domain.StaffDepartment{},
		Exempt: boolean(scope, "exempt"), CanDecide: sc.access.Can(domain.PermApplicationsApprove)}
	if note := jstr(r, "note"); note != "" {
		out.Note = &note
	}
	if note := jstr(r, "decisionNote"); note != "" {
		out.DecisionNote = &note
	}
	needs := strs(r, "needs")
	for _, req := range dept.Requires {
		p := domain.StaffCompliancePrompt{Requirement: req, Label: firstNonEmpty(deptPromptLabels[req], domain.Humanize(req))}
		if jstr(r, "status") != domain.DeptRequestRejected {
			ok := !slices.Contains(needs, req)
			p.Satisfied = &ok
		}
		out.Prompts = append(out.Prompts, p)
	}
	for _, code := range strs(scope, "departments") {
		out.CurrentDepartments = append(out.CurrentDepartments, depts.of(code))
	}
	sortByName(out.CurrentDepartments)
	return out, nil
}

// sortedStrings is a sorted copy that is never nil (it is a JSON array, not null).
func sortedStrings(in []string) []string {
	out := slices.Clone(in)
	if out == nil {
		out = []string{}
	}
	slices.Sort(out)
	return out
}

func sortByName(list []domain.StaffDepartment) {
	slices.SortFunc(list, func(a, b domain.StaffDepartment) int {
		return strings.Compare(strings.ToLower(a.Name), strings.ToLower(b.Name))
	})
}

func (s *staffDepartmentsImpl) Approve(ctx context.Context, c *domain.Caller, id, note string) (domain.StaffDepartmentRequest, error) {
	sc, err := s.access(ctx, c, true)
	if err != nil {
		return domain.StaffDepartmentRequest{}, err
	}
	note = strings.TrimSpace(note)
	if len([]rune(note)) > deptNoteMax {
		return domain.StaffDepartmentRequest{}, domain.ValidationProblem(map[string]string{"note": "Keep the note under 500 characters."})
	}
	return s.decide(ctx, sc, id, true, note)
}

func (s *staffDepartmentsImpl) Reject(ctx context.Context, c *domain.Caller, id, reason string) (domain.StaffDepartmentRequest, error) {
	sc, err := s.access(ctx, c, true)
	if err != nil {
		return domain.StaffDepartmentRequest{}, err
	}
	reason = strings.TrimSpace(reason)
	switch {
	case len([]rune(reason)) < deptMinReject:
		return domain.StaffDepartmentRequest{}, domain.ValidationProblem(map[string]string{"reason": "Say why: the seller reads it."})
	case len([]rune(reason)) > deptNoteMax:
		return domain.StaffDepartmentRequest{}, domain.ValidationProblem(map[string]string{"reason": "Keep the reason under 500 characters."})
	}
	return s.decide(ctx, sc, id, false, reason)
}

func (s *staffDepartmentsImpl) decide(ctx context.Context, sc staffCtx, id string, approve bool, note string) (domain.StaffDepartmentRequest, error) {
	body := jobj{"approve": approve}
	if note != "" {
		body["note"] = note
	}
	if err := sc.post(ctx, sc.up.Seller, "/v1/department-requests/"+url.PathEscape(id)+"/decision", body, nil); err != nil {
		return domain.StaffDepartmentRequest{}, err
	}
	return s.detail(ctx, sc, id)
}

func (s *staffDepartmentsImpl) OrgDepartments(ctx context.Context, c *domain.Caller, orgID string) (domain.StaffOrgDepartments, error) {
	sc, err := s.orgAccess(ctx, c, false)
	if err != nil {
		return domain.StaffOrgDepartments{}, err
	}
	return s.orgView(ctx, sc, orgID)
}

func (s *staffDepartmentsImpl) orgView(ctx context.Context, sc staffCtx, orgID string) (domain.StaffOrgDepartments, error) {
	var scope jobj
	if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/"+url.PathEscape(orgID)+"/catalog-scope", nil, &scope); err != nil {
		return domain.StaffOrgDepartments{}, err
	}
	catalog, err := catalogDepartments(ctx, sc)
	if err != nil {
		return domain.StaffOrgDepartments{}, err
	}
	depts := newDepartmentNames(catalog)
	out := domain.StaffOrgDepartments{OrgID: orgID, OrgName: sc.names.name(ctx, sc, orgRef("orgId"), orgID), Exempt: boolean(scope, "exempt"),
		Departments: []domain.StaffDepartment{}, Catalog: catalog}
	for _, code := range strs(scope, "departments") {
		out.Departments = append(out.Departments, depts.of(code))
	}
	sortByName(out.Departments)
	for _, r := range objs(scope, "requests") {
		if jstr(r, "status") == domain.DeptRequestPending {
			out.PendingRequests++
		}
	}
	out.CanEdit = sc.access.Can(domain.PermApplicationsApprove) && !out.Exempt
	return out, nil
}

func (s *staffDepartmentsImpl) SetOrgDepartments(ctx context.Context, c *domain.Caller, orgID string, departments []string, reason string) (domain.StaffOrgDepartments, error) {
	sc, err := s.orgAccess(ctx, c, true)
	if err != nil {
		return domain.StaffOrgDepartments{}, err
	}
	reason = strings.TrimSpace(reason)
	errs := map[string]string{}
	if len([]rune(reason)) < deptMinOverride {
		errs["reason"] = "Say why the departments change (at least 10 characters)."
	} else if len([]rune(reason)) > deptMaxReason {
		errs["reason"] = "Keep the reason under 1000 characters."
	}
	if len(errs) > 0 {
		return domain.StaffOrgDepartments{}, domain.ValidationProblem(errs)
	}
	current, err := s.orgView(ctx, sc, orgID)
	if err != nil {
		return domain.StaffOrgDepartments{}, err
	}
	if current.Exempt {
		return domain.StaffOrgDepartments{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Conflict",
			"AfroTransact Retail sells in every department; there is nothing to change.")
	}
	known := newDepartmentNames(current.Catalog)
	chosen := []string{}
	for _, code := range departments {
		code = strings.TrimSpace(code)
		if _, ok := known[code]; !ok {
			return domain.StaffOrgDepartments{}, domain.ValidationProblem(map[string]string{"departments": "Choose departments from the list."})
		}
		if !slices.Contains(chosen, code) {
			chosen = append(chosen, code)
		}
	}
	slices.Sort(chosen)
	now := make([]string, 0, len(current.Departments))
	for _, d := range current.Departments {
		now = append(now, d.Code)
	}
	slices.Sort(now)
	if slices.Equal(chosen, now) {
		return domain.StaffOrgDepartments{}, domain.ValidationProblem(map[string]string{"departments": "Nothing changed. Choose different departments before saving."})
	}
	if err := sc.call(ctx, sc.up.Seller, port.StaffCall{Method: http.MethodPut, Path: "/v1/orgs/" + url.PathEscape(orgID) + "/catalog-scope/departments",
		Body: jobj{"departments": chosen, "reason": reason}}, nil); err != nil {
		return domain.StaffOrgDepartments{}, err
	}
	return s.orgView(ctx, sc, orgID)
}

package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffAccessService is Settings > Roles and permissions of the staff console: the permission catalogue, the roles of
// each portal with their permissions, who holds a role, a person's effective permissions with their direct grants, and
// the access change log (contracts/openapi/gateway-bff-staff.yaml, tag Access). Identity owns all of it: every call goes
// out with the staff member's own token, so identity's access:read and access:admin checks are the final word; the BFF
// checks the console module (Users & roles) first and asks for a reason on every change.
type StaffAccessService interface {
	// Permissions is the catalogue, straight from identity (so a permission added by a migration shows up with no change here).
	Permissions(ctx context.Context, c *domain.Caller) (map[string]any, error)
	// Roles lists a portal's roles (platform, seller, provider, account) with their permissions.
	Roles(ctx context.Context, c *domain.Caller, portal string) (map[string]any, error)
	// RoleMembers names the people who hold a role, a page at a time (identity filters by role, so the total is exact).
	RoleMembers(ctx context.Context, c *domain.Caller, role string, page, size int) (map[string]any, error)
	// ChangeRolePermission adds or removes one permission of a role; the reason is kept in identity's change log.
	ChangeRolePermission(ctx context.Context, c *domain.Caller, role, permission string, grant bool, reason string) (map[string]any, error)
	// People searches people by name or email, each with roles, direct grants and effective permissions.
	People(ctx context.Context, c *domain.Caller, q string, page, size int) (map[string]any, error)
	// Person is one person's access.
	Person(ctx context.Context, c *domain.Caller, id string) (map[string]any, error)
	// ChangePersonPermission grants or revokes one direct permission of a person.
	ChangePersonPermission(ctx context.Context, c *domain.Caller, id, permission string, grant bool, reason string) (map[string]any, error)
	// Log is the access change log, newest first, optionally for one person, role or permission.
	Log(ctx context.Context, c *domain.Caller, f StaffAccessLogFilter) (map[string]any, error)
}

// StaffAccessLogFilter narrows the change log. Identity filters by person, role and permission (exact, indexed) and pages it.
type StaffAccessLogFilter struct {
	UserID, Role, Permission string
	Page, Size               int
}

const (
	accessReasonMin  = 3
	accessReasonMax  = 300
	accessLogMaxSize = 100
)

var (
	accessPortals    = []string{"platform", "seller", "provider", "account"}
	accessPermRE     = regexp.MustCompile(`^[a-z][a-z0-9-]*(:[a-z0-9][a-z0-9.-]*)+$`)
	accessRoleRE     = regexp.MustCompile(`^[a-z]+\.[a-z0-9_]+$`)
	accessUserIDRE   = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	accessLockedPerm = map[string][]string{"platform.super_admin": {"access:admin", "access:read"}}
)

type staffAccessImpl struct{ r *staffResolver }

var _ StaffAccessService = (*staffAccessImpl)(nil)

// NewStaffAccessService returns the roles and permissions use case.
func NewStaffAccessService(d StaffDeps) StaffAccessService {
	return &staffAccessImpl{r: newStaffResolver(d)}
}

// read opens the screen: View on Users & roles and the access:read permission (identity checks it again).
func (s *staffAccessImpl) read(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, "rbac", domain.LevelView)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can("access:read") {
		return sc, needsPermission("access:read")
	}
	return sc, nil
}

// write changes access: Edit on Users & roles and the access:admin permission.
func (s *staffAccessImpl) write(ctx context.Context, c *domain.Caller, reason string) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, "rbac", domain.LevelEdit)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can("access:admin") {
		return sc, needsPermission("access:admin")
	}
	if n := utf8.RuneCountInString(strings.TrimSpace(reason)); n < accessReasonMin || n > accessReasonMax {
		return sc, domain.ValidationProblem(map[string]string{"reason": "Give a reason of " + strconv.Itoa(accessReasonMin) + " to " + strconv.Itoa(accessReasonMax) + " characters"})
	}
	return sc, nil
}

func (s *staffAccessImpl) Permissions(ctx context.Context, c *domain.Caller) (map[string]any, error) {
	sc, err := s.read(ctx, c)
	if err != nil {
		return nil, err
	}
	var raw any
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/permissions", nil, &raw); err != nil {
		return nil, err
	}
	items := jpage(raw).items
	slices.SortStableFunc(items, func(a, b jobj) int {
		if m := strings.Compare(jstr(a, "module"), jstr(b, "module")); m != 0 {
			return m
		}
		return strings.Compare(jstr(a, "key"), jstr(b, "key"))
	})
	out := make([]any, 0, len(items))
	for _, p := range items {
		out = append(out, jobj{"key": jstr(p, "key"), "module": jstr(p, "module"), "description": jstr(p, "description")})
	}
	return jobj{"items": out}, nil
}

// roleView is a role as the console shows it: the platform roles keep the console's labels.
func roleView(o jobj) jobj {
	perms := strs(o, "permissions")
	slices.Sort(perms)
	name := jstr(o, "name")
	if jstr(o, "portal") == "platform" || strings.HasPrefix(jstr(o, "key"), "platform.") {
		name = domain.PlatformRoleLabel(jstr(o, "key"))
	}
	if name == "" {
		name = domain.RoleLabel(jstr(o, "key"))
	}
	return jobj{"key": jstr(o, "key"), "portal": jstr(o, "portal"), "name": name, "description": jstr(o, "description"), "permissions": perms, "permissionCount": len(perms)}
}

func (s *staffAccessImpl) Roles(ctx context.Context, c *domain.Caller, portal string) (map[string]any, error) {
	sc, err := s.read(ctx, c)
	if err != nil {
		return nil, err
	}
	if portal == "" {
		portal = "platform"
	}
	if !slices.Contains(accessPortals, portal) {
		return nil, domain.ValidationProblem(map[string]string{"portal": "Choose platform, seller, provider or account"})
	}
	var raw any
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/roles", url.Values{"portal": {portal}}, &raw); err != nil {
		return nil, err
	}
	items := []any{}
	for _, r := range jpage(raw).items {
		items = append(items, roleView(r))
	}
	return jobj{"portal": portal, "items": items, "canAdmin": sc.access.Can("access:admin") && sc.access.Allows("rbac", domain.LevelEdit)}, nil
}

// userView is a person's access: roles with their labels, direct grants and the permissions that result.
func userView(u jobj) jobj {
	roles := []any{}
	for _, k := range strs(u, "roles") {
		roles = append(roles, jobj{"key": k, "name": domain.PlatformRoleLabel(k)})
	}
	markets := jobj{}
	if m, ok := u["roleMarkets"].(map[string]any); ok {
		markets = m
	}
	eff, direct := strs(u, "effectivePermissions"), strs(u, "directGrants")
	slices.Sort(eff)
	slices.Sort(direct)
	return jobj{"id": jstr(u, "id"), "name": firstNonEmpty(jstr(u, "name"), jstr(u, "email")), "email": jstr(u, "email"), "enabled": boolean(u, "enabled"),
		"principalType": jstr(u, "principalType"), "roles": roles, "directGrants": direct, "effectivePermissions": eff, "roleMarkets": markets}
}

func (s *staffAccessImpl) RoleMembers(ctx context.Context, c *domain.Caller, role string, page, size int) (map[string]any, error) {
	sc, err := s.read(ctx, c)
	if err != nil {
		return nil, err
	}
	if !accessRoleRE.MatchString(role) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such role.")
	}
	if size < 1 || size > accessLogMaxSize {
		size = 50
	}
	if page < 0 {
		page = 0
	}
	// Identity filters the directory by role (GET /v1/admin/users?role=), so the page and the total are exact.
	var res jobj
	q := url.Values{"role": {role}, "page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", q, &res); err != nil {
		return nil, err
	}
	members := []any{}
	for _, u := range objs(res, "items") {
		v := userView(u)
		members = append(members, jobj{"id": v["id"], "name": v["name"], "email": v["email"], "enabled": v["enabled"], "markets": markets(u, role)})
	}
	return jobj{"role": role, "items": members, "total": int64(numberOf(res, "total")), "page": page, "size": size}, nil
}

func markets(u jobj, role string) []string {
	m, _ := u["roleMarkets"].(map[string]any)
	list, _ := m[role].([]any)
	out := []string{}
	for _, v := range list {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func numberOf(o jobj, path string) float64 {
	n, _ := at(o, path).(float64)
	return n
}

func (s *staffAccessImpl) ChangeRolePermission(ctx context.Context, c *domain.Caller, role, permission string, grant bool, reason string) (map[string]any, error) {
	sc, err := s.write(ctx, c, reason)
	if err != nil {
		return nil, err
	}
	if !accessRoleRE.MatchString(role) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such role.")
	}
	if !accessPermRE.MatchString(permission) {
		return nil, domain.ValidationProblem(map[string]string{"permissionKey": "A permission key, e.g. refunds:issue"})
	}
	// Nobody locks Super Admin out of this screen: the two permissions it is reached with stay on that role.
	if !grant && slices.Contains(accessLockedPerm[role], permission) {
		return nil, domain.NewProblem(http.StatusConflict, "ACCESS_LOCKOUT", "Can't remove this", "Super Admin keeps "+permission+" so the console can always be administered.")
	}
	method := http.MethodPut
	if !grant {
		method = http.MethodDelete
	}
	var out jobj
	call := port.StaffCall{Method: method, Path: "/v1/admin/roles/" + url.PathEscape(role) + "/permissions/" + url.PathEscape(permission), Query: url.Values{"reason": {strings.TrimSpace(reason)}}}
	if err := sc.call(ctx, sc.up.Identity, call, &out); err != nil {
		return nil, err
	}
	return roleView(out), nil
}

func (s *staffAccessImpl) People(ctx context.Context, c *domain.Caller, q string, page, size int) (map[string]any, error) {
	sc, err := s.read(ctx, c)
	if err != nil {
		return nil, err
	}
	if size < 1 || size > 100 {
		size = 25
	}
	if page < 0 {
		page = 0
	}
	v := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if q = strings.TrimSpace(q); q != "" {
		v.Set("q", q)
	}
	var res jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", v, &res); err != nil {
		return nil, err
	}
	items := []any{}
	for _, u := range objs(res, "items") {
		items = append(items, userView(u))
	}
	return jobj{"items": items, "total": int64(numberOf(res, "total")), "page": page, "size": size}, nil
}

func (s *staffAccessImpl) Person(ctx context.Context, c *domain.Caller, id string) (map[string]any, error) {
	sc, err := s.read(ctx, c)
	if err != nil {
		return nil, err
	}
	if !accessUserIDRE.MatchString(id) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such person.")
	}
	var u jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(id), nil, &u); err != nil {
		return nil, err
	}
	return userView(u), nil
}

func (s *staffAccessImpl) ChangePersonPermission(ctx context.Context, c *domain.Caller, id, permission string, grant bool, reason string) (map[string]any, error) {
	sc, err := s.write(ctx, c, reason)
	if err != nil {
		return nil, err
	}
	if !accessUserIDRE.MatchString(id) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such person.")
	}
	if !accessPermRE.MatchString(permission) {
		return nil, domain.ValidationProblem(map[string]string{"permissionKey": "A permission key, e.g. delivery:operate"})
	}
	method := http.MethodPut
	if !grant {
		method = http.MethodDelete
	}
	var out jobj
	call := port.StaffCall{Method: method, Path: "/v1/admin/users/" + url.PathEscape(id) + "/permissions/" + url.PathEscape(permission), Query: url.Values{"reason": {strings.TrimSpace(reason)}}}
	if err := sc.call(ctx, sc.up.Identity, call, &out); err != nil {
		return nil, err
	}
	return userView(out), nil
}

func (s *staffAccessImpl) Log(ctx context.Context, c *domain.Caller, f StaffAccessLogFilter) (map[string]any, error) {
	sc, err := s.read(ctx, c)
	if err != nil {
		return nil, err
	}
	if f.UserID != "" && !accessUserIDRE.MatchString(f.UserID) {
		return nil, domain.ValidationProblem(map[string]string{"userId": "Not a person id"})
	}
	if f.Size < 1 || f.Size > accessLogMaxSize {
		f.Size = 50
	}
	if f.Page < 0 {
		f.Page = 0
	}
	if f.Role != "" && !accessRoleRE.MatchString(f.Role) {
		return nil, domain.ValidationProblem(map[string]string{"role": "Not a role key"})
	}
	if f.Permission != "" && !accessPermRE.MatchString(f.Permission) {
		return nil, domain.ValidationProblem(map[string]string{"permission": "Not a permission key"})
	}
	// Identity filters by person, role and permission and pages the result, so nothing is scanned or cut off here.
	v := url.Values{"page": {strconv.Itoa(f.Page)}, "size": {strconv.Itoa(f.Size)}}
	for k, val := range map[string]string{"userId": f.UserID, "role": f.Role, "permission": f.Permission} {
		if val != "" {
			v.Set(k, val)
		}
	}
	var res jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/access-log", v, &res); err != nil {
		return nil, err
	}
	rows := objs(res, "items")
	nameAll(ctx, sc, rows, personRef("actorId", "Actor"), personRef("targetUserId", "Person"))
	items := []any{}
	for _, e := range rows {
		role := jstr(e, "targetRole")
		item := jobj{"id": jstr(e, "id"), "at": jstr(e, "at"), "actorName": jstr(e, "actorName"), "action": jstr(e, "action"), "actionLabel": domain.Humanize(jstr(e, "action")),
			"targetName": jstr(e, "targetUserName"), "role": role, "subject": jstr(e, "subject"), "detail": jstr(e, "detail")}
		if role != "" {
			item["roleName"] = domain.PlatformRoleLabel(role)
		}
		items = append(items, item)
	}
	return jobj{"items": items, "page": f.Page, "size": f.Size, "total": int64(numberOf(res, "total"))}, nil
}

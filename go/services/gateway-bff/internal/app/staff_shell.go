package app

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffDeps are the upstreams and stores of the staff console routes.
type StaffDeps struct {
	Up port.StaffUpstreams
	// Matrix reads identity's module matrix (shared with the Seller Center routes).
	Matrix port.OrgAccessClient
	// Cache holds identity's platform matrix (not personal data).
	Cache     port.Cache
	MatrixTTL time.Duration
	Jobs      port.ExportJobStore
	// Maintenance is the platform maintenance switch (Settings → General).
	Maintenance MaintenanceService
	Blobs       port.ExportBlobStore
	// Renderer writes export files (CSV, XLSX with native charts, PDF).
	Renderer port.ExportRenderer
	// ExportBasePath prefixes download links handed to the console (its same-origin BFF proxy, e.g. /api/bff).
	ExportBasePath string
	Clock          Clock
	Log            *slog.Logger
	// Background runs export jobs (defaults to a goroutine); tests run them inline.
	Background func(func())
	// RetailOrgID is AfroTransact Retail (merchant of record): never charged commission or fees, never overridden.
	RetailOrgID string
}

func (d *StaffDeps) defaults() {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	if d.Clock == nil {
		d.Clock = time.Now
	}
	if d.MatrixTTL <= 0 {
		d.MatrixTTL = time.Minute
	}
	if d.Background == nil {
		d.Background = func(f func()) { go f() }
	}
}

// staffMe is identity's GET /v1/me as the staff routes need it.
type staffMe struct {
	ID            string   `json:"id"`
	FirstName     string   `json:"firstName"`
	LastName      string   `json:"lastName"`
	Email         string   `json:"email"`
	PrincipalType string   `json:"principalType"`
	Roles         []string `json:"roles"`
	Permissions   []string `json:"permissions"`
}

// staffCtx is the signed-in staff member and their access, plus everything an upstream call needs.
type staffCtx struct {
	me     staffMe
	access domain.StaffAccess
	bearer string
	up     port.StaffUpstreams
	now    time.Time
	maint  MaintenanceService
	// idem is the request's Idempotency-Key (relayed to upstreams that accept one).
	idem string
}

// call runs one upstream call with the staff member's token.
func (s staffCtx) call(ctx context.Context, up port.StaffUpstream, c port.StaffCall, out any) error {
	if up == nil {
		return domain.UpstreamUnavailable("staff")
	}
	if err := up.Call(ctx, s.bearer, c, out); err != nil {
		return mapErr(up.Name(), err)
	}
	return nil
}

func (s staffCtx) get(ctx context.Context, up port.StaffUpstream, path string, q url.Values, out any) error {
	return s.call(ctx, up, port.StaffCall{Path: path, Query: q}, out)
}

func (s staffCtx) post(ctx context.Context, up port.StaffUpstream, path string, body, out any) error {
	return s.call(ctx, up, port.StaffCall{Method: http.MethodPost, Path: path, Body: body, IdempotencyKey: s.idem}, out)
}

// staffResolver resolves the staff member for every staff route.
type staffResolver struct {
	d StaffDeps
}

func newStaffResolver(d StaffDeps) *staffResolver {
	d.defaults()
	return &staffResolver{d: d}
}

// matrix reads identity's PLATFORM matrix; staff without access:read (403) and an unavailable identity fall back to
// the seeded defaults. Either result is cached.
func (r *staffResolver) matrix(ctx context.Context, bearer string) domain.ModuleMatrix {
	const key = "identity:module-matrix:platform"
	if r.d.Cache != nil {
		if b, ok := r.d.Cache.Get(key); ok {
			var m domain.ModuleMatrix
			if json.Unmarshal(b, &m) == nil && len(m) > 0 {
				return m
			}
		}
	}
	var m domain.ModuleMatrix
	var err error
	if r.d.Matrix != nil {
		m, err = r.d.Matrix.ModuleMatrix(ctx, bearer, "platform")
	}
	if err != nil || len(m) == 0 {
		if p, ok := port.AsUpstreamProblem(err); !ok || p.Status != http.StatusForbidden {
			r.d.Log.DebugContext(ctx, "platform module matrix unavailable; using defaults", "error", errString(err))
		}
		m = domain.DefaultPlatformMatrix
	}
	if r.d.Cache != nil {
		if b, err := json.Marshal(m); err == nil {
			r.d.Cache.Set(key, b, r.d.MatrixTTL)
		}
	}
	return m
}

// resolve returns the staff context: 401 for guests, 403 NOT_STAFF for customers and seller members.
func (r *staffResolver) resolve(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	if err := requireCustomer(c); err != nil {
		return staffCtx{}, err
	}
	sc := staffCtx{bearer: c.BearerToken, up: r.d.Up, now: r.d.Clock(), maint: r.d.Maintenance}
	if err := sc.get(ctx, r.d.Up.Identity, "/v1/me", nil, &sc.me); err != nil {
		return staffCtx{}, err
	}
	if !strings.EqualFold(sc.me.PrincipalType, domain.PrincipalStaff) {
		return staffCtx{}, domain.NotStaff()
	}
	sc.access = domain.StaffAccess{Roles: domain.PlatformRoles(sc.me.Roles), Permissions: sc.me.Permissions, Matrix: r.matrix(ctx, c.BearerToken)}
	return sc, nil
}

// require resolves the staff member and checks the module level.
func (r *staffResolver) require(ctx context.Context, c *domain.Caller, module, level string) (staffCtx, error) {
	sc, err := r.resolve(ctx, c)
	if err != nil {
		return sc, err
	}
	if !sc.access.Allows(module, level) {
		return sc, staffModuleDenied(module, level)
	}
	return sc, nil
}

func staffModuleDenied(module, level string) *domain.Problem {
	verb := "open"
	if level != domain.LevelView {
		verb = "change"
	}
	label := module
	for _, g := range domain.StaffNav {
		for _, m := range g.Modules {
			if m.Key == module {
				label = m.Label
			}
		}
	}
	return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "Your roles can't "+verb+" "+strings.ToLower(label)+".")
}

func needsPermission(permission string) *domain.Problem {
	return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "This needs the "+permission+" permission.")
}

// ---------------------------------------------------------------- shell

// StaffShellService serves GET /v1/staff/session.
type StaffShellService interface {
	Session(ctx context.Context, c *domain.Caller) (domain.StaffSession, error)
}

type staffShellImpl struct{ r *staffResolver }

var _ StaffShellService = (*staffShellImpl)(nil)

// NewStaffShellService returns the staff shell use case.
func NewStaffShellService(d StaffDeps) StaffShellService {
	return &staffShellImpl{r: newStaffResolver(d)}
}

func (s *staffShellImpl) Session(ctx context.Context, c *domain.Caller) (domain.StaffSession, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return domain.StaffSession{}, err
	}
	nav := domain.StaffNavigation(sc.access, s.badges(ctx, sc))
	if len(nav) == 0 {
		return domain.StaffSession{}, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "No access",
			"Your roles don't open any part of the staff console.")
	}
	roles := make([]domain.StaffRole, 0, len(sc.access.Roles))
	labels := make([]string, 0, len(sc.access.Roles))
	for _, r := range sc.access.Roles {
		roles = append(roles, domain.StaffRole{Key: r, Label: domain.PlatformRoleLabel(r)})
		labels = append(labels, domain.PlatformRoleLabel(r))
	}
	landing := nav[0].Modules[0].Key
	if sc.access.Allows("dash", domain.LevelView) {
		landing = "dash"
	}
	perms := sc.access.Permissions
	if perms == nil {
		perms = []string{}
	}
	return domain.StaffSession{
		User: domain.StaffUser{ID: sc.me.ID, FirstName: sc.me.FirstName, LastName: sc.me.LastName, Email: sc.me.Email,
			Initials: domain.Profile{FirstName: sc.me.FirstName, LastName: sc.me.LastName}.Initials()},
		Roles: roles, RoleLabel: strings.Join(labels, " · "), Permissions: perms, NavGroups: nav, Landing: landing,
		AccessNote: "Least privilege by role · enforced server-side", RefundLimitMinor: domain.RefundLimit(sc.access.Roles),
	}, nil
}

// badges counts the queues that need someone (applications to review, open support cases, claims to decide). Each
// count is best effort: a failing upstream simply shows no badge.
func (s *staffShellImpl) badges(ctx context.Context, sc staffCtx) map[string]int {
	type probe struct {
		module, perm string
		up           port.StaffUpstream
		path         string
		q            url.Values
	}
	probes := []probe{
		{"apps", "applications:review", sc.up.Seller, "/v1/applications", url.Values{"size": {"1"}}},
		{"support", "support:respond", sc.up.TrustOps, "/v1/support-cases", url.Values{"status": {"OPEN"}, "size": {"1"}}},
		{"disputes", "disputes:manage", sc.up.TrustOps, "/v1/claims", url.Values{"status": {"UNDER_REVIEW"}, "size": {"1"}}},
	}
	out := map[string]int{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	cctx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	for _, p := range probes {
		if !sc.access.Can(p.perm) || !sc.access.Allows(p.module, domain.LevelView) || p.up == nil {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			var page struct {
				Total int `json:"total"`
			}
			if err := sc.get(cctx, p.up, p.path, p.q, &page); err != nil {
				var pr *domain.Problem
				if !errors.As(err, &pr) || pr.Status >= 500 {
					s.r.d.Log.DebugContext(ctx, "staff badge unavailable", "module", p.module, "error", err.Error())
				}
				return
			}
			mu.Lock()
			out[p.module] = page.Total
			mu.Unlock()
		}()
	}
	wg.Wait()
	return out
}

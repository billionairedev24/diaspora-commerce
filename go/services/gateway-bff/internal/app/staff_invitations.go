package app

import (
	"context"
	"net/http"
	"net/mail"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffInvitationsService is Settings > Staff accounts' invitation flow (contracts/openapi/gateway-bff-staff.yaml, tag
// Staff invitations): staff with access:admin invite a person by name, work email and role with a reason; the open invitations
// are listed with names and emails; they can be sent again (a new link, the old one stops working) or revoked. The invitee's
// two pages (preview and accept) need no session: the token travels in the request body, never the URL, and neither it nor
// the password is logged, cached or kept here. Identity owns everything: it stores the token hashed, expires it, sends the
// email through notifications, creates the account and audits every step.
type StaffInvitationsService interface {
	// Open lists the invitations that are still open (pending, and expired ones never used), a page at a time.
	Open(ctx context.Context, c *domain.Caller, page, size int) (map[string]any, error)
	// Invite creates an invitation and queues the email.
	Invite(ctx context.Context, c *domain.Caller, in StaffInvite) (map[string]any, error)
	// Resend issues a new link and expiry for an open invitation.
	Resend(ctx context.Context, c *domain.Caller, id string) (map[string]any, error)
	// Revoke ends an open invitation at once; the reason is kept in the access change log.
	Revoke(ctx context.Context, c *domain.Caller, id, reason string) error
	// Preview says who an invite link is for (public).
	Preview(ctx context.Context, token string) (map[string]any, error)
	// Accept creates the account with the invitee's own password (public).
	Accept(ctx context.Context, in StaffInviteAccept) (map[string]any, error)
}

// StaffInvite is the body of an invitation.
type StaffInvite struct{ FirstName, LastName, Email, RoleKey, Reason string }

// StaffInviteAccept is the invitee's side. Its String method never shows the token or the password.
type StaffInviteAccept struct {
	Token, Password, ConfirmPassword, Phone, PhoneRegion string
}

// String keeps the secrets out of any log line that formats the value.
func (StaffInviteAccept) String() string { return "StaffInviteAccept{redacted}" }

const maxInviteToken = 200

var inviteRegionRE = regexp.MustCompile(`^[A-Za-z]{2}$`)

type staffInvitationsImpl struct {
	r  *staffResolver
	up port.StaffUpstream
}

var _ StaffInvitationsService = (*staffInvitationsImpl)(nil)

// NewStaffInvitationsService returns the staff invitations use case.
func NewStaffInvitationsService(d StaffDeps) StaffInvitationsService {
	return &staffInvitationsImpl{r: newStaffResolver(d), up: d.Up.Identity}
}

func (s *staffInvitationsImpl) read(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, "rbac", domain.LevelView)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can("access:read") {
		return sc, needsPermission("access:read")
	}
	return sc, nil
}

func (s *staffInvitationsImpl) write(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, "rbac", domain.LevelEdit)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can("access:admin") {
		return sc, needsPermission("access:admin")
	}
	return sc, nil
}

func inviteView(o jobj) jobj {
	first, last := jstr(o, "firstName"), jstr(o, "lastName")
	return jobj{"id": jstr(o, "id"), "name": strings.TrimSpace(first + " " + last), "firstName": first, "lastName": last, "email": jstr(o, "email"),
		"roleKey": jstr(o, "roleKey"), "roleName": firstNonEmpty(jstr(o, "roleName"), domain.PlatformRoleLabel(jstr(o, "roleKey"))),
		"status": jstr(o, "status"), "reason": jstr(o, "reason"), "invitedByName": jstr(o, "invitedByName"), "createdAt": jstr(o, "createdAt"),
		"expiresAt": jstr(o, "expiresAt"), "lastSentAt": jstr(o, "lastSentAt"), "sendCount": int64(numberOf(o, "sendCount"))}
}

func (s *staffInvitationsImpl) Open(ctx context.Context, c *domain.Caller, page, size int) (map[string]any, error) {
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
	var res jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/staff-invitations", url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}, &res); err != nil {
		return nil, err
	}
	items := []any{}
	for _, o := range objs(res, "items") {
		items = append(items, inviteView(o))
	}
	return jobj{"items": items, "total": int64(numberOf(res, "total")), "page": page, "size": size, "canAdmin": sc.access.Can("access:admin") && sc.access.Allows("rbac", domain.LevelEdit)}, nil
}

func (s *staffInvitationsImpl) Invite(ctx context.Context, c *domain.Caller, in StaffInvite) (map[string]any, error) {
	sc, err := s.write(ctx, c)
	if err != nil {
		return nil, err
	}
	errs := map[string]string{}
	for field, v := range map[string]string{"firstName": in.FirstName, "lastName": in.LastName} {
		if n := utf8.RuneCountInString(strings.TrimSpace(v)); n < 1 || n > 80 {
			errs[field] = "Enter a name of up to 80 characters"
		}
	}
	if a, err := mail.ParseAddress(strings.TrimSpace(in.Email)); err != nil || a.Address != strings.TrimSpace(in.Email) {
		errs["email"] = "Enter a valid work email address"
	}
	if !accessRoleRE.MatchString(in.RoleKey) || !strings.HasPrefix(in.RoleKey, "platform.") {
		errs["roleKey"] = "Choose a staff role"
	}
	if n := utf8.RuneCountInString(strings.TrimSpace(in.Reason)); n < accessReasonMin || n > accessReasonMax {
		errs["reason"] = "Give a reason of " + strconv.Itoa(accessReasonMin) + " to " + strconv.Itoa(accessReasonMax) + " characters"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	var out jobj
	body := jobj{"firstName": strings.TrimSpace(in.FirstName), "lastName": strings.TrimSpace(in.LastName), "email": strings.TrimSpace(in.Email), "roleKey": in.RoleKey, "reason": strings.TrimSpace(in.Reason)}
	if err := sc.post(ctx, sc.up.Identity, "/v1/admin/staff-invitations", body, &out); err != nil {
		return nil, err
	}
	return inviteView(out), nil
}

func (s *staffInvitationsImpl) Resend(ctx context.Context, c *domain.Caller, id string) (map[string]any, error) {
	sc, err := s.write(ctx, c)
	if err != nil {
		return nil, err
	}
	if !accessUserIDRE.MatchString(id) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such invitation.")
	}
	var out jobj
	if err := sc.post(ctx, sc.up.Identity, "/v1/admin/staff-invitations/"+url.PathEscape(id)+"/resend", jobj{}, &out); err != nil {
		return nil, err
	}
	return inviteView(out), nil
}

func (s *staffInvitationsImpl) Revoke(ctx context.Context, c *domain.Caller, id, reason string) error {
	sc, err := s.write(ctx, c)
	if err != nil {
		return err
	}
	if !accessUserIDRE.MatchString(id) {
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such invitation.")
	}
	if n := utf8.RuneCountInString(strings.TrimSpace(reason)); n < accessReasonMin || n > accessReasonMax {
		return domain.ValidationProblem(map[string]string{"reason": "Give a reason of " + strconv.Itoa(accessReasonMin) + " to " + strconv.Itoa(accessReasonMax) + " characters"})
	}
	return sc.post(ctx, sc.up.Identity, "/v1/admin/staff-invitations/"+url.PathEscape(id)+"/revoke", jobj{"reason": strings.TrimSpace(reason)}, nil)
}

func (s *staffInvitationsImpl) Preview(ctx context.Context, token string) (map[string]any, error) {
	if n := utf8.RuneCountInString(token); n == 0 || n > maxInviteToken {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "This invitation link isn't valid.")
	}
	var out jobj
	// No bearer: the invitee has no session. The token goes in the body only.
	if err := s.publicCall(ctx, "/v1/staff-invitations/preview", jobj{"token": token}, &out); err != nil {
		return nil, err
	}
	return jobj{"firstName": jstr(out, "firstName"), "lastName": jstr(out, "lastName"), "name": strings.TrimSpace(jstr(out, "firstName") + " " + jstr(out, "lastName")),
		"email": jstr(out, "email"), "roleName": jstr(out, "roleName"), "status": jstr(out, "status"), "expiresAt": jstr(out, "expiresAt")}, nil
}

func (s *staffInvitationsImpl) Accept(ctx context.Context, in StaffInviteAccept) (map[string]any, error) {
	errs := map[string]string{}
	if n := utf8.RuneCountInString(in.Token); n == 0 || n > maxInviteToken {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "This invitation link isn't valid.")
	}
	if n := utf8.RuneCountInString(in.Password); n < 1 || n > 128 {
		errs["password"] = "Choose a password"
	}
	if in.Password != in.ConfirmPassword {
		errs["confirmPassword"] = "The two passwords don't match"
	}
	if in.Phone != "" && !inviteRegionRE.MatchString(in.PhoneRegion) {
		errs["phone"] = "Choose the country of the number"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	body := jobj{"token": in.Token, "password": in.Password, "confirmPassword": in.ConfirmPassword}
	if in.Phone != "" {
		body["phone"], body["phoneRegion"] = in.Phone, strings.ToUpper(in.PhoneRegion)
	}
	var out jobj
	if err := s.publicCall(ctx, "/v1/staff-invitations/accept", body, &out); err != nil {
		return nil, err
	}
	return jobj{"email": jstr(out, "email"), "roleName": jstr(out, "roleName")}, nil
}

func (s *staffInvitationsImpl) publicCall(ctx context.Context, path string, body, out any) error {
	if s.up == nil {
		return domain.UpstreamUnavailable("identity")
	}
	if err := s.up.Call(ctx, "", port.StaffCall{Method: http.MethodPost, Path: path, Body: body}, out); err != nil {
		return mapErr(s.up.Name(), err)
	}
	return nil
}

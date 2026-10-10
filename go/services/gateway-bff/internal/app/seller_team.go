package app

import (
	"context"
	"net/http"
	"net/mail"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerTeamService serves Team & roles (S236): identity's member directory plus pending invitations, capability
// cards, invite / revoke, role changes, removal and the invitation accept page.
type SellerTeamService interface {
	Team(ctx context.Context, c *domain.Caller) (domain.TeamView, error)
	Invite(ctx context.Context, c *domain.Caller, email, roleKey string) (domain.TeamMember, error)
	AssignRole(ctx context.Context, c *domain.Caller, userID, roleKey string) (domain.TeamView, error)
	RemoveMember(ctx context.Context, c *domain.Caller, userID string) (domain.TeamView, error)
	RevokeInvitation(ctx context.Context, c *domain.Caller, invitationID string) (domain.TeamView, error)
	// LookupInvitation is public: it shows what an invitation link is for before the person signs in or up.
	LookupInvitation(ctx context.Context, token string) (domain.InvitationPreview, error)
	// AcceptInvitation joins the signed-in person to the organisation and makes it their active organisation.
	AcceptInvitation(ctx context.Context, c *domain.Caller, token string) (domain.AcceptedInvitation, error)
}

type sellerTeamImpl struct{ r *orgResolver }

var _ SellerTeamService = (*sellerTeamImpl)(nil)

// NewSellerTeamService returns the SellerTeamService.
func NewSellerTeamService(d SellerDeps) SellerTeamService { return &sellerTeamImpl{r: newResolver(d)} }

const maxInvitationToken = 200

// teamErr maps identity's team and invitation problems to the BFF's codes with a message people can act on.
func teamErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("identity", err)
	}
	problem := func(status int, code, title, detail string) error {
		return domain.NewProblem(status, code, title, detail).WithCause(err)
	}
	switch p.Code {
	case "identity.last-owner":
		return problem(http.StatusUnprocessableEntity, domain.CodeLastOwner, "Last owner",
			"The organisation needs at least one owner. Make someone else an owner first.")
	case "identity.invalid-role":
		pr := domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeInvalidRole, "Invalid role", "Choose a role of this organisation.")
		pr.Errors = map[string]string{"roleKey": "Choose a role of this organisation"}
		return pr.WithCause(err)
	case "identity.owner-only":
		return problem(http.StatusForbidden, domain.CodeOwnerOnly, "Owner only", "Only an owner can do that.")
	case "identity.own-role":
		return problem(http.StatusForbidden, domain.CodeOwnRole, "Your own role", "You can't change or remove your own role. Ask another owner.")
	case "identity.already-member":
		return problem(http.StatusConflict, domain.CodeAlreadyMember, "Already a member", "That person is already a member of this organisation.")
	case "identity.invitation-expired":
		return problem(http.StatusUnprocessableEntity, domain.CodeInvitationExpired, "Invitation expired",
			"This invitation has expired. Ask the organisation for a new one.")
	case "identity.invitation-used":
		return problem(http.StatusUnprocessableEntity, domain.CodeInvitationUsed, "Invitation already used", "This invitation has already been used.")
	case "identity.invitation-revoked":
		return problem(http.StatusUnprocessableEntity, domain.CodeInvitationRevoked, "Invitation withdrawn", "This invitation was withdrawn.")
	case "identity.invitation-email-mismatch":
		return problem(http.StatusUnprocessableEntity, domain.CodeInvitationEmail, "Different email",
			"This invitation was sent to a different email address. Sign in with that address.")
	}
	return mapErr("identity", err)
}

func (s *sellerTeamImpl) view(ctx context.Context, c *domain.Caller, sc sellerCtx) (domain.TeamView, error) {
	members, err := s.r.d.Orgs.Members(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.TeamView{}, teamErr(err)
	}
	out := domain.TeamView{Members: []domain.TeamMember{}, Roles: domain.RoleCards(sc.kind(), sc.access.Matrix), YourRoleKey: sc.org.Role,
		CanManage: domain.AtLeast(sc.access.Level("team"), domain.LevelEdit)}
	for _, m := range members {
		joined := m.JoinedAt
		mem := domain.TeamMember{UserID: m.UserID, Email: strPtr(m.Email), RoleKey: m.RoleKey, RoleLabel: domain.RoleLabel(m.RoleKey),
			Status: firstNonEmpty(m.Status, "ACTIVE"), IsYou: m.UserID == sc.member.ID,
			Name: firstNonEmpty(m.Name, strings.TrimSpace(m.FirstName+" "+m.LastName), m.Email, "Team member")}
		if !joined.IsZero() {
			mem.JoinedAt = &joined
		}
		out.Members = append(out.Members, mem)
	}
	if !out.CanManage {
		return out, nil
	}
	invitations, err := s.r.d.Orgs.Invitations(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusForbidden {
			return out, nil
		}
		return domain.TeamView{}, teamErr(err)
	}
	for _, inv := range invitations {
		if inv.Status != "" && inv.Status != "PENDING" {
			continue
		}
		out.Members = append(out.Members, invitedRow(inv))
	}
	return out, nil
}

func invitedRow(inv port.Invitation) domain.TeamMember {
	id, email := inv.ID, inv.Email
	row := domain.TeamMember{UserID: "invite:" + id, Name: email, Email: strPtr(email), RoleKey: inv.RoleKey, RoleLabel: domain.RoleLabel(inv.RoleKey),
		Status: "INVITED", InvitationID: strPtr(id)}
	if expires := inv.ExpiresAt; !expires.IsZero() {
		row.ExpiresAt = &expires
	}
	return row
}

func (s *sellerTeamImpl) Team(ctx context.Context, c *domain.Caller) (domain.TeamView, error) {
	sc, err := s.r.require(ctx, c, "", "team", domain.LevelView)
	if err != nil {
		return domain.TeamView{}, err
	}
	return s.view(ctx, c, sc)
}

func (s *sellerTeamImpl) checkRole(sc sellerCtx, roleKey string) error {
	if !domain.IsRoleOf(sc.kind(), roleKey) {
		pr := domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeInvalidRole, "Invalid role", "Choose a role of this organisation.")
		pr.Errors = map[string]string{"roleKey": "Choose a role of this organisation"}
		return pr
	}
	if isOwner(roleKey) && !isOwner(sc.org.Role) {
		return domain.NewProblem(http.StatusForbidden, domain.CodeOwnerOnly, "Owner only", "Only an owner can make someone an owner.")
	}
	return nil
}

func (s *sellerTeamImpl) Invite(ctx context.Context, c *domain.Caller, email, roleKey string) (domain.TeamMember, error) {
	addr := strings.ToLower(strings.TrimSpace(email))
	if _, err := mail.ParseAddress(addr); err != nil || !strings.Contains(addr, "@") || len(addr) > 254 {
		return domain.TeamMember{}, domain.ValidationProblem(map[string]string{"email": "Enter a valid email address"})
	}
	sc, err := s.r.require(ctx, c, "", "team", domain.LevelEdit)
	if err != nil {
		return domain.TeamMember{}, err
	}
	if err := s.checkRole(sc, roleKey); err != nil {
		return domain.TeamMember{}, err
	}
	inv, err := s.r.d.Orgs.Invite(ctx, c.BearerToken, sc.org.ID, addr, roleKey)
	if err != nil {
		return domain.TeamMember{}, teamErr(err)
	}
	inv.Email = firstNonEmpty(inv.Email, addr)
	inv.RoleKey = firstNonEmpty(inv.RoleKey, roleKey)
	return invitedRow(inv), nil
}

func (s *sellerTeamImpl) AssignRole(ctx context.Context, c *domain.Caller, userID, roleKey string) (domain.TeamView, error) {
	sc, err := s.r.require(ctx, c, "", "team", domain.LevelEdit)
	if err != nil {
		return domain.TeamView{}, err
	}
	if err := s.checkRole(sc, roleKey); err != nil {
		return domain.TeamView{}, err
	}
	if userID == sc.member.ID {
		return domain.TeamView{}, domain.NewProblem(http.StatusForbidden, domain.CodeOwnRole, "Your own role", "You can't change your own role. Ask another owner.")
	}
	if err := s.r.d.Orgs.SetMemberRole(ctx, c.BearerToken, sc.org.ID, userID, roleKey); err != nil {
		return domain.TeamView{}, teamErr(err)
	}
	return s.view(ctx, c, sc)
}

func (s *sellerTeamImpl) RemoveMember(ctx context.Context, c *domain.Caller, userID string) (domain.TeamView, error) {
	sc, err := s.r.require(ctx, c, "", "team", domain.LevelEdit)
	if err != nil {
		return domain.TeamView{}, err
	}
	if err := s.r.d.Orgs.RemoveMember(ctx, c.BearerToken, sc.org.ID, userID); err != nil {
		return domain.TeamView{}, teamErr(err)
	}
	return s.view(ctx, c, sc)
}

func (s *sellerTeamImpl) RevokeInvitation(ctx context.Context, c *domain.Caller, invitationID string) (domain.TeamView, error) {
	sc, err := s.r.require(ctx, c, "", "team", domain.LevelEdit)
	if err != nil {
		return domain.TeamView{}, err
	}
	if !uuidRe.MatchString(invitationID) {
		return domain.TeamView{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that invitation.")
	}
	if err := s.r.d.Orgs.RevokeInvitation(ctx, c.BearerToken, sc.org.ID, invitationID); err != nil {
		return domain.TeamView{}, teamErr(err)
	}
	return s.view(ctx, c, sc)
}

func checkToken(token string) error {
	if strings.TrimSpace(token) == "" || len(token) > maxInvitationToken {
		return domain.ValidationProblem(map[string]string{"token": "This invitation link is incomplete. Open it again from the email."})
	}
	return nil
}

func (s *sellerTeamImpl) LookupInvitation(ctx context.Context, token string) (domain.InvitationPreview, error) {
	if err := checkToken(token); err != nil {
		return domain.InvitationPreview{}, err
	}
	p, err := s.r.d.Orgs.LookupInvitation(ctx, strings.TrimSpace(token))
	if err != nil {
		if up, ok := port.AsUpstreamProblem(err); ok && up.Status == http.StatusNotFound {
			return domain.InvitationPreview{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Invitation not found",
				"This invitation link isn't valid. Ask the organisation to send a new one.").WithCause(err)
		}
		return domain.InvitationPreview{}, teamErr(err)
	}
	return domain.InvitationPreview{OrgName: p.OrgName, OrgKind: p.OrgKind, Email: p.Email, RoleKey: p.RoleKey, RoleLabel: domain.RoleLabel(p.RoleKey),
		Status: p.Status, ExpiresAt: p.ExpiresAt}, nil
}

func (s *sellerTeamImpl) AcceptInvitation(ctx context.Context, c *domain.Caller, token string) (domain.AcceptedInvitation, error) {
	if c == nil || c.BearerToken == "" {
		return domain.AcceptedInvitation{}, domain.Unauthenticated()
	}
	if err := checkToken(token); err != nil {
		return domain.AcceptedInvitation{}, err
	}
	a, err := s.r.d.Orgs.AcceptInvitation(ctx, c.BearerToken, strings.TrimSpace(token))
	if err != nil {
		return domain.AcceptedInvitation{}, teamErr(err)
	}
	// Best effort: open the organisation just joined; the person can still switch from the org menu.
	if a.OrgID != "" {
		_, _ = s.r.d.Orgs.SelectOrg(ctx, c.BearerToken, a.OrgID)
	}
	return domain.AcceptedInvitation{OrgID: a.OrgID, OrgName: a.OrgName, OrgKind: a.OrgKind, RoleKey: a.RoleKey, RoleLabel: domain.RoleLabel(a.RoleKey),
		AlreadyMember: a.AlreadyMember}, nil
}

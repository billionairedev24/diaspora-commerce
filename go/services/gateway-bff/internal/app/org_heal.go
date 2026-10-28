package app

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// TokenRefresher rotates a web session's tokens whose claims are stale (SessionService implements it).
type TokenRefresher interface {
	RefreshNow(ctx context.Context, c *domain.Caller) bool
}

// OrgSyncer asks identity to repair its copy of an organisation from the seller service (port.OrgAccessClient).
type OrgSyncer interface {
	SyncOrg(ctx context.Context, bearer, orgID string) (port.OrgSync, error)
}

// orgHealSyncTimeout bounds the repair a request makes on the way (identity reads the seller service).
const orgHealSyncTimeout = 4 * time.Second

// healOrg repairs identity's copy of an organisation the seller service approved before identity knew (a missed or
// late seller.org.approved): identity syncs it from the seller service, then the session's tokens are rotated so the
// next calls carry the organisation and the owner role, without signing in again. True when identity now has the
// organisation approved for the caller and the tokens were refreshed. Best effort: failures are logged, the request
// goes on with what it has (identity's reconcile job repairs it within minutes anyway).
func healOrg(ctx context.Context, orgs OrgSyncer, sessions TokenRefresher, log *slog.Logger, c *domain.Caller, orgID string) bool {
	if orgs == nil || orgID == "" || c == nil || c.BearerToken == "" {
		return false
	}
	sctx, cancel := context.WithTimeout(ctx, orgHealSyncTimeout)
	defer cancel()
	res, err := orgs.SyncOrg(sctx, c.BearerToken, orgID)
	if err != nil {
		if log != nil {
			log.WarnContext(ctx, "identity could not sync the approved organisation", "org", orgID, "error", err)
		}
		return false
	}
	if !strings.EqualFold(res.Status, "APPROVED") && !strings.EqualFold(res.Status, "SUSPENDED") {
		return false
	}
	if log != nil && res.Repaired {
		log.InfoContext(ctx, "identity's copy of the organisation repaired from the seller service", "org", orgID)
	}
	if sessions == nil {
		return true
	}
	// Claims are recomputed on refresh: the new access token carries the organisation (app bearers refresh themselves).
	return sessions.RefreshNow(ctx, c) || c.App
}

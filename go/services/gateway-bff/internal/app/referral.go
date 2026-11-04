package app

import (
	"context"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ReferralService is the shopper's "Refer a friend" page (S322, ADR 0006): the code and share link, the offer, the
// friends who signed up with the code and what they paid. The rewards are AfroTransact's to pay and are granted by
// payments through the credit ledger; there is nothing here to claim or withdraw.
type ReferralService interface {
	// Account is the page: the code (created on first use), the link, the offer, the friends and the earnings.
	Account(ctx context.Context, c *domain.Caller) (domain.AccountReferral, error)
}

type referralServiceImpl struct {
	referral    port.ReferralClient
	storefront  string
	friendsPage int
}

var _ ReferralService = (*referralServiceImpl)(nil)

// NewReferralService wires the referral page; storefront is the storefront's origin, where share links open.
func NewReferralService(referral port.ReferralClient, storefront string) ReferralService {
	return &referralServiceImpl{referral: referral, storefront: strings.TrimRight(storefront, "/"), friendsPage: 100}
}

func (s *referralServiceImpl) Account(ctx context.Context, c *domain.Caller) (domain.AccountReferral, error) {
	if err := requireCustomer(c); err != nil {
		return domain.AccountReferral{}, err
	}
	o, err := s.referral.Overview(ctx, c.BearerToken)
	if err != nil {
		return domain.AccountReferral{}, mapErr("payments", err)
	}
	friends, err := s.referral.Friends(ctx, c.BearerToken, s.friendsPage)
	if err != nil {
		return domain.AccountReferral{}, mapErr("payments", err)
	}
	out := domain.AccountReferral{
		Code: o.Code, DisplayCode: o.DisplayCode, ShareURL: s.storefront + "/r/" + url.PathEscape(o.Code),
		Program: domain.ReferralProgram{Enabled: o.Program.Enabled, ReferrerReward: toRewardTerms(o.Program.ReferrerReward),
			ReferredReward: toRewardTerms(o.Program.ReferredReward), MinOrderMinor: o.Program.MinOrderMinor,
			ReturnWindowDays: o.Program.ReturnWindowDays, ValidityDays: o.Program.ValidityDays,
			Currency: firstNonEmpty(o.Program.Currency, domain.Currency)},
		Earnings: domain.ReferralEarnings{Invited: o.Earnings.Invited, Open: o.Earnings.Open, Rewarded: o.Earnings.Rewarded,
			EarnedMinor: o.Earnings.EarnedMinor, MembershipDays: o.Earnings.MembershipDays, PendingMinor: o.Earnings.PendingMinor},
		Friends: []domain.ReferralFriend{},
	}
	for _, f := range friends {
		out.Friends = append(out.Friends, toReferralFriend(f))
	}
	if o.AsFriend != nil {
		own := toReferralFriend(*o.AsFriend)
		out.AsFriend = &own
	}
	return out, nil
}

func toRewardTerms(r port.UpReferralReward) domain.ReferralRewardTerms {
	return domain.ReferralRewardTerms{Type: r.Type, CreditMinor: r.CreditMinor, MembershipDays: r.MembershipDays}
}

func toReferralFriend(f port.UpMyReferral) domain.ReferralFriend {
	out := domain.ReferralFriend{ID: f.ID, FriendName: f.FriendName, Status: f.Status,
		SignedUpAt: f.SignedUpAt.UTC().Format(time.RFC3339), ExpiresAt: f.ExpiresAt.UTC().Format(time.RFC3339)}
	if f.EligibleAt != nil {
		out.EligibleAt = ptr(f.EligibleAt.UTC().Format(time.RFC3339))
	}
	if f.Reward != nil {
		r := domain.ReferralFriendReward{Terms: toRewardTerms(f.Reward.Terms), Granted: f.Reward.Granted}
		if f.Reward.GrantedAt != nil {
			r.GrantedAt = ptr(f.Reward.GrantedAt.UTC().Format(time.RFC3339))
		}
		out.Reward = &r
	}
	return out
}

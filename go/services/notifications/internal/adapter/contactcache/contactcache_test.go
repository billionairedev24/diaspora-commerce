package contactcache_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/contactcache"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type countingResolver struct {
	calls int
	err   error
}

func (c *countingResolver) Contact(_ context.Context, id string) (port.Contact, error) {
	c.calls++
	if c.err != nil {
		return port.Contact{}, c.err
	}
	if id == "ghost" {
		return port.Contact{}, domain.ErrRecipientNotFound
	}
	return port.Contact{UserID: id, Email: id + "@example.com"}, nil
}

func (c *countingResolver) OrgOwners(_ context.Context, _ string) ([]string, error) {
	c.calls++
	return []string{"u-1"}, c.err
}

func TestCacheHonoursTTLAndNeverCachesErrors(t *testing.T) {
	now := time.Unix(0, 0)
	inner := &countingResolver{}
	c := contactcache.NewWithClock(inner, contactcache.Options{TTL: time.Minute, NotFoundTTL: 10 * time.Second, MaxEntries: 2},
		func() time.Time { return now })
	ctx := context.Background()
	for range 3 {
		if got, err := c.Contact(ctx, "u-1"); err != nil || got.Email != "u-1@example.com" {
			t.Fatal(got, err)
		}
	}
	if inner.calls != 1 {
		t.Fatalf("cached lookups: %d calls", inner.calls)
	}
	for range 2 {
		if _, err := c.Contact(ctx, "ghost"); !errors.Is(err, domain.ErrRecipientNotFound) {
			t.Fatal(err)
		}
	}
	if inner.calls != 2 {
		t.Fatalf("not-found must be cached too: %d", inner.calls)
	}
	now = now.Add(11 * time.Second) // not-found expired, contact still fresh
	_, _ = c.Contact(ctx, "ghost")
	_, _ = c.Contact(ctx, "u-1")
	if inner.calls != 3 {
		t.Fatalf("after not-found TTL: %d", inner.calls)
	}
	now = now.Add(time.Minute)
	inner.err = errors.New("down")
	if _, err := c.Contact(ctx, "u-1"); err == nil {
		t.Fatal("expired entry must be reloaded and errors surfaced")
	}
	inner.err = nil
	if _, err := c.Contact(ctx, "u-1"); err != nil || inner.calls != 5 {
		t.Fatalf("errors are not cached: %v %d", err, inner.calls)
	}
	// Owners are cached separately; the cap evicts without failing.
	_, _ = c.OrgOwners(ctx, "org_1")
	_, _ = c.OrgOwners(ctx, "org_1")
	_, _ = c.OrgOwners(ctx, "org_2")
	_, _ = c.OrgOwners(ctx, "org_3")
	if inner.calls != 8 {
		t.Fatalf("owners: %d", inner.calls)
	}
}

func TestForgetDropsOnlyThatContact(t *testing.T) {
	inner := &countingResolver{}
	c := contactcache.New(inner, contactcache.Options{TTL: time.Hour})
	ctx := context.Background()
	_, _ = c.Contact(ctx, "u-1")
	_, _ = c.Contact(ctx, "u-2")
	_, _ = c.OrgOwners(ctx, "org_1")
	c.Forget("u-1")
	c.Forget("unknown")
	_, _ = c.Contact(ctx, "u-1")
	_, _ = c.Contact(ctx, "u-2")
	_, _ = c.OrgOwners(ctx, "org_1")
	if inner.calls != 4 {
		t.Fatalf("only u-1 is looked up again: %d calls", inner.calls)
	}
}

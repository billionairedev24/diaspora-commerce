package app_test

import (
	"context"
	"io"
	"log/slog"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

func quietLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// fakeSms is a scripted SmsSender.
type fakeSms struct {
	name  string
	errs  []error // returned in order; nil = success
	delay time.Duration
	mu    sync.Mutex
	calls int
}

func (f *fakeSms) Name() string { return f.name }

func (f *fakeSms) Send(ctx context.Context, _ domain.SmsMessage) (domain.Receipt, error) {
	f.mu.Lock()
	i := f.calls
	f.calls++
	f.mu.Unlock()
	if f.delay > 0 {
		select {
		case <-time.After(f.delay):
		case <-ctx.Done():
			return domain.Receipt{}, ctx.Err()
		}
	}
	if i < len(f.errs) && f.errs[i] != nil {
		return domain.Receipt{}, f.errs[i]
	}
	return domain.Receipt{ProviderMessageID: f.name + "-msg"}, nil
}

func (f *fakeSms) Calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

type fakePush struct {
	name     string
	platform domain.PushPlatform
	calls    int
}

func (f *fakePush) Name() string                       { return f.name }
func (f *fakePush) Accepts(p domain.PushPlatform) bool { return p == f.platform }
func (f *fakePush) Send(context.Context, domain.PushMessage) (domain.Receipt, error) {
	f.calls++
	return domain.Receipt{ProviderMessageID: f.name}, nil
}

// fakeTemplates serves templates from memory.
type fakeTemplates struct{ items []domain.Template }

func (f *fakeTemplates) Find(_ context.Context, key string, locales []string, ch domain.Channel, version int) (domain.Template, error) {
	for _, l := range locales {
		var best *domain.Template
		for i := range f.items {
			t := &f.items[i]
			if t.Key == key && t.Locale == l && t.Channel == ch && (version == 0 || t.Version == version) {
				if best == nil || t.Version > best.Version {
					best = t
				}
			}
		}
		if best != nil {
			return *best, nil
		}
	}
	return domain.Template{}, domain.ErrTemplateNotFound
}

func (f *fakeTemplates) Create(_ context.Context, t domain.Template) (domain.Template, error) {
	f.items = append(f.items, t)
	return t, nil
}

func (f *fakeTemplates) List(context.Context, string) ([]domain.Template, error) { return f.items, nil }

// fakeStore records what the dispatcher and notifier do.
type fakeStore struct {
	port.NotificationStore
	mu       sync.Mutex
	enqueued []domain.Notification
	inbox    map[string]bool
	claim    []domain.Notification
	sent     map[string]domain.Receipt
	retries  map[string]int
	final    map[string]domain.Status
	dead     map[string]bool
	resched  map[string]time.Time
}

func newFakeStore() *fakeStore {
	return &fakeStore{inbox: map[string]bool{}, sent: map[string]domain.Receipt{}, retries: map[string]int{},
		final: map[string]domain.Status{}, dead: map[string]bool{}, resched: map[string]time.Time{}}
}

func (f *fakeStore) Enqueue(_ context.Context, ns []domain.Notification) ([]domain.Notification, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.enqueued = append(f.enqueued, ns...)
	return ns, nil
}

func (f *fakeStore) EnqueueForEvent(_ context.Context, consumer, id string, ns []domain.Notification) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.inbox[consumer+id] {
		return false, nil
	}
	f.inbox[consumer+id] = true
	f.enqueued = append(f.enqueued, ns...)
	return true, nil
}

func (f *fakeStore) Claim(context.Context, int, time.Duration) ([]domain.Notification, error) {
	out := f.claim
	f.claim = nil
	return out, nil
}

func (f *fakeStore) MarkSent(_ context.Context, id string, r domain.Receipt) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent[id] = r
	return nil
}

func (f *fakeStore) Reschedule(_ context.Context, id string, at time.Time, _ string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.resched[id] = at
	return nil
}

func (f *fakeStore) MarkRetry(_ context.Context, id string, attempts int, _ time.Time, _ string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.retries[id] = attempts
	return nil
}

func (f *fakeStore) MarkFinal(_ context.Context, id string, st domain.Status, _ int, _ string, dl bool) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.final[id], f.dead[id] = st, dl
	return nil
}

type fakeChecker struct{ decision map[string]domain.Decision }

func (f fakeChecker) Check(_ context.Context, q port.ConsentQuery) (domain.Decision, error) {
	if d, ok := f.decision[q.Address]; ok {
		return d, nil
	}
	return domain.Decision{Verdict: domain.VerdictAllow}, nil
}

// fakeConsent records ConsentStore calls.
type fakeConsent struct {
	port.ConsentStore
	optOut     map[string]string
	optIn      map[string]string
	suppressed map[string]string
}

func newFakeConsent() *fakeConsent {
	return &fakeConsent{optOut: map[string]string{}, optIn: map[string]string{}, suppressed: map[string]string{}}
}

func (f *fakeConsent) OptOutSms(_ context.Context, phone, via string, _ time.Time) error {
	f.optOut[phone] = via
	return nil
}

func (f *fakeConsent) OptInSms(_ context.Context, phone, source, _ string, _ time.Time) error {
	f.optIn[phone] = source
	return nil
}

func (f *fakeConsent) Suppress(_ context.Context, address, reason string, _ time.Time) error {
	f.suppressed[address] = reason
	return nil
}

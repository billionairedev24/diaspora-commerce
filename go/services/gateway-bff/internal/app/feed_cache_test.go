package app

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestFeedCacheServesTheSameFeedForSixtySecondsPerKey(t *testing.T) {
	c := newFeedCache[int]()
	var calls atomic.Int32
	compute := func() int { return int(calls.Add(1)) }
	t0 := time.Date(2026, 10, 24, 10, 0, 0, 0, time.UTC)

	if got := c.do("org-a|u1|30", t0, compute); got != 1 {
		t.Fatalf("first = %d", got)
	}
	if got := c.do("org-a|u1|30", t0.Add(59*time.Second), compute); got != 1 {
		t.Fatalf("within 60s = %d, want the cached 1", got)
	}
	if got := c.do("org-a|u2|30", t0.Add(time.Second), compute); got != 2 {
		t.Fatalf("another caller must not share the feed: %d", got)
	}
	if got := c.do("org-a|u1|30", t0.Add(61*time.Second), compute); got != 3 {
		t.Fatalf("after 60s = %d, want a fresh 3", got)
	}
}

func TestFeedCacheComputesOnceForConcurrentRequests(t *testing.T) {
	c := newFeedCache[int]()
	var calls atomic.Int32
	release := make(chan struct{})
	now := time.Date(2026, 10, 24, 10, 0, 0, 0, time.UTC)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c.do("k", now, func() int { calls.Add(1); <-release; return 7 })
		}()
	}
	time.Sleep(50 * time.Millisecond)
	close(release)
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatalf("computed %d times, want once", calls.Load())
	}
}

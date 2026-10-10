package memcache_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/memcache"
)

func TestTTLAndBound(t *testing.T) {
	now := time.Unix(0, 0)
	c := memcache.New(16, func() time.Time { return now })
	c.Set("a", []byte("1"), time.Second)
	c.Set("skip", []byte("x"), 0)
	if v, ok := c.Get("a"); !ok || string(v) != "1" {
		t.Fatal("miss")
	}
	if _, ok := c.Get("skip"); ok {
		t.Fatal("ttl 0 must not cache")
	}
	now = now.Add(2 * time.Second)
	if _, ok := c.Get("a"); ok {
		t.Fatal("expired entry served")
	}
	for i := range 100 {
		c.Set(string(rune('a'+i)), []byte("v"), time.Hour)
	}
	n := 0
	for i := range 100 {
		if _, ok := c.Get(string(rune('a' + i))); ok {
			n++
		}
	}
	if n > 16 {
		t.Fatalf("%d entries kept; bound is 16", n)
	}
}

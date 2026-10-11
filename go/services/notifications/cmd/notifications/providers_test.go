package main

import "testing"

func TestReachableFromInternet(t *testing.T) {
	for raw, want := range map[string]bool{
		"":                                      false,
		"http://localhost:8208":                 false,
		"http://127.0.0.1:8208":                 false,
		"http://192.168.1.20:8208":              false,
		"http://notifications.local":            false,
		"https://quiet-river.trycloudflare.com": true,
		"https://api.example.org/notifications": true,
	} {
		if got := reachableFromInternet(raw); got != want {
			t.Errorf("reachableFromInternet(%q) = %v, want %v", raw, got, want)
		}
	}
}

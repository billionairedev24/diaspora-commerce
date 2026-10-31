package domain

import "testing"

func TestDeviceLabel(t *testing.T) {
	cases := []struct{ ua, want string }{
		{"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Chrome on macOS"},
		{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0", "Edge on Windows"},
		{"Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1", "Safari on iOS"},
		{"Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/124.0.6367.88 Mobile/15E148 Safari/604.1", "Chrome on iOS"},
		{"Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:125.0) Gecko/20100101 Firefox/125.0", "Firefox on macOS"},
		{"Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36", "Chrome on Android"},
		{"Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/24.0 Chrome/117.0.0.0 Mobile Safari/537.36", "Samsung Internet on Android"},
		{"Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0", "Firefox on Linux"},
		{"Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Chrome on ChromeOS"},
		{"okhttp/4.12.0", "Android app"},
		{"AfroTransact/1.0 CFNetwork/1494.0.7 Darwin/23.4.0", "iOS app"},
		{"Java-http-client/25", ""},
		{"", ""},
		{"   ", ""},
	}
	for _, c := range cases {
		if got := DeviceLabel(c.ua); got != c.want {
			t.Errorf("DeviceLabel(%q) = %q, want %q", c.ua, got, c.want)
		}
	}
}

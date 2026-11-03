package domain

import "strings"

// DeviceLabel turns a User-Agent into what the staff console's sessions table shows: "Chrome on macOS", "Safari on
// iOS", "Firefox on Windows". A missing or unrecognised agent gives "" (the table shows a dash); an agent with only a
// browser or only a system gives that part alone. It is a display label, not a security signal.
func DeviceLabel(userAgent string) string {
	ua := strings.TrimSpace(userAgent)
	if ua == "" {
		return ""
	}
	browser, system := browserOf(ua), systemOf(ua)
	switch {
	case strings.HasSuffix(browser, " app"):
		return browser // the native apps name their own system
	case browser != "" && system != "":
		return browser + " on " + system
	case browser != "":
		return browser
	default:
		return system
	}
}

// browserOf picks the most specific browser token first: Edge, Opera and Samsung Internet also say Chrome, and Chrome
// also says Safari.
func browserOf(ua string) string {
	l := strings.ToLower(ua)
	for _, b := range []struct{ token, name string }{
		{"edg/", "Edge"}, {"edga/", "Edge"}, {"edgios/", "Edge"}, {"opr/", "Opera"}, {"opera", "Opera"},
		{"samsungbrowser/", "Samsung Internet"}, {"firefox/", "Firefox"}, {"fxios/", "Firefox"},
		{"crios/", "Chrome"}, {"chrome/", "Chrome"}, {"okhttp/", "Android app"}, {"cfnetwork/", "iOS app"},
		{"safari/", "Safari"}, {"curl/", "curl"},
	} {
		if strings.Contains(l, b.token) {
			return b.name
		}
	}
	return ""
}

// systemOf checks mobile systems before desktop ones: an iPhone says "like Mac OS X", Android says "Linux".
func systemOf(ua string) string {
	l := strings.ToLower(ua)
	for _, s := range []struct{ token, name string }{
		{"iphone", "iOS"}, {"ipad", "iPadOS"}, {"ipod", "iOS"}, {"android", "Android"}, {"cros", "ChromeOS"},
		{"windows", "Windows"}, {"mac os x", "macOS"}, {"macintosh", "macOS"}, {"darwin", "iOS"}, {"linux", "Linux"},
	} {
		if strings.Contains(l, s.token) {
			return s.name
		}
	}
	return ""
}

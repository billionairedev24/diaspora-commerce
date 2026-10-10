package domain

import "math"

// ThemeInput is what a seller picks in the theme studio.
type ThemeInput struct {
	Primary string `json:"primary"`
	Accent  string `json:"accent"`
	Surface string `json:"surface,omitempty"`
	Text    string `json:"text,omitempty"`
}

// Check is one contrast check.
type Check struct {
	ID       string  `json:"id"`
	Label    string  `json:"label"`
	FG       string  `json:"fg"`
	BG       string  `json:"bg"`
	Need     float64 `json:"need"`
	Ratio    float64 `json:"ratio"`
	Pass     bool    `json:"pass"`
	Fix      string  `json:"fix,omitempty"`
	Blocking bool    `json:"blocking"`
}

// Vetting is the result of checking a theme.
type Vetting struct {
	OK     bool              `json:"ok"`
	Checks []Check           `json:"checks"`
	Ramp   []RampStep        `json:"ramp"`
	Tokens map[string]string `json:"tokens"`
}

// Vet runs the WCAG 2.2 AA checks. Body text and button labels are blocking; colours used as text or edges get
// an auto-fix suggestion. Checkout, prices and the header always stay platform-styled.
func Vet(in ThemeInput) (Vetting, error) {
	var err error
	if in.Primary, err = NormalizeHex(in.Primary); err != nil {
		return Vetting{}, err
	}
	if in.Accent, err = NormalizeHex(in.Accent); err != nil {
		return Vetting{}, err
	}
	if in.Surface == "" {
		in.Surface = "#FFFFFF"
	}
	if in.Text == "" {
		in.Text = "#191816"
	}
	if in.Surface, err = NormalizeHex(in.Surface); err != nil {
		return Vetting{}, err
	}
	if in.Text, err = NormalizeHex(in.Text); err != nil {
		return Vetting{}, err
	}
	onP, onA := OnColor(in.Primary), OnColor(in.Accent)
	ink, accentInk := Fit(in.Primary, in.Surface, 4.5), Fit(in.Accent, in.Surface, 4.5)
	ramp := Ramp(in.Primary)
	checks := []Check{
		{ID: "body", Label: "Body text on store surface", FG: in.Text, BG: in.Surface, Need: 4.5, Blocking: true},
		{ID: "onPrimary", Label: "Button label on primary", FG: onP, BG: in.Primary, Need: 4.5, Blocking: true},
		{ID: "onAccent", Label: "Button label on accent", FG: onA, BG: in.Accent, Need: 4.5, Blocking: true},
		{ID: "primaryUi", Label: "Primary as UI edge on surface (non-text)", FG: in.Primary, BG: in.Surface, Need: 3,
			Fix: Fit(in.Primary, in.Surface, 3)},
		{ID: "primaryText", Label: "Primary used as text / links", FG: in.Primary, BG: in.Surface, Need: 4.5, Fix: ink},
		{ID: "accentText", Label: "Accent used as text", FG: in.Accent, BG: in.Surface, Need: 4.5, Fix: accentInk},
	}
	ok := true
	for i := range checks {
		r := Contrast(checks[i].FG, checks[i].BG)
		checks[i].Ratio = math.Round(r*100) / 100
		checks[i].Pass = r >= checks[i].Need
		if checks[i].Pass {
			checks[i].Fix = ""
		}
		if checks[i].Blocking && !checks[i].Pass {
			ok = false
		}
	}
	return Vetting{OK: ok, Checks: checks, Ramp: ramp, Tokens: map[string]string{
		"--store-primary": in.Primary, "--store-primary-hover": ramp[6].Hex, "--store-on-primary": onP,
		"--store-accent": in.Accent, "--store-on-accent": onA, "--store-ink": ink, "--store-tint": ramp[0].Hex,
		"--store-surface": in.Surface, "--store-text": in.Text,
	}}, nil
}

package domain

// HomeModuleEvent is an impression or a click on an organic home module, counted in aggregate (no shopper identity).
type HomeModuleEvent struct {
	Module   string
	Event    string // impression | click
	Position int
	Variant  string
	Target   string // product | department | community | service | other
	Locale   string
}

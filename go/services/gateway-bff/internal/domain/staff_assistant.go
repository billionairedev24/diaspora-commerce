package domain

// Lola in the staff console ("admin Lola"): one level for every staff member, read-only, with the member's own
// permissions. She looks things up and points at the record to open; she never changes anything.
const (
	// StaffAssistantDailyMessages is how many messages a staff member can send a day (UTC).
	StaffAssistantDailyMessages = 200
	// StaffAssistantMaxToolRounds bounds how many times she may look things up for one question.
	StaffAssistantMaxToolRounds = 6
	// StaffAssistantHistory is how many earlier messages she remembers.
	StaffAssistantHistory = 12
)

// Staff assistant tool names.
const (
	StaffToolOverview      = "staff_overview"
	StaffToolSearchRecords = "search_records"
	StaffToolGetRecord     = "get_record"
	StaffToolSellerPayouts = "seller_payouts"
	StaffToolAccountHealth = "seller_account_health"
	StaffToolSellerBilling = "seller_billing"
	StaffToolStripeLookup  = "stripe_lookup"
	StaffToolOpenRecord    = "open_record"
)

// StaffAssistantTools are the tools she can use, in the order they are offered.
var StaffAssistantTools = []string{StaffToolOverview, StaffToolSearchRecords, StaffToolGetRecord, StaffToolSellerPayouts,
	StaffToolAccountHealth, StaffToolSellerBilling, StaffToolStripeLookup, StaffToolOpenRecord}

// StaffAssistantStatus is GET /v1/staff/assistant.
type StaffAssistantStatus struct {
	DailyMessages int      `json:"dailyMessages"`
	Used          int      `json:"used"`
	Remaining     int      `json:"remaining"`
	Tools         []string `json:"tools"`
}

// StaffAssistantLink is a record she points at: the console opens it when the member taps the button.
type StaffAssistantLink struct {
	Label    string `json:"label"`
	Resource string `json:"resource"`
	ID       string `json:"id"`
}

// StaffAssistantReply is POST /v1/staff/assistant/chat.
type StaffAssistantReply struct {
	Answer string               `json:"answer"`
	Steps  []AssistantStep      `json:"steps"`
	Links  []StaffAssistantLink `json:"links"`
	Status StaffAssistantStatus `json:"status"`
}

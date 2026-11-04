package domain

import "time"

// Department requests and department overrides in the staff console (contracts/openapi/gateway-bff-staff.yaml, tag
// Department requests): which catalog departments a seller organisation may sell in. Sellers ask for another one in
// Seller Center; staff approve or reject the request, or replace an organisation's departments with a reason. The seller
// service owns the data and the rules (docs/engineering/seller-catalog-scope.md).

// DepartmentRequestsModule is the staff console module key of Department requests.
const DepartmentRequestsModule = "deptRequests"

// Seller service permissions behind Department requests (no new ones: the seller service already checks these).
const (
	// PermApplicationsReview reads the department request queue (and is the first step of an application review).
	PermApplicationsReview = "applications:review"
	// PermApplicationsApprove decides a request and overrides an organisation's departments.
	PermApplicationsApprove = "applications:approve"
)

// Status filters of the department request table.
const (
	DeptRequestPending  = "PENDING"
	DeptRequestApproved = "APPROVED"
	DeptRequestRejected = "REJECTED"
	// DeptRequestDecided is both APPROVED and REJECTED (the "Decided" tab).
	DeptRequestDecided = "DECIDED"
)

// StaffDepartment is a catalog department by name; code is the catalog node code (what the API sends, never shown).
type StaffDepartment struct {
	Code string `json:"code"`
	Name string `json:"name"`
	// Requires lists the evidence a seller must have on file to sell here (FOOD_PERMIT, BEAUTY_LABELLING).
	Requires []string `json:"requires"`
}

// StaffCompliancePrompt is one thing a department asks the seller to have on file, and whether the organisation's
// compliance data already satisfies it.
type StaffCompliancePrompt struct {
	// Requirement is FOOD_PERMIT or BEAUTY_LABELLING.
	Requirement string `json:"requirement"`
	// Label is the plain-language English text ("Food permit"); the console translates by requirement.
	Label string `json:"label"`
	// Satisfied is null when it was not checked (a request that was rejected).
	Satisfied *bool `json:"satisfied"`
}

// StaffDepartmentRequestRow is one row of the department request table.
type StaffDepartmentRequestRow struct {
	ID string `json:"id"`
	// OrgID only links to the organisation; the console shows OrgName.
	OrgID           string     `json:"orgId"`
	OrgName         string     `json:"orgName"`
	DepartmentName  string     `json:"departmentName"`
	RequestedByName string     `json:"requestedByName"`
	RequestedAt     time.Time  `json:"requestedAt"`
	Status          string     `json:"status"`
	DecidedByName   *string    `json:"decidedByName"`
	DecidedAt       *time.Time `json:"decidedAt"`
}

// StaffDepartmentRequestPage is GET /v1/staff/department-requests.
type StaffDepartmentRequestPage struct {
	Items []StaffDepartmentRequestRow `json:"items"`
	Total int                         `json:"total"`
	Page  int                         `json:"page"`
	Size  int                         `json:"size"`
	// Pending counts the requests waiting for a decision whatever the filters (the tab and nav badge).
	Pending int `json:"pending"`
	// Truncated says the service holds more requests than it lists (it lists at most 200 per status).
	Truncated bool `json:"truncated"`
	// CanDecide says whether the staff member may approve and reject.
	CanDecide bool `json:"canDecide"`
}

// StaffDepartmentRequest is GET /v1/staff/department-requests/{id}.
type StaffDepartmentRequest struct {
	StaffDepartmentRequestRow
	// Note is what the seller wrote when asking.
	Note *string `json:"note"`
	// DecisionNote is the approver's note; for a rejection it is the reason the seller reads.
	DecisionNote *string `json:"decisionNote"`
	// Prompts are the department's compliance prompts with what the organisation's data says.
	Prompts []StaffCompliancePrompt `json:"prompts"`
	// CurrentDepartments are the departments the organisation may sell in now, by name.
	CurrentDepartments []StaffDepartment `json:"currentDepartments"`
	// Exempt is AfroTransact Retail, which sells in every department.
	Exempt    bool `json:"exempt"`
	CanDecide bool `json:"canDecide"`
}

// StaffDepartmentRequestDecision is the body of approve and reject.
type StaffDepartmentRequestDecision struct {
	Note string `json:"note"`
}

// StaffOrgDepartments is GET and PUT /v1/staff/organisations/{orgId}/departments.
type StaffOrgDepartments struct {
	OrgName string `json:"orgName"`
	// Exempt is AfroTransact Retail: it sells in every department and cannot be overridden.
	Exempt      bool              `json:"exempt"`
	Departments []StaffDepartment `json:"departments"`
	// Catalog is every department of the catalog (what an override can choose from).
	Catalog []StaffDepartment `json:"catalog"`
	CanEdit bool              `json:"canEdit"`
	OrgID   string            `json:"orgId"`
	// PendingRequests counts the organisation's requests waiting for a decision.
	PendingRequests int `json:"pendingRequests"`
}

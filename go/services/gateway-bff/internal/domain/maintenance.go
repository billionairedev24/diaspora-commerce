package domain

import "time"

// Maintenance scopes: SHOPPERS pauses the storefront and apps (sellers keep working); ALL pauses Seller Center too.
// Staff and sign-in always work, so the people who switch maintenance off can still reach the console.
const (
	MaintenanceShoppers = "SHOPPERS"
	MaintenanceAll      = "ALL"
)

// Maintenance is the platform's maintenance switch and what customers are told.
type Maintenance struct {
	Enabled        bool                `json:"enabled"`
	Scope          string              `json:"scope"`
	Title          string              `json:"title"`
	Message        string              `json:"message"`
	ExpectedBackAt *time.Time          `json:"expectedBackAt,omitempty"`
	UpdatedAt      time.Time           `json:"updatedAt"`
	UpdatedBy      string              `json:"updatedBy"`
	History        []MaintenanceChange `json:"history,omitempty"`
}

// MaintenanceChange is one audited switch of maintenance mode (newest first in Maintenance.History).
type MaintenanceChange struct {
	At             time.Time  `json:"at"`
	By             string     `json:"by"`
	Enabled        bool       `json:"enabled"`
	Scope          string     `json:"scope"`
	Message        string     `json:"message"`
	ExpectedBackAt *time.Time `json:"expectedBackAt,omitempty"`
}

// MaintenanceStatus is GET /v1/maintenance: what the web apps and mobile apps need to show the maintenance page.
type MaintenanceStatus struct {
	Active         bool       `json:"active"`
	Scope          string     `json:"scope,omitempty"`
	Title          string     `json:"title,omitempty"`
	Message        string     `json:"message,omitempty"`
	ExpectedBackAt *time.Time `json:"expectedBackAt,omitempty"`
}

// Blocks reports whether the switch pauses a request for the seller (true) or shopper (false) side.
func (m Maintenance) Blocks(seller bool) bool {
	if !m.Enabled {
		return false
	}
	return !seller || m.Scope == MaintenanceAll
}

// Status is the public view.
func (m Maintenance) Status() MaintenanceStatus {
	if !m.Enabled {
		return MaintenanceStatus{}
	}
	return MaintenanceStatus{Active: true, Scope: m.Scope, Title: m.Title, Message: m.Message, ExpectedBackAt: m.ExpectedBackAt}
}

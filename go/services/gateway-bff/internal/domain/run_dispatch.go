package domain

import "time"

// Delivery runs in the staff console's Dispatch module and the seller's pick lists (S264). A run is a closed pre-order
// window's paid orders delivered in one trip: fulfilment plans its routes (seller pickups before drops), delivery-runs
// follows it from the drivers' shipment events. These are the BFF's own shapes (contracts/openapi/gateway-bff-staff.yaml
// and gateway-bff-seller.yaml), independent of the services' DTOs.

// RunPickupPoint is where a pickup-point run hands every order over.
type RunPickupPoint struct {
	Name       string `json:"name"`
	Line1      string `json:"line1"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Hours      string `json:"hours"`
	Notes      string `json:"notes"`
}

// RunDispatchProgress is what has happened on the run's routes (a bag is one seller's package of an order).
type RunDispatchProgress struct {
	Orders        int `json:"orders"`
	Bags          int `json:"bags"`
	PickupStops   int `json:"pickupStops"`
	PickupsDone   int `json:"pickupsDone"`
	BagsCollected int `json:"bagsCollected"`
	DropStops     int `json:"dropStops"`
	DropsDone     int `json:"dropsDone"`
	DropsFailed   int `json:"dropsFailed"`
	BagsDelivered int `json:"bagsDelivered"`
	Unrouted      int `json:"unrouted"`
}

// RunOrderProgress counts the run's orders per stage, from orders-service shipment events (delivery-runs).
type RunOrderProgress struct {
	Orders          int        `json:"orders"`
	Pending         int        `json:"pending"`
	Collected       int        `json:"collected"`
	OutForDelivery  int        `json:"outForDelivery"`
	Failed          int        `json:"failed"`
	Delivered       int        `json:"delivered"`
	Returned        int        `json:"returned"`
	LastDeliveredAt *time.Time `json:"lastDeliveredAt"`
}

// RunDispatchStop is one stop of a run's route.
type RunDispatchStop struct {
	ID               string  `json:"id"`
	Sequence         int     `json:"sequence"`
	Kind             string  `json:"kind"`
	Name             string  `json:"name"`
	Status           string  `json:"status"`
	OrderNumber      *string `json:"orderNumber"`
	AddressLine      *string `json:"addressLine"`
	HandoverRequired bool    `json:"handoverRequired"`
	Note             *string `json:"note"`
}

// RunDispatchRoute is one route of a run; Stops is empty in the list of runs.
type RunDispatchRoute struct {
	ID            string            `json:"id"`
	Name          string            `json:"name"`
	Status        string            `json:"status"`
	DriverID      *string           `json:"driverId"`
	VehicleID     *string           `json:"vehicleId"`
	Blocked       bool              `json:"blocked"`
	Packages      int               `json:"packages"`
	BagsCollected int               `json:"bagsCollected"`
	BagsDelivered int               `json:"bagsDelivered"`
	PickupStops   int               `json:"pickupStops"`
	PickupsDone   int               `json:"pickupsDone"`
	DropStops     int               `json:"dropStops"`
	DropsDone     int               `json:"dropsDone"`
	DropsFailed   int               `json:"dropsFailed"`
	Stops         []RunDispatchStop `json:"stops"`
}

// RunDispatch is a delivery run in dispatch. Status is the dispatch status (PENDING = not planned, see PlanError);
// RunStatus is the run's lifecycle in delivery-runs when it could be read.
type RunDispatch struct {
	RunID          string              `json:"runId"`
	Town           string              `json:"town"`
	State          string              `json:"state"`
	TimeZone       string              `json:"timeZone"`
	ServiceDate    string              `json:"serviceDate"`
	WindowStart    time.Time           `json:"windowStart"`
	WindowEnd      time.Time           `json:"windowEnd"`
	SellerPickupAt *time.Time          `json:"sellerPickupAt"`
	DropMode       string              `json:"dropMode"`
	PickupPoint    *RunPickupPoint     `json:"pickupPoint"`
	Status         string              `json:"status"`
	RunStatus      *string             `json:"runStatus"`
	PlanError      *string             `json:"planError"`
	PlannedAt      *time.Time          `json:"plannedAt"`
	Progress       RunDispatchProgress `json:"progress"`
	Orders         *RunOrderProgress   `json:"orders"`
	Routes         []RunDispatchRoute  `json:"routes"`
	CanManage      bool                `json:"canManage"`
}

// RunDispatchList is GET /v1/staff/dispatch/runs.
type RunDispatchList struct {
	Items     []RunDispatch `json:"items"`
	CanManage bool          `json:"canManage"`
}

// RunAdvanceRequest moves a run to its next step by hand (COLLECTING, OUT_FOR_DELIVERY or COMPLETED).
type RunAdvanceRequest struct {
	To string `json:"to"`
}

// RunPickItem is units of one product on a pick list.
type RunPickItem struct {
	ATIN  string `json:"atin"`
	Name  string `json:"name"`
	Units int    `json:"units"`
}

// RunOrderBag is what goes into one order's bag; the order number is written on it.
type RunOrderBag struct {
	OrderNumber string        `json:"orderNumber"`
	Collected   bool          `json:"collected"`
	Units       int           `json:"units"`
	Lines       []RunPickItem `json:"lines"`
}

// SellerRunPickup is one delivery run's pick list for the seller's organisation.
type SellerRunPickup struct {
	RunID           string        `json:"runId"`
	Town            string        `json:"town"`
	State           string        `json:"state"`
	TimeZone        string        `json:"timeZone"`
	RunStatus       string        `json:"runStatus"`
	WindowStart     time.Time     `json:"windowStart"`
	WindowEnd       time.Time     `json:"windowEnd"`
	PickupAt        *time.Time    `json:"pickupAt"`
	DropMode        string        `json:"dropMode"`
	Orders          int           `json:"orders"`
	Units           int           `json:"units"`
	CollectedOrders int           `json:"collectedOrders"`
	Items           []RunPickItem `json:"items"`
	Bags            []RunOrderBag `json:"bags"`
}

// SellerRunPickupList is GET /v1/seller/run-pickups.
type SellerRunPickupList struct {
	Items []SellerRunPickup `json:"items"`
}

package domain

// DefaultPlatformMatrix is identity's seeded PLATFORM module matrix (V2__seed_rbac.sql) for the modules the staff
// console shows, one column per platform role in DefaultPlatformRoles order. The BFF uses it when the staff member may
// not read identity's matrix (403 without access:read) or identity is unavailable.
var DefaultPlatformMatrix = matrixFrom(map[string]string{
	"dash":      "FULL,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,VIEW,NONE",
	"orders":    "FULL,VIEW,VIEW,VIEW,EDIT,NONE,VIEW,VIEW,VIEW,VIEW,EDIT,VIEW,VIEW,VIEW,NONE",
	"catalog":   "FULL,EDIT,APPROVE,NONE,VIEW,VIEW,FULL,EDIT,NONE,NONE,NONE,VIEW,NONE,VIEW,NONE",
	"inventory": "FULL,VIEW,NONE,VIEW,NONE,NONE,NONE,EDIT,FULL,FULL,VIEW,VIEW,NONE,VIEW,NONE",
	"retail":    "FULL,VIEW,NONE,NONE,NONE,NONE,VIEW,FULL,VIEW,VIEW,NONE,VIEW,NONE,VIEW,NONE",
	"dispatch":  "FULL,EDIT,NONE,NONE,VIEW,NONE,NONE,NONE,FULL,VIEW,FULL,VIEW,NONE,VIEW,VIEW",
	"apps":      "FULL,APPROVE,APPROVE,VIEW,VIEW,NONE,NONE,NONE,NONE,NONE,NONE,VIEW,NONE,VIEW,NONE",
	"rbac":      "FULL,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,VIEW,NONE,VIEW,NONE",
	"payouts":   "FULL,VIEW,NONE,APPROVE,VIEW,NONE,NONE,NONE,NONE,NONE,NONE,VIEW,NONE,VIEW,NONE",
	"fees":      "FULL,VIEW,NONE,FULL,NONE,NONE,NONE,NONE,NONE,NONE,NONE,EDIT,VIEW,VIEW,NONE",
	"marketing": "FULL,VIEW,NONE,NONE,NONE,FULL,NONE,NONE,NONE,NONE,NONE,VIEW,NONE,VIEW,NONE",
	"disputes":  "FULL,APPROVE,APPROVE,EDIT,EDIT,NONE,NONE,NONE,VIEW,NONE,NONE,VIEW,NONE,VIEW,NONE",
	"audit":     "FULL,VIEW,VIEW,VIEW,NONE,NONE,NONE,NONE,NONE,NONE,NONE,VIEW,VIEW,VIEW,NONE",
	"markets":   "FULL,VIEW,NONE,NONE,NONE,NONE,NONE,NONE,VIEW,NONE,NONE,FULL,VIEW,VIEW,NONE",
	"siteCfg":   "FULL,VIEW,VIEW,NONE,VIEW,EDIT,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE",
	"dev":       "FULL,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,NONE,VIEW,FULL,VIEW,NONE",
}, DefaultPlatformRoles)

// DefaultPlatformRoles are identity's platform roles in matrix column order.
var DefaultPlatformRoles = []string{
	"platform.super_admin",
	"platform.operations_manager",
	"platform.trust_safety",
	"platform.finance",
	"platform.customer_support",
	"platform.marketing",
	"platform.catalog_manager",
	"platform.retail_buyer",
	"platform.fulfilment_ops",
	"platform.inventory_manager",
	"platform.dispatcher",
	"platform.product_owner",
	"platform.developer",
	"platform.auditor",
	"platform.driver",
}

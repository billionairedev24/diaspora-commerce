package domain

// TaxModule is the staff console module key of Sales tax: the review queue, who calculates tax, the category profiles,
// provider codes, shadow report, filings, nexus and rates (contracts/openapi/gateway-bff-staff.yaml, tag Tax).
const TaxModule = "tax"

// tax-returns permissions behind the Sales tax module (identity V38).
const (
	// PermTaxRead opens the Sales tax module: every tax screen and the review queue, read only.
	PermTaxRead = "tax:read"
	// PermTaxClassifyAdmin approves, reclassifies and asks the seller for more, changes profile gates and edits the code maps.
	PermTaxClassifyAdmin = "tax:classify-admin"
)

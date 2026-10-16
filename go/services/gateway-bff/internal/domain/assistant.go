package domain

import (
	"fmt"
	"strings"
)

// AssistantTier is the Lola level of a seller's plan: what she can look at, whether she can prepare changes for the seller to
// confirm, and how many messages a day.
type AssistantTier string

// The tiers, one per Seller plan.
const (
	AssistantEssentials AssistantTier = "ESSENTIALS"
	AssistantGrowth     AssistantTier = "GROWTH"
	AssistantPro        AssistantTier = "PRO"
)

// AssistantLimits is what a tier allows.
type AssistantLimits struct {
	Label string `json:"label"`
	// DailyMessages is how many messages a day (UTC); Lola resets at midnight UTC.
	DailyMessages int `json:"dailyMessages"`
	// MaxToolRounds bounds how many times she may look things up for one question.
	MaxToolRounds int `json:"maxToolRounds"`
	// History is how many earlier messages she remembers in a conversation.
	History int `json:"history"`
	// Tools are the things she can look at (tool names).
	Tools []string `json:"tools"`
	// CanPropose: she can prepare a change (set stock, move an order on) for the seller to confirm with a tap.
	CanPropose bool `json:"canPropose"`
}

// Assistant tool names.
const (
	ToolMyOrders      = "my_orders"
	ToolOrderDetail   = "order_detail"
	ToolMyListings    = "my_listings"
	ToolStockLevels   = "stock_levels"
	ToolFeesAndPlan   = "fees_and_plan"
	ToolPayouts       = "payouts"
	ToolSalesSummary  = "sales_summary"
	ToolProposeStock  = "propose_set_stock"
	ToolProposeNext   = "propose_next_step"
	assistantMaxInput = 1500
)

// AssistantMaxInput is the longest message accepted.
const AssistantMaxInput = assistantMaxInput

var essentialsTools = []string{ToolMyOrders, ToolOrderDetail, ToolMyListings, ToolStockLevels}

// AssistantLimitsOf is what each tier allows. Essentials looks at orders, listings and stock; Growth adds fees and payouts
// and can prepare changes; Pro adds sales summaries and the most messages.
func AssistantLimitsOf(t AssistantTier) AssistantLimits {
	switch t {
	case AssistantPro:
		return AssistantLimits{Label: "Lola Pro", DailyMessages: 400, MaxToolRounds: 6, History: 16, CanPropose: true,
			Tools: append(append([]string{}, essentialsTools...), ToolFeesAndPlan, ToolPayouts, ToolSalesSummary, ToolProposeStock, ToolProposeNext)}
	case AssistantGrowth:
		return AssistantLimits{Label: "Lola Growth", DailyMessages: 100, MaxToolRounds: 4, History: 10, CanPropose: true,
			Tools: append(append([]string{}, essentialsTools...), ToolFeesAndPlan, ToolPayouts, ToolProposeStock, ToolProposeNext)}
	default:
		return AssistantLimits{Label: "Lola Essentials", DailyMessages: 15, MaxToolRounds: 3, History: 6,
			Tools: append([]string{}, essentialsTools...)}
	}
}

// AssistantTierOf maps a plan code to its Lola tier; an unknown or missing plan gets Essentials.
func AssistantTierOf(planCode string) AssistantTier {
	code := strings.ToLower(strings.TrimSpace(planCode))
	switch {
	case strings.HasSuffix(code, "_pro"):
		return AssistantPro
	case strings.HasSuffix(code, "_growth"):
		return AssistantGrowth
	default:
		return AssistantEssentials
	}
}

// AssistantTierFor is the Lola tier a plan's entitlements give (staff set it per plan). A plan that carries no
// entitlement (an older seller service) falls back to the plan code's name.
func AssistantTierFor(lolaTier, planCode string) AssistantTier {
	switch strings.ToUpper(strings.TrimSpace(lolaTier)) {
	case "PRO":
		return AssistantPro
	case "GROWTH":
		return AssistantGrowth
	case "ESSENTIALS":
		return AssistantEssentials
	default:
		return AssistantTierOf(planCode)
	}
}

// AssistantUpgradeFrom is the plan to offer when a limit is reached or a tool is not on the plan: among the plans
// whose Lola tier is the next one up, the cheapest. Nil when no plan has a higher tier.
func AssistantUpgradeFrom(tier AssistantTier, plans []Plan) *AssistantUpgradeOffer {
	var best *Plan
	rank := 0
	for i := range plans {
		p := plans[i]
		if p.Entitlements == nil || p.Current {
			continue
		}
		r := assistantRank(AssistantTierFor(p.Entitlements.LolaTier, p.Code))
		if r <= assistantRank(tier) {
			continue
		}
		if best == nil || r < rank || (r == rank && p.MonthlyMinor < best.MonthlyMinor) {
			best, rank = &p, r
		}
	}
	if best == nil {
		return nil
	}
	next := AssistantTierFor(best.Entitlements.LolaTier, best.Code)
	l := AssistantLimitsOf(next)
	gains := fmt.Sprintf("%d messages a day", l.DailyMessages)
	if tier == AssistantEssentials && l.CanPropose {
		gains += ", fees and payouts, and changes she prepares for you to confirm"
	} else if next == AssistantPro {
		gains += " and sales summaries"
	}
	return &AssistantUpgradeOffer{PlanCode: best.Code, Label: best.Name, Gains: gains}
}

func assistantRank(t AssistantTier) int {
	switch t {
	case AssistantPro:
		return 2
	case AssistantGrowth:
		return 1
	default:
		return 0
	}
}

// AssistantUpgrade is the next plan up, shown when a limit is reached or a tool is not on the plan.
func AssistantUpgrade(t AssistantTier) *AssistantUpgradeOffer {
	switch t {
	case AssistantEssentials:
		return &AssistantUpgradeOffer{PlanCode: "seller_growth", Label: "Seller Growth", Gains: "100 messages a day, fees and payouts, and changes she prepares for you to confirm"}
	case AssistantGrowth:
		return &AssistantUpgradeOffer{PlanCode: "seller_pro", Label: "Seller Pro", Gains: "400 messages a day and sales summaries"}
	default:
		return nil
	}
}

// AssistantUpgradeOffer is the plan that gives Lola more.
type AssistantUpgradeOffer struct {
	PlanCode string `json:"planCode"`
	Label    string `json:"label"`
	Gains    string `json:"gains"`
}

// AssistantStatus is GET /v1/seller/assistant: the tier, today's messages and what she can do.
type AssistantStatus struct {
	Tier      AssistantTier          `json:"tier"`
	Limits    AssistantLimits        `json:"limits"`
	Used      int                    `json:"used"`
	Remaining int                    `json:"remaining"`
	Upgrade   *AssistantUpgradeOffer `json:"upgrade"`
}

// AssistantTurn is one earlier message of the conversation (the browser keeps it).
type AssistantTurn struct {
	// Role is user or assistant; anything else is dropped (a browser cannot forge tool results).
	Role    string `json:"role"`
	Content string `json:"content"`
}

// AssistantChatRequest is POST /v1/seller/assistant/chat.
type AssistantChatRequest struct {
	Message string          `json:"message"`
	History []AssistantTurn `json:"history"`
	// Language is the reply language (BCP 47), default the seller's.
	Language string `json:"language"`
}

// AssistantStep is something Lola looked up, shown to the seller ("Checking your orders").
type AssistantStep struct {
	Tool  string `json:"tool"`
	Label string `json:"label"`
}

// AssistantProposal is a change Lola prepared. Nothing happens until the seller taps it; the app then calls the normal
// Seller Center API with the seller's own session.
type AssistantProposal struct {
	// Kind is SET_STOCK or NEXT_STEP.
	Kind  string `json:"kind"`
	Label string `json:"label"`
	// SKU and Quantity are set for SET_STOCK.
	SKU      string `json:"sku,omitempty"`
	Quantity *int   `json:"quantity,omitempty"`
	// OrderNumber, ShipmentID and Step are set for NEXT_STEP (CONFIRMED, PACKED or READY).
	OrderNumber string `json:"orderNumber,omitempty"`
	ShipmentID  string `json:"shipmentId,omitempty"`
	Step        string `json:"step,omitempty"`
}

// AssistantReply is the answer to one message.
type AssistantReply struct {
	Answer    string              `json:"answer"`
	Steps     []AssistantStep     `json:"steps"`
	Proposals []AssistantProposal `json:"proposals"`
	Status    AssistantStatus     `json:"status"`
}

# MCP tool proposals

Status: **PROPOSED. Nothing here is built or approved.** The owner approves each tool by its number before anyone
writes it. Rule for agents: no new MCP tool without that approval; contracts and docs for every new endpoint are
still updated as normal.

Context: the MCP server (`java/services/mcp-server`) is private (no public route), every call needs a scoped token,
and AI-calling tools count against the daily AI spend cap (USD 2/day until staff change it in the console; `docs/runbooks/ai-spend-and-mcp-access.md`). Plain data reads
cost no AI spend.

## Existing tools (29, for reference)

- Shopper: `search_products`, `get_product`, `check_delivery`, `get_cart`, `propose_add_to_cart`, `track_order`,
  `start_return_proposal`, `find_service`, `my_order_history`.
- Seller: `list_my_products`, `product_status`, `list_my_orders`, `get_my_order`, `update_shipment_status`,
  `check_inventory`, `request_category`, `payout_balance`, `list_payouts`, `account_health`.
- Staff: `applications_queue`, `application_detail`, `moderation_queue`, `category_requests_queue`, `order_lookup`,
  `order_payment_split`, `seller_payout_status`, `seller_account_health`, `tax_review_queue`, `tax_shadow_report`.

**Existing issue:** `track_order` says it returns "carrier, tracking". Customers must never see carrier names.
Proposal 0 below.

## Rules for every tool

1. Permission-gated server-side with a scoped token; no staff tool reachable with a shopper token.
2. Writes only **propose** (a PROPOSED action a person confirms), like `propose_add_to_cart`. Audited.
3. Personal data masked; tenant isolation tests (a seller sees only their organisation).
4. No invented data: no basis means an empty or "unknown" answer, never a guess. No tax codes shown to sellers.
5. No carrier names and no "AfroTransact collects or delivers" wording unless the order truly is AfroTransact-delivered.
6. Contract (OpenAPI) and docs page updated with the tool.

## Proposal 0 (change to an existing tool)

| # | Change | Why |
|---|---|---|
| 0 | `track_order`: remove carrier names from the output and description | Owner rule: do not show carrier names |

## Customers (shoppers)

| # | Tool | Kind | What it does |
|---|---|---|---|
| C1 | `order_delivery_status` | read | Delivery status in plain words, no carrier names; replaces the carrier wording |
| C2 | `delivery_proof` | read | The proof photo, time and recipient for an order AfroTransact delivered; nothing for seller-shipped orders |
| C3 | `explain_checkout_block` | read | Why checkout is blocked for an item (customer-safe reason), and what to do |
| C4 | `estimate_order_total` | read | Cart total with estimated tax; says honestly when it needs a ZIP or an item is blocked |
| C5 | `pickup_near_me` | read | Products and sellers with pickup near a ZIP, with hours |
| C6 | `store_page_search` | read | Find a seller's store page and its sellable products |
| C7 | `compare_products` | read | Side by side from real attributes only (price, size, seller, stock) |
| C8 | `my_returns` | read | Status of the shopper's returns and refunds |
| C9 | `my_bookings` | read | Status of service bookings |
| C10 | `propose_reorder` | propose | Prepare re-adding the items of a past order to the cart |
| C11 | `propose_set_delivery_place` | propose | Prepare changing the delivery place used for tax and arrival dates |

## Sellers

| # | Tool | Kind | What it does |
|---|---|---|---|
| S1 | `why_cant_i_sell` | read | Plain reason a product is not live: tax decision, photo, approval, category, stock |
| S2 | `tax_status_of_product` | read | Whether a product has a resolved tax decision; never shows or accepts tax codes |
| S3 | `pending_actions` | read | What needs the seller's attention today (orders to pack, returns, missing info, blocked products) |
| S4 | `pickup_status` | read | Pickup on or off, hours, ready time |
| S5 | `set_pickup` | propose | Prepare turning pickup on/off with typed hours; seller confirms |
| S6 | `shipping_setup_status` | read | Ship-from address, handling time, who delivers; what is missing for honest arrival dates |
| S7 | `store_page_status` | read | Whether the store page is published and what is missing (needs the store page work) |
| S8 | `sales_summary` | read | Orders and revenue over 7/30/90 days from real orders |
| S9 | `low_stock` | read | Items near or at zero stock |
| S10 | `returns_and_disputes` | read | Open returns and disputes with the next step |
| S11 | `reviews_summary` | read | Real reviews only; says nothing rather than "0 reviews" |
| S12 | `listing_quality_check` | read | Missing photos, thin titles, category mismatches |
| S13 | `propose_price_change` | propose | Prepare a price change; seller confirms |
| S14 | `propose_stock_update` | propose | Prepare a stock count change; seller confirms |
| S15 | `team_and_roles` | read | Who is on the team and what each role can do |
| S16 | `draft_listing` | AI read | Draft a title and description from what the seller typed (uses the AI gateway and the USD 2/day cap) |

## Staff (admin)

| # | Tool | Kind | What it does |
|---|---|---|---|
| A1 | `delivery_queue` | read | AfroTransact-delivered orders waiting, with seller and city by name (`delivery:read`; needs the delivery work) |
| A2 | `assign_delivery` | propose | Prepare taking or assigning a delivery job (`delivery:manage`) |
| A3 | `explain_tax_decision` | read | Why a product was approved, flagged or blocked, with the rule that matched |
| A4 | `provider_code_gaps` | read | Outcomes with no provider code (Stripe, Avalara) |
| A5 | `tax_provider_status` | read | Whether each tax provider is configured and answering (Test connection result) |
| A6 | `roles_and_permissions` | read | What a role or person can do, effective permissions (`access:read`) |
| A7 | `audit_log_search` | read | Search the change log by person, role, object |
| A8 | `stuck_orders` | read | Orders stuck in a state with the likely cause |
| A9 | `returns_and_disputes_queue` | read | Open returns and disputes across sellers |
| A10 | `seller_health_overview` | read | A seller's setup completeness: ship-from, pickup, payouts, tax, store page |
| A11 | `duplicate_candidates_queue` | read | Possible duplicate product pages from matching |
| A12 | `search_index_status` | read | Why a product does or does not appear in search (live, photo, variant, tax, index lag) |
| A13 | `service_health` | read | Services up, sweeps running, identity client scope drift |
| A14 | `ai_spend_today` | read | AI spend against the daily cap, by feature. The REST endpoint it would call now exists (ai-gateway `GET /v1/ai/usage/today`, `ai:manage`; the staff console shows it); the tool is still not built or approved |
| A15 | `propose_tax_decision` | propose | Prepare approving or rejecting a tax review item (`tax:review`); a person confirms |
| A16 | `set_ai_spend_cap` | propose | **Proposed only; not built, not approved.** Prepare a change of the daily AI spend cap (value and reason) as a PROPOSAL the staff member confirms in the console; the tool itself could never save it. Would need `ai:cap.manage` on the caller's own relayed token (ai-gateway `PUT /v1/ai/spend-cap` stays the judge: ceiling, reason, version). Today the cap is changed in the console only, and a test (`NoSpendCapToolTest`) keeps every tool away from it |

## For anyone integrating (all audiences)

| # | Tool | Kind | What it does |
|---|---|---|---|
| X1 | `api_reference_search` | read | Search the OpenAPI/AsyncAPI contracts and docs portal pages for an endpoint or event |

## Suggested first batch (lowest risk, highest value)

0, C1, C2, S1, S2, S3, A1, A3, A12. All read-only except change 0, which only removes wording. Write tools (S5, S13, S14,
A2, A15, C10, C11) come after the read tools prove themselves.

## Approval

Reply with the numbers to approve, trim or reject. Nothing is built until then.

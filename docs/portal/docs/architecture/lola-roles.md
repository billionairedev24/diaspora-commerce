---
title: Lola for customers and staff
description: What Lola can look up for shoppers and for the staff console, how access and privacy are enforced, and which MCP tools mirror them.
---

Lola has three jobs, each with its own tools and the same two promises: she only states what a tool returned, and she
never changes anything herself (a change is a button the person taps).

| | Shoppers (storefront, app) | Sellers and providers | Staff console |
|---|---|---|---|
| Runs in | ai-gateway (`/v1/lola/chat`, SSE) | gateway-bff, model turns at ai-gateway | gateway-bff, model turns at ai-gateway |
| Sees | the shopper's own data | the organisation's own data | what the staff member's role can open |
| Can change | prepares buttons: add to cart, checkout, return, booking | prepares buttons: set stock, next step | nothing; offers a button that opens a record |
| Daily limit | ai budget per user | by plan (15 / 100 / 400) | 200 messages (UTC) |

## Shoppers

Search, compare offers, delivery promise, add to cart, recipe-to-cart, track one order, start a return, find and book a
service, checkout, hand-off to support, and (new) **`my_orders`** (recent orders with number, status, date and total, to find an
order number), **`view_cart`** (items, prices, unavailable or price-changed lines, subtotal) and **`my_bookings`** (reference,
service, status, start, price, deposit). They read orders, cart and bookings with the shopper's relayed token, so a shopper can
only ever see their own. **`return_status`** (return number, items, reasons, progress, drop-off deadline, refund) and
**`refund_status`** (refund amount and state per return, "not issued yet" until one exists) read tax-returns the same way, optionally
for one order; Lola never states a refund date they do not return. Seller-written text (product titles, service names) is cleaned and quoted as untrusted data.

## Seller Lola tools by plan

| Tier | Tools |
|---|---|
| Essentials | `my_orders`, `order_detail`, `my_listings`, `stock_levels`, `payouts`, `account_health`, **`my_returns`** (tax-returns, the organisation's returns and refunds), **`my_disputes`** (trust-ops claims against the seller with the respond-by deadline; buyer ids are never shown) |
| Growth | Essentials plus `fees_and_plan`, **`plan_billing`** (plan, next charge, credit, charging condition, unpaid or declined invoices, rewards), **`my_coupons`**, and prepared changes (`propose_set_stock`, `propose_next_step`) |
| Pro | Growth plus `sales_summary` |

All are read-only except the two `propose_*` tools, which only show a button the seller must tap.

## Staff ("admin Lola")

`GET /v1/staff/assistant` (today's messages) and `POST /v1/staff/assistant/chat`. Tools:

| Tool | What it does | Access |
|---|---|---|
| `staff_overview` | dashboard headline numbers (7, 30 or 90 days) | Dashboard module |
| `search_records` | one search across a console table, first rows with their columns | the table's module |
| `get_record` | a record as its sheet shows it: fields, nested tables, recent activity, actions that exist | the table's module |
| `seller_payouts` | a seller's balance: available, held orders with release dates, pauses | Payouts module (view) |
| `seller_account_health` | the seller's Stripe Connect account (what is still needed) | Stripe Connect table |
| `seller_billing` | a seller's plan, credit, charging condition and invoices (paid, unpaid, declined and why) | Plans & billing module (view) |
| `open_record` | a button that opens the record in the console | none (a link) |

Every lookup goes through the same use cases as the console tables, so module access, permissions and the BFF's own
checks apply: a role without the Payouts module gets "your role can't see that", not data. Before the model sees a result,
emails keep their first letter and domain and phone-like numbers keep their last four digits; order numbers and ids are left
alone. ai-gateway serves the model turns at `POST /v1/ai/staff-assistant/complete` (staff accounts with `ai:use`, budget
`staff-assistant`, no organisation to bill).

## MCP

The same questions are available to outside agents through the MCP server (`java/services/mcp-server/README.md`): shoppers
`my_order_history`; sellers `payout_balance`, `list_payouts`, `account_health`, `get_my_order`; staff `order_lookup`,
`seller_payout_status`, `seller_account_health`.

---
title: "Lola for sellers"
sidebar_label: "Lola for sellers"
sidebar_position: 5
description: The assistant in Seller Center, the level each plan gets, what she can see and do, and how it is built and billed.
---

Lola in Seller Center answers a seller's questions about their own shop: orders to pack, listings, stock and, on higher plans,
fees, payouts and sales. She can also prepare a change (set a stock level, move an order on) that the seller confirms with a tap.
She is the same Lola the shoppers meet, with a different job and different tools.

## Levels

The level comes from the organisation's plan.

| | Essentials | Growth | Pro |
|---|---|---|---|
| Messages a day (UTC) | 15 | 100 | 400 |
| Orders, order detail, listings, stock | Yes | Yes | Yes |
| Fees and plan, payouts | No | Yes | Yes |
| Sales summary (7, 30, 90 days) | No | No | Yes |
| Prepares changes for the seller to confirm | No | Yes | Yes |
| Earlier messages she remembers | 6 | 10 | 16 |

When the day's messages are used, the sheet says so, names the plan that gives more, and Lola starts again at midnight UTC. A
message that gets no answer (the model is down) is not counted.

## What she can see

Lola looks things up through the same Seller Center services as the pages, as the signed-in seller, so she can never see more than
that person can: a team member without access to payouts gets "your role can't see that". She never sees buyers' names,
addresses, phones or emails. Text that comes from products or notes is passed to the model as untrusted data.

## What she can do

She cannot change anything herself. A change is a **proposal**: a button under her answer. When the seller taps it, the app calls
the normal Seller Center API with the seller's own session (stock adjustment, or the next order step). Shipping and handing an
order over need a label or a code and stay on the order page.

## How it is built

- **Seller Center** has an "Ask Lola" button (approved sellers only) that opens a chat sheet. It keeps the conversation in the
  browser and sends the last few turns with each question.
- **gateway-bff** runs the conversation: `GET /v1/seller/assistant` (level, messages left) and `POST /v1/seller/assistant/chat`.
  It decides the level from the plan, counts the day's messages per organisation, offers the tools the level allows, runs them,
  and loops with the model for a few rounds. The earlier turns are accepted as plain user and assistant text only, so a browser
  cannot slip in an instruction or a tool result.
- **ai-gateway** serves one model turn: `POST /v1/ai/seller-assistant/complete`. It checks the budget (`seller-assistant`), routes to
  a model with the offered tools and records the usage against the organisation as **AI credits**, like the AI listing writer.
  It never calls a tool.

The level is currently worked out from the plan code (`*_pro`, `*_growth`, anything else Essentials). When plans become fully
configurable, it will read the plan's own entitlements.

External agents use the [MCP server](../services/mcp-server.md), which has its own seller tools; Lola for sellers does not go through it.

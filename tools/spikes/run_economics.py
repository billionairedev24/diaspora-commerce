#!/usr/bin/env python3
"""Community delivery run economics calculator (spike for epic E34 / S265).

Throwaway spike tool; standard library only. It mirrors the cost model of
java/services/delivery-runs (DefaultRunEconomicsCalculator):

    cost(n) = fixed + n * per_order
    fixed     = drive minutes (x2 when round trip) + collection minutes [+ pickup-point stop]
                -> minutes / 60 * driver $/h  +  road miles (x2 when round trip) * vehicle $/mi
    per_order = doorstep stop minutes (or handover minutes at a pickup point) / 60 * driver $/h

Two break-even lenses are printed:

  * delivery   - the run fee alone pays for the run (what delivery-runs' S265 economics shows):
                 smallest n with n * fee >= cost(n)
  * contribution - each order also brings the seller pickup fee and AfroTransact's take on the
                 basket (commission for marketplace sellers / gross margin for Retail, net of
                 card fees on the basket); the run fee loses card fees:
                 smallest n with n * (fee * (1 - card_pct) + seller_fee + basket * take_rate) >= cost(n)

Every assumption is a CLI flag. Defaults are the Bastrop example in
docs/spikes/preorder-delivery-runs.md; change them and re-run, e.g.

    python3 tools/spikes/run_economics.py
    python3 tools/spikes/run_economics.py --drive-minutes 60 --driver-per-hour 30
    python3 tools/spikes/run_economics.py --drop-mode pickup-point --fees 4.99 7.99
"""

from __future__ import annotations

import argparse
import math
from dataclasses import dataclass

MAX_ORDERS = 10_000


@dataclass(frozen=True)
class Assumptions:
    road_miles: float
    drive_minutes: float
    round_trip: bool
    driver_per_hour: float
    vehicle_per_mile: float
    collection_minutes: float
    sellers: int
    per_seller_minutes: float
    drop_mode: str
    per_stop_minutes: float
    pickup_point_stop_minutes: float
    handover_minutes: float
    basket: float
    take_rate: float
    seller_fee: float
    card_pct: float


@dataclass(frozen=True)
class CostModel:
    miles: float
    fixed_minutes: float
    per_order_minutes: float
    fixed_cost: float
    per_order_cost: float

    def cost(self, orders: int) -> float:
        return round(self.fixed_cost + orders * self.per_order_cost, 2)


def cost_model(a: Assumptions) -> CostModel:
    legs = 2 if a.round_trip else 1
    miles = a.road_miles * legs
    fixed_minutes = a.drive_minutes * legs + a.collection_minutes + a.sellers * a.per_seller_minutes
    if a.drop_mode == "pickup-point":
        fixed_minutes += a.pickup_point_stop_minutes
        per_order_minutes = a.handover_minutes
    else:
        per_order_minutes = a.per_stop_minutes
    per_minute = a.driver_per_hour / 60.0
    return CostModel(
        miles=miles,
        fixed_minutes=fixed_minutes,
        per_order_minutes=per_order_minutes,
        fixed_cost=fixed_minutes * per_minute + miles * a.vehicle_per_mile,
        per_order_cost=per_order_minutes * per_minute,
    )


def delivery_revenue(fee: float, _a: Assumptions) -> float:
    return fee


def contribution_revenue(fee: float, a: Assumptions) -> float:
    return fee * (1 - a.card_pct) + a.seller_fee + a.basket * a.take_rate


def break_even(revenue_per_order: float, model: CostModel) -> int | None:
    if revenue_per_order <= model.per_order_cost:
        return None
    n = max(1, math.ceil(model.fixed_cost / (revenue_per_order - model.per_order_cost)))
    while n > 1 and round((n - 1) * revenue_per_order, 2) >= model.cost(n - 1):
        n -= 1
    while round(n * revenue_per_order, 2) < model.cost(n) and n < MAX_ORDERS:
        n += 1
    return None if n >= MAX_ORDERS else n


def fee_to_break_even(orders: int, model: CostModel) -> float:
    """Smallest whole-cent run fee whose fees alone cover a run of ``orders`` orders."""
    return math.ceil(model.cost(orders) / orders * 100) / 100


def money(v: float) -> str:
    return f"-${-v:,.2f}" if v < 0 else f"${v:,.2f}"


def table(headers: list[str], rows: list[list[str]]) -> str:
    widths = [max(len(h), *(len(r[i]) for r in rows)) for i, h in enumerate(headers)]
    line = lambda cells: "| " + " | ".join(c.rjust(w) for c, w in zip(cells, widths)) + " |"  # noqa: E731
    sep = "|" + "|".join("-" * (w + 1) + ":" for w in widths) + "|"
    return "\n".join([line(headers), sep, *(line(r) for r in rows)])


def parse(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = p.add_argument_group("route (from geo-service in production)")
    g.add_argument("--road-miles", type=float, default=32.0, help="one-way road miles origin -> run area (32)")
    g.add_argument("--drive-minutes", type=float, default=50.0, help="one-way drive minutes (50)")
    g.add_argument("--one-way", action="store_true", help="do not count the drive back to the origin")
    g = p.add_argument_group("driver and vehicle")
    g.add_argument("--driver-per-hour", type=float, default=25.0, help="loaded driver cost $/h (25)")
    g.add_argument("--vehicle-per-mile", type=float, default=0.70, help="fuel + wear $/mi (0.70)")
    g = p.add_argument_group("time on the run")
    g.add_argument("--collection-minutes", type=float, default=45.0,
                   help="fixed seller sweep + FC load-out minutes (45)")
    g.add_argument("--sellers", type=int, default=0, help="extra seller pickup stops to cost separately (0)")
    g.add_argument("--per-seller-minutes", type=float, default=12.0,
                   help="minutes per extra seller stop, used with --sellers (12)")
    g.add_argument("--drop-mode", choices=["doorstep", "pickup-point"], default="doorstep")
    g.add_argument("--per-stop-minutes", type=float, default=6.0,
                   help="doorstep stop incl. local driving between drops (6)")
    g.add_argument("--pickup-point-stop-minutes", type=float, default=30.0, help="time at a pickup point (30)")
    g.add_argument("--handover-minutes", type=float, default=1.0, help="per order at a pickup point (1)")
    g = p.add_argument_group("order economics (contribution lens)")
    g.add_argument("--basket", type=float, default=45.0, help="average basket excl. fees $ (45)")
    g.add_argument("--take-rate", type=float, default=0.10,
                   help="AfroTransact take on the basket, net of card fees on it (0.10)")
    g.add_argument("--seller-fee", type=float, default=2.00,
                   help="seller pickup-delivery fee per order $ (2.00 = seed $2/package x 1)")
    g.add_argument("--card-pct", type=float, default=0.029, help="card fee share lost on the run fee (0.029)")
    g = p.add_argument_group("output")
    g.add_argument("--fees", type=float, nargs="+", default=[5.99, 7.99, 9.99, 12.99])
    g.add_argument("--orders", type=int, nargs="+", default=[5, 10, 15, 20, 25, 30, 40])
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse(argv)
    a = Assumptions(
        road_miles=args.road_miles, drive_minutes=args.drive_minutes, round_trip=not args.one_way,
        driver_per_hour=args.driver_per_hour, vehicle_per_mile=args.vehicle_per_mile,
        collection_minutes=args.collection_minutes, sellers=args.sellers,
        per_seller_minutes=args.per_seller_minutes, drop_mode=args.drop_mode,
        per_stop_minutes=args.per_stop_minutes, pickup_point_stop_minutes=args.pickup_point_stop_minutes,
        handover_minutes=args.handover_minutes, basket=args.basket, take_rate=args.take_rate,
        seller_fee=args.seller_fee, card_pct=args.card_pct,
    )
    m = cost_model(a)

    print("## Assumptions")
    print(f"- route: {a.road_miles:g} road mi / {a.drive_minutes:g} min one way, "
          f"{'round trip' if a.round_trip else 'one way'}; drop mode {a.drop_mode}")
    print(f"- driver ${a.driver_per_hour:.2f}/h, vehicle ${a.vehicle_per_mile:.2f}/mi; "
          f"collection {a.collection_minutes:g} min + {a.sellers} x {a.per_seller_minutes:g} min extra seller stops")
    print(f"- per order: {m.per_order_minutes:g} min; basket ${a.basket:.2f}, take {a.take_rate:.0%}, "
          f"seller fee ${a.seller_fee:.2f}, card {a.card_pct:.1%} of the run fee")
    print()
    print("## Cost model")
    print(f"- fixed: {m.fixed_minutes:g} min + {m.miles:g} mi = {money(m.fixed_cost)} per run")
    print(f"- per order: {money(m.per_order_cost)}")
    print()

    print("## Break-even orders per run")
    rows = []
    for fee in args.fees:
        be_d = break_even(delivery_revenue(fee, a), m)
        be_c = break_even(contribution_revenue(fee, a), m)
        rows.append([money(fee), "never" if be_d is None else str(be_d),
                     "never" if be_c is None else str(be_c), money(contribution_revenue(fee, a))])
    print(table(["run fee", "delivery lens", "contribution lens", "contribution / order"], rows))
    print()

    print("## Run P&L, delivery lens (run fees - estimated cost)")
    headers = ["orders", "cost", "cost / order", *(f"fee {money(f)}" for f in args.fees), "fee to break even"]
    rows = []
    for n in args.orders:
        rows.append([str(n), money(m.cost(n)), money(m.cost(n) / n),
                     *(money(round(n * f, 2) - m.cost(n)) for f in args.fees), money(fee_to_break_even(n, m))])
    print(table(headers, rows))
    print()

    print("## Run P&L, contribution lens (fees net of card + seller fees + take on baskets - cost)")
    headers = ["orders", *(f"fee {money(f)}" for f in args.fees)]
    rows = []
    for n in args.orders:
        rows.append([str(n), *(money(round(n * contribution_revenue(f, a), 2) - m.cost(n)) for f in args.fees)])
    print(table(headers, rows))


if __name__ == "__main__":
    main()

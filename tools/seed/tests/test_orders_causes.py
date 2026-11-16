"""The orders step names each cause once: a delivery option checked against what checkout offers (carrier shipping is off
at launch), products verified purchasable before any checkout, Stripe test payments confirmed with the test card, and the
orders a cause blocks are left out of the later steps."""
from __future__ import annotations

import unittest

from seedlib import dataset as D
from seedlib import ops_dataset as O
from seedlib import stripe_test as ST
from seedlib.http import ApiError
from seedlib.ops import OpsSeeder, SeedCause

from tests.test_ops import Fake, Users


def make(find=None, env=None, stripe=None, **svc):
    problems, logs = [], []
    s = OpsSeeder(svc, Users(), find or (lambda title: None), log=logs.append, add=lambda w, o, n=1: None,
                  problem=problems.append, sleep=lambda x: None, wait=0, env=env or {},
                  stripe_send=stripe or (lambda url, form, headers: {"status": "requires_capture"}))
    s.problems, s.logs = problems, logs
    return s


def live_product(title):
    return {"id": "p", "title": title, "status": "LIVE", "variants": [{"atin": "B0X", "taxStatus": "READY"}]}


class ChooseOptionTest(unittest.TestCase):
    parcel = O.Package((O.Line("at-basmati"),))  # option "carrier"
    local = O.Package((O.Line("egusi"),), option="afrotransact_delivery", route="today")

    def test_a_parcel_scenario_uses_afrotransact_delivery_when_the_carrier_is_not_offered(self):
        s = make()
        self.assertEqual(s.choose_option(self.parcel, {"keys": ["afrotransact_delivery", "pickup"], "pickup": None}),
                         ("afrotransact_delivery", None))
        self.assertEqual(s.choose_option(self.parcel, {"keys": ["carrier", "afrotransact_delivery"], "pickup": None})[0],
                         "carrier")
        self.assertEqual(s.choose_option(self.parcel, None)[0], "afrotransact_delivery", "unknown: the launch default")

    def test_a_delivery_option_that_is_not_offered_is_one_named_cause(self):
        with self.assertRaises(SeedCause) as ctx:
            make().choose_option(self.local, {"keys": ["pickup"], "pickup": "pt1"})
        self.assertIn("offers: pickup", ctx.exception.message)
        self.assertIn("ops_dataset.py", ctx.exception.hint)

    def test_pickup_needs_an_offered_point_and_sends_its_id(self):
        pickup = O.Package((O.Line("at-basmati"),), option="pickup")
        self.assertEqual(make().choose_option(pickup, {"keys": ["pickup"], "pickup": "pt1"}), ("pickup", "pt1"))
        with self.assertRaises(SeedCause):
            make().choose_option(pickup, {"keys": ["pickup"], "pickup": None})


class PurchasableTest(unittest.TestCase):
    def order(self, key):
        return next(o for o in O.ORDERS if o.key == key)

    def test_a_missing_product_a_held_product_and_a_missing_offer_each_say_why(self):
        offers = Fake()
        offers.on("GET", "/v1/offers", lambda *a: {"items": []})
        s = make(find=lambda t: None, offers=offers)
        cause = s._purchasable(self.order("delivered-return-requested"))
        self.assertIn("not in the catalog", cause.message)
        self.assertIn("--only products", cause.hint)

        held = lambda t: dict(live_product(t), status="IN_REVIEW")
        self.assertIn("IN_REVIEW", make(find=held, offers=offers)._purchasable(self.order("cancelled")).message)

        taxed = lambda t: dict(live_product(t), variants=[{"atin": "B0X", "taxStatus": "PENDING_REVIEW"}])
        self.assertIn("tax status", make(find=taxed, offers=offers)._purchasable(self.order("cancelled")).message)

        nooffer = make(find=live_product, offers=offers)._purchasable(self.order("cancelled"))
        self.assertIn("no ACTIVE offer", nooffer.message)

    def test_a_purchasable_order_passes(self):
        offers = Fake()
        offers.on("GET", "/v1/offers", lambda *a: {"items": [{"id": "o1", "status": "ACTIVE", "fulfilment": "AFROTRANSACT"}]})
        self.assertIsNone(make(find=live_product, offers=offers)._purchasable(self.order("cancelled")))


class OneLinePerCauseTest(unittest.TestCase):
    def test_six_orders_with_one_cause_give_one_problem_and_are_left_out_of_later_steps(self):
        s = make(find=lambda t: None)
        s._find_order = lambda order: None
        s._seller_pickup_offer = lambda: None
        s.place_orders()
        missing = [p for p in s.problems if "not in the catalog" in p]
        self.assertTrue(len(s.problems) <= len({ln.product for o in O.ORDERS for pk in o.packages for ln in pk.lines}) + 1)
        self.assertTrue(all("Fix:" in p for p in s.problems))
        self.assertTrue(missing)
        self.assertEqual(len(s.live_orders()), 0)
        s.unplaced.clear()
        self.assertEqual(len(s.live_orders()), len(O.ORDERS) + 1)


class StripeTestCardTest(unittest.TestCase):
    def view(self, pi):
        return {"orderNumber": "AT-1", "status": "awaiting_payment", "paymentClientSecret": f"{pi}_secret_abc"}

    def test_a_test_key_confirms_the_intent_with_the_test_card(self):
        sent = []
        s = make(env={"STRIPE_SECRET_KEY": "sk_test_abc123"}, stripe=lambda url, form, headers: sent.append((url, form, headers)) or {"status": "requires_capture"})
        s._pay_with_test_card(self.view("pi_3Abc"))
        self.assertEqual(sent[0][0], "https://api.stripe.com/v1/payment_intents/pi_3Abc/confirm")
        self.assertEqual(sent[0][1]["payment_method"], "pm_card_visa")

    def test_the_local_stand_in_needs_nothing_and_a_live_key_is_never_used(self):
        sent = []
        s = make(env={"STRIPE_SECRET_KEY": "sk_test_abc123"}, stripe=lambda *a: sent.append(a) or {})
        s._pay_with_test_card(self.view("pi_localtest_1"))
        self.assertEqual(sent, [])
        self.assertIsNone(ST.test_key({"STRIPE_SECRET_KEY": "sk_live_xyz"}))
        self.assertIsNone(ST.test_key({"STRIPE_SECRET_KEY": "sk_test_replace_with_your_stripe_test_key"}))
        with self.assertRaises(SeedCause) as ctx:
            make(env={})._pay_with_test_card(self.view("pi_3Abc"))
        self.assertIn(".local/secrets.env", ctx.exception.hint)

    def test_checkout_confirms_then_continues_and_a_refusal_is_a_cause(self):
        orders = Fake()
        states = iter([{"status": "placed", "orderNumber": "AT-1"}])
        orders.on("POST", "/v1/checkout/AT-1/confirm", lambda *a: next(states))
        s = make(env={"STRIPE_SECRET_KEY": "sk_test_k"}, orders=orders)
        view = self.view("pi_3Abc")
        # the loop of _checkout, exercised through its pieces
        s._pay_with_test_card(view)
        self.assertEqual(orders.post("/v1/checkout/AT-1/confirm")["status"], "placed")

        def refuse(url, form, headers):
            raise ST.StripeTestError("Stripe answered 402: Your card was declined.")

        with self.assertRaises(SeedCause) as ctx:
            make(env={"STRIPE_SECRET_KEY": "sk_test_k"}, stripe=refuse)._pay_with_test_card(view)
        self.assertIn("declined", ctx.exception.message)

    def test_intent_ids(self):
        self.assertEqual(ST.intent_id("pi_3Abc_secret_xyz"), "pi_3Abc")
        self.assertEqual(ST.intent_id(None), "")


if __name__ == "__main__":
    unittest.main()

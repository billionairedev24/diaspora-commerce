-- Customers never pay on a Stripe-hosted page (no Checkout Sessions, no Payment Links): a late pre-order add paid
-- with a pay-now link gets an ordinary manual-capture PaymentIntent (key <orderId>:create-intent) that the customer
-- confirms with Stripe Elements on AfroTransact's own pay page (/orders/<number>/pay). payments no longer writes or
-- reads payment_link. The rows written by V11 are kept, read-only, for audit: renamed so nothing mistakes them for a
-- live path. A session paid after this release is matched to no payment; the webhook handler cancels such an
-- authorisation (StripeEventHandlers, key <intentId>:cancel-orphan), so it can never be captured.
alter table payment_link rename to legacy_checkout_session;
alter table legacy_checkout_session rename constraint payment_link_pkey to legacy_checkout_session_pkey;
comment on table legacy_checkout_session is
    'Read-only audit of Stripe Checkout Sessions created before hosted payment links were removed. Not used.';

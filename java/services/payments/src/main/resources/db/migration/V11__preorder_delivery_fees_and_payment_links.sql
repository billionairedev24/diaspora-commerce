-- S272 community pre-orders.
-- 1. A fourth fulfilment mode on payment lines: PREORDER lines carry their share of the window's flat door-delivery
--    fee as shipping, booked to 4250 Local delivery fees (not 4200) at capture and debited from it by refunds.
alter table payment_line drop constraint payment_line_fulfilment_check;
alter table payment_line add constraint payment_line_fulfilment_check
    check (fulfilment in ('FBA', 'SELF', 'AFROTRANSACT_DELIVERY', 'PREORDER'));

-- 2. Hosted payment links of late pre-order adds (customer service sends the link by SMS). The paid link's
--    PaymentIntent is attached to the payment by its first webhook (metadata payment_id).
create table payment_link (
    payment_id        uuid primary key references payment (id),
    stripe_session_id varchar(255) not null unique,
    url               text         not null,
    expires_at        timestamptz  not null,
    created_at        timestamptz  not null
);

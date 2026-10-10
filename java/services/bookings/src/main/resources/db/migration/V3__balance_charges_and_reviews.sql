-- Long-dated bookings (S220): card authorisations last ~7 days, so the balance hold can be released after the
-- deposit capture and the balance charged off-session on the saved card shortly before the appointment.
alter table booking
    add column balance_state           varchar(20) not null default 'ON_AUTHORISATION',
    add column balance_payment_id      uuid,
    add column balance_attempts        int         not null default 0,
    add column balance_client_secret   text,
    add column balance_failure         text,
    add column balance_next_attempt_at timestamptz;

-- Off-session balance job: bookings whose balance is due (released or declined), soonest appointment first.
create index booking_balance_due on booking (start_at) where balance_state in ('DUE', 'FAILED');

-- Ratings (S221): one review per completed booking, averaged per service in search.
create table booking_review (
    booking_id  uuid primary key references booking (id),
    org_id      varchar(80)  not null,
    service_id  uuid         not null,
    customer_id varchar(120) not null,
    rating      smallint     not null check (rating between 1 and 5),
    text        text,
    created_at  timestamptz  not null
);
create index booking_review_service on booking_review (service_id);

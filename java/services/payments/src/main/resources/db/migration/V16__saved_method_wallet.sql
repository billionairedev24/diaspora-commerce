-- The wallet a saved card came from (card.wallet.type: apple_pay, google_pay, link …), shown next to the card.
alter table customer_payment_method add column wallet varchar(30);

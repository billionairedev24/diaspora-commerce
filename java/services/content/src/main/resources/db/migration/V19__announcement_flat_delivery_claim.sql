-- The seeded announcement promised "FREE delivery on Fulfilled by AfroTransact items", which is not how launch shipping
-- works: AfroTransact charges ONE flat delivery fee (staff-edited in orders' shipping settings, optionally free above an
-- amount). Replace the claim only where staff have not edited the placement, and name no amount (the fee is shown in
-- the page, the cart and checkout from the one setting).
update placement
   set name = 'Flat delivery fee',
       payload = jsonb_set(jsonb_set(jsonb_set(payload, '{text}', '"One flat delivery fee on every order. Pickup is free where offered."'),
                                     '{cta}', '"Shop now"'), '{href}', '"/search"'),
       updated_at = now()
 where id = 'plc_ann_free_delivery'
   and payload ->> 'text' = 'FREE delivery on Fulfilled by AfroTransact items';

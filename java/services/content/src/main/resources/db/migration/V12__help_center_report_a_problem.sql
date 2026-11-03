-- A delivered order has no self-service return: customers report a problem (description and photos) and staff resolve
-- it. The seeded help pages still sent people to /account/returns, so they now explain the new flow. Only pages that
-- still carry the seeded wording are rewritten; anything site-content staff already edited is left alone.
update help_article
   set summary = 'Track a package, report a problem or reach us.',
       sections = '[{"heading":"Track a package","body":"[Your orders](/orders) shows every shipment and its live status from carriers and AfroTransact Delivery."},
   {"heading":"Report a problem","body":"If something is wrong with a delivered order, tell us and add photos. Our team will look into it and reply. [Get help with an order](/account/help)."},
   {"heading":"Contact us","body":"Chat with Lola any time, or reach a person 8 am–10 pm ET at help@afrotransact.com."}]'::jsonb,
       updated_at = now(),
       version = version + 1
 where slug = 'help-center' and sections::text like '%/account/returns%';

update help_article
   set summary = 'Report a problem with an order and follow what we decide.',
       sections = '[{"heading":"Problems with an order","body":"There is no self-service return. If something arrived damaged, wrong, not as described or not at all, contact us and add photos."},
   {"heading":"Report a problem","body":"Go to [Get help with an order](/account/help), choose the order and what went wrong, describe it and add photos. Our team reviews every request and replies."},
   {"heading":"Refunds","body":"If we refund you, the money goes back to whoever paid, once we have decided. Read the full [Returns & Refunds policy](/legal/returns)."}]'::jsonb,
       updated_at = now(),
       version = version + 1
 where slug = 'returns' and sections::text like '%/account/returns%';

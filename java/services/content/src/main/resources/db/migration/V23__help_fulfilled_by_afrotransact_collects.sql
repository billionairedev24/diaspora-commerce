-- ADR 0013 (S617, S632): Fulfilled by AfroTransact means AfroTransact collects from the seller's pickup location and delivers.
-- Nothing is sent to AfroTransact and no stock is held. The seeded help page still told sellers to send stock to a fulfilment
-- centre; rewrite it, but only while it still carries the seeded wording (a page staff already edited is left alone).
update help_article
set summary = 'We collect the order from your pickup location and deliver it to your customer.',
    sections = '[{"heading":"We collect and deliver","body":"With Fulfilled by AfroTransact you keep your stock. When a customer orders, AfroTransact collects the packed order from your pickup location and delivers it to the customer. You send nothing to us and pay no fulfilment fee."},
                 {"heading":"Delivery","body":"The customer pays the normal AfroTransact delivery fee (free when their membership plan includes free delivery). Delivery covers the areas AfroTransact serves around your location."},
                 {"heading":"Get started","body":"[Sell on AfroTransact](/sell), add a pickup location in Seller Center under Settings, Locations, and switch on that AfroTransact collects there. New offers then default to Fulfilled by AfroTransact; you can choose Pick up on any listing."}]'::jsonb,
    updated_at = now(), version = version + 1
where slug = 'fulfilled-by-afrotransact'
  and sections::text like '%fulfilment centre%';

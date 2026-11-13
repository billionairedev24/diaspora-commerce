-- S423: production must not ship demo-flavoured content. V2 inserted ten LIVE placements in every environment (a
-- "Shop by community" tile set, four hero slides, a sponsored banner, a pantry rail and three announcement bars that
-- promise stock). They are removed here, but only rows staff never touched (version 0, still created by the seed): a
-- placement staff edited has version > 0 and stays.
--
-- Local and dev stacks get their demo placements back from tools/seed (`make seed`), which refuses production, UAT and the
-- trial environment; it supersedes these ids when they exist and creates its own (with real photos) when they do not.
delete from placement
where id in ('plc_ann_free_delivery', 'plc_ann_festive_stock', 'plc_ann_seller_zero',
             'plc_hero_party_jollof', 'plc_hero_diwali', 'plc_hero_kingston_jerk', 'plc_hero_braider',
             'plc_tiles_communities', 'plc_row_pantry', 'plc_banner_kente')
  and created_by = 'seed'
  and version = 0;

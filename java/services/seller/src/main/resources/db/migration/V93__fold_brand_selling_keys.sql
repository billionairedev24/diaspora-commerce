-- Brand names in selling rules compare like the catalog brand registry (S630, catalog brand_key): lower case, accents
-- folded, everything but letters and digits removed, so "Hermes", "HERMES" and "Hermes" with an accent on the e are one
-- brand. The Java side (SellingScope.normaliseBrand) does the same on new keys; this migration folds the keys stored
-- before that: selling_rule, selling_approval and selling_application (BRAND and BRAND_IN_CATEGORY scopes; the category
-- half of "brand|category" is left alone).
--
-- Nothing is lost. Every key it changes, and every row it merges into another, is written to
-- selling_scope_key_fold_log with the full old row. Two rules that fold to one key merge into the stricter, active one
-- (the other's history and approvals follow it, and its old row is kept in the log and as a MERGE row in
-- selling_rule_audit). Two approvals of one organisation merge the same way (organisations are never mixed: the group
-- always includes org_id). Two open applications of one organisation for one brand keep the first and close the other as
-- a duplicate with an event, so the unique "one application in progress" index holds. Safe to run again: it only touches
-- rows whose key is not already folded.

create or replace function selling_brand_key(name text) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $$
    select regexp_replace(translate(lower(name), 'ÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝàáâãäåçèéêëìíîïñòóôõöùúûüýÿĀāĂăĄąĆćĈĉĊċČčĎďĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĨĩĪīĬĭĮįİĴĵĶķĹĺĻļĽľŃńŅņŇňŌōŎŏŐőŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŨũŪūŬŭŮůŰűŲųŴŵŶŷŸŹźŻżŽžƠơƯưǍǎǏǐǑǒǓǔǕǖǗǘǙǚǛǜǞǟǠǡǦǧǨǩǪǫǬǭǰǴǵǸǹǺǻȀȁȂȃȄȅȆȇȈȉȊȋȌȍȎȏȐȑȒȓȔȕȖȗȘșȚțȞȟȦȧȨȩȪȫȬȭȮȯȰȱȲȳḀḁḂḃḄḅḆḇḈḉḊḋḌḍḎḏḐḑḒḓḔḕḖḗḘḙḚḛḜḝḞḟḠḡḢḣḤḥḦḧḨḩḪḫḬḭḮḯḰḱḲḳḴḵḶḷḸḹḺḻḼḽḾḿṀṁṂṃṄṅṆṇṈṉṊṋṌṍṎṏṐṑṒṓṔṕṖṗṘṙṚṛṜṝṞṟṠṡṢṣṤṥṦṧṨṩṪṫṬṭṮṯṰṱṲṳṴṵṶṷṸṹṺṻṼṽṾṿẀẁẂẃẄẅẆẇẈẉẊẋẌẍẎẏẐẑẒẓẔẕẖẗẘẙẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹ', 'aaaaaaceeeeiiiinooooouuuuyaaaaaaceeeeiiiinooooouuuuyyaaaaaaccccccccddeeeeeeeeeegggggggghhiiiiiiiiijjkkllllllnnnnnnoooooorrrrrrssssssssttttuuuuuuuuuuuuwwyyyzzzzzzoouuaaiioouuuuuuuuuuaaaaggkkoooojggnnaaaaaaeeeeiiiioooorrrruuuusstthhaaeeooooooooyyaabbbbbbccddddddddddeeeeeeeeeeffgghhhhhhhhhhiiiikkkkkkllllllllmmmmmmnnnnnnnnoooooooopppprrrrrrrrssssssssssttttttttuuuuuuuuuuvvvvwwwwwwwwwwxxxxyyzzzzzzhtwyaaaaaaaaaaaaaaaaaaaaaaaaeeeeeeeeeeeeeeeeiiiioooooooooooooooooooooooouuuuuuuuuuuuuuyyyyyyyy'), '[[:punct:][:space:]‘’“”´–—•·]+', '', 'g')
$$;

comment on function selling_brand_key(text) is
  'Same key as catalog brand_key(): lower case, accents folded, only letters and digits. Used to fold brand selling-scope keys (V93).';

create or replace function selling_fold_scope_key(scope_type text, scope_key text) returns text
    language sql immutable as $$
    select case scope_type
        when 'BRAND' then selling_brand_key(scope_key)
        when 'BRAND_IN_CATEGORY' then case when position('|' in scope_key) = 0 then selling_brand_key(scope_key)
            else selling_brand_key(split_part(scope_key, '|', 1)) || '|' || substr(scope_key, position('|' in scope_key) + 1) end
        else scope_key end
$$;

create table if not exists selling_scope_key_fold_log (
    id          uuid         primary key default gen_random_uuid(),
    table_name  varchar(32)  not null,
    row_id      varchar(64)  not null,
    scope_type  varchar(24)  not null,
    old_key     varchar(200) not null,
    new_key     varchar(200) not null,
    action      varchar(24)  not null check (action in ('FOLDED', 'MERGED_INTO', 'DUPLICATE_CLOSED')),
    merged_into varchar(64),
    org_id      varchar(40),
    snapshot    jsonb        not null,
    folded_at   timestamptz  not null default now()
);
comment on table selling_scope_key_fold_log is
  'V93: every brand selling-scope key that was folded or merged, with the full old row. Read-only history.';

do $fold$
declare
    g record;
    win text;
    lose record;
begin
    ----------------------------------------------------------------------------------------------- rules
    for g in
        select r.scope_type, selling_fold_scope_key(r.scope_type, r.scope_key) as nk
        from selling_rule r
        where r.scope_type in ('BRAND', 'BRAND_IN_CATEGORY')
        group by 1, 2
        having bool_or(r.scope_key <> selling_fold_scope_key(r.scope_type, r.scope_key))
    loop
        select r.id::text into win from selling_rule r
        where r.scope_type = g.scope_type and selling_fold_scope_key(r.scope_type, r.scope_key) = g.nk
        order by r.active desc,
                 case r.level when 'NOT_AVAILABLE' then 3 when 'APPLY' then 2 else 1 end desc,
                 (r.scope_key = g.nk) desc, r.updated_at desc, r.id
        limit 1;
        for lose in
            select r.* from selling_rule r
            where r.scope_type = g.scope_type and selling_fold_scope_key(r.scope_type, r.scope_key) = g.nk
              and r.id::text <> win
        loop
            insert into selling_scope_key_fold_log (table_name, row_id, scope_type, old_key, new_key, action, merged_into, snapshot)
            values ('selling_rule', lose.id::text, lose.scope_type, lose.scope_key, g.nk, 'MERGED_INTO', win, to_jsonb(lose));
            insert into selling_rule_audit (id, rule_id, action, actor_id, actor_name, reason, before_json, after_json, changed_at)
            select gen_random_uuid(), w.id, 'MERGE', 'system', 'System', 'Brand name folded: "' || lose.scope_label
                   || '" (' || lose.scope_key || ') is the same brand as "' || w.scope_label || '"',
                   to_jsonb(lose), to_jsonb(w), now()
            from selling_rule w where w.id::text = win;
            update selling_rule_audit set rule_id = win::uuid where rule_id = lose.id;
            update selling_approval set rule_id = win::uuid where rule_id = lose.id;
            update selling_application set rule_id = win::uuid where rule_id = lose.id;
            delete from selling_rule where id = lose.id;
        end loop;
        insert into selling_scope_key_fold_log (table_name, row_id, scope_type, old_key, new_key, action, snapshot)
        select 'selling_rule', r.id::text, r.scope_type, r.scope_key, g.nk, 'FOLDED', to_jsonb(r)
        from selling_rule r where r.id::text = win and r.scope_key <> g.nk;
        insert into selling_rule_audit (id, rule_id, action, actor_id, actor_name, reason, before_json, after_json, changed_at)
        select gen_random_uuid(), r.id, 'KEY_FOLD', 'system', 'System',
               'Brand key folded like the catalog brand registry: ' || r.scope_key || ' to ' || g.nk,
               to_jsonb(r), to_jsonb(r) || jsonb_build_object('scope_key', g.nk), now()
        from selling_rule r where r.id::text = win and r.scope_key <> g.nk;
        update selling_rule set scope_key = g.nk, version = version + 1, updated_at = now()
        where id::text = win and scope_key <> g.nk;
    end loop;

    ------------------------------------------------------------------------------------------- approvals
    for g in
        select a.org_id, a.scope_type, selling_fold_scope_key(a.scope_type, a.scope_key) as nk
        from selling_approval a
        where a.scope_type in ('BRAND', 'BRAND_IN_CATEGORY')
        group by 1, 2, 3
        having bool_or(a.scope_key <> selling_fold_scope_key(a.scope_type, a.scope_key))
    loop
        select a.id::text into win from selling_approval a
        where a.org_id = g.org_id and a.scope_type = g.scope_type
          and selling_fold_scope_key(a.scope_type, a.scope_key) = g.nk
        order by (a.paused or a.status = 'NOT_AVAILABLE') desc,
                 case a.status when 'NOT_AVAILABLE' then 5 when 'APPROVED' then 4 when 'NEEDS_ATTENTION' then 3
                               when 'APPLIED' then 2 else 1 end desc,
                 a.expires_at desc nulls last, (a.scope_key = g.nk) desc, a.updated_at desc, a.id
        limit 1;
        for lose in
            select a.* from selling_approval a
            where a.org_id = g.org_id and a.scope_type = g.scope_type
              and selling_fold_scope_key(a.scope_type, a.scope_key) = g.nk and a.id::text <> win
        loop
            insert into selling_scope_key_fold_log (table_name, row_id, scope_type, old_key, new_key, action, merged_into, org_id, snapshot)
            values ('selling_approval', lose.id::text, lose.scope_type, lose.scope_key, g.nk, 'MERGED_INTO', win, lose.org_id, to_jsonb(lose));
            update selling_approval w set
                documents = case when w.documents = '[]'::jsonb then lose.documents else w.documents end,
                paused = w.paused or lose.paused,
                pause_reason = coalesce(w.pause_reason, lose.pause_reason),
                listings_paused = w.listings_paused or lose.listings_paused,
                application_id = coalesce(w.application_id, lose.application_id),
                version = w.version + 1, updated_at = now()
            where w.id::text = win;
            delete from selling_approval where id = lose.id;
        end loop;
        insert into selling_scope_key_fold_log (table_name, row_id, scope_type, old_key, new_key, action, org_id, snapshot)
        select 'selling_approval', a.id::text, a.scope_type, a.scope_key, g.nk, 'FOLDED', a.org_id, to_jsonb(a)
        from selling_approval a where a.id::text = win and a.scope_key <> g.nk;
        update selling_approval set scope_key = g.nk, version = version + 1, updated_at = now()
        where id::text = win and scope_key <> g.nk;
    end loop;

    ---------------------------------------------------------------------------------------- applications
    for g in
        select a.org_id, a.scope_type, selling_fold_scope_key(a.scope_type, a.scope_key) as nk
        from selling_application a
        where a.scope_type in ('BRAND', 'BRAND_IN_CATEGORY')
          and a.status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED')
        group by 1, 2, 3
        having bool_or(a.scope_key <> selling_fold_scope_key(a.scope_type, a.scope_key)) and count(*) > 1
    loop
        select a.id into win from selling_application a
        where a.org_id = g.org_id and a.scope_type = g.scope_type
          and selling_fold_scope_key(a.scope_type, a.scope_key) = g.nk
          and a.status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED')
        order by a.created_at, a.id limit 1;
        for lose in
            select a.* from selling_application a
            where a.org_id = g.org_id and a.scope_type = g.scope_type
              and selling_fold_scope_key(a.scope_type, a.scope_key) = g.nk
              and a.status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED')
              and a.id <> win
        loop
            insert into selling_scope_key_fold_log (table_name, row_id, scope_type, old_key, new_key, action, merged_into, org_id, snapshot)
            values ('selling_application', lose.id, lose.scope_type, lose.scope_key, g.nk, 'DUPLICATE_CLOSED', win, lose.org_id, to_jsonb(lose));
            insert into selling_application_event (id, application_id, action, from_status, to_status, actor_id, actor_name, note, occurred_at)
            values (gen_random_uuid(), lose.id, 'MERGED_DUPLICATE', lose.status, 'REJECTED', 'system', 'System',
                    'Closed as a duplicate of ' || (select reference from selling_application where id = win)
                    || ': the brand name is the same once accents and punctuation are ignored', now());
            update selling_application set status = 'REJECTED', decided_at = now(), updated_at = now(), version = version + 1,
                   decision_note = 'Closed as a duplicate of ' || (select reference from selling_application where id = win)
                   where id = lose.id;
            update selling_case set stage = null, assignee_id = null, assignee_name = null where application_id = lose.id;
        end loop;
    end loop;

    insert into selling_scope_key_fold_log (table_name, row_id, scope_type, old_key, new_key, action, org_id, snapshot)
    select 'selling_application', a.id, a.scope_type, a.scope_key, selling_fold_scope_key(a.scope_type, a.scope_key), 'FOLDED',
           a.org_id, to_jsonb(a)
    from selling_application a
    where a.scope_type in ('BRAND', 'BRAND_IN_CATEGORY')
      and a.scope_key <> selling_fold_scope_key(a.scope_type, a.scope_key);
    update selling_application set scope_key = selling_fold_scope_key(scope_type, scope_key)
    where scope_type in ('BRAND', 'BRAND_IN_CATEGORY') and scope_key <> selling_fold_scope_key(scope_type, scope_key);
end
$fold$;

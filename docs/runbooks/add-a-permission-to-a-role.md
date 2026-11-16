# Add a permission to a role

## An existing permission (no deploy)

1. Staff console > **Settings > Roles and permissions** (needs `access:admin` and Edit on Users & roles; Super Admin has both).
2. **Roles**, pick the portal, open the role. Find the permission (search, or filter by area).
3. Switch it on, say why (3 to 300 characters) and confirm. It is in the **Change log** with your name.
4. It reaches people's sign-ins within about 5 minutes (their next access token). Removing works the same way.
5. For one person only: **People**, search the name, open them, *Grant a permission directly* (confirm + reason). It shows as "Granted directly" and can be taken away.

## A new permission (a migration)

A permission does not exist for the screen until identity's catalogue has it. In `java/services/identity/src/main/resources/db/migration/V<next>__<name>.sql`:

```sql
insert into permission (key, module, description) values
    ('delivery:read', 'delivery', 'View delivery runs, stops and drivers')
on conflict (key) do update set module = excluded.module, description = excluded.description;
insert into role_permission (role_key, permission_key) values
    ('platform.dispatcher', 'delivery:read'), ('platform.super_admin', 'delivery:read')
on conflict do nothing;
```

- `module` is the heading it appears under; `description` is the plain sentence shown next to the switch. Write both for a non-technical reader.
- Grant it to roles in the same migration if it must work on day one; otherwise leave it for the screen. Super Admin needs it listed too.
- Update the README permission table and, if the Go BFF or a service checks the key, the check. **The console needs no change**: it reads the catalogue.
- Mock BFF (dev and tests): `python3 tools/identity/gen_access_fixture.py` regenerates its catalogue from the migrations.
- Deploy identity, then it appears in Settings > Roles and permissions under its module, off for every role not granted in the migration.

## Example: `ai:cap.manage` (change the daily AI spend cap)

V52 (`V52__ai_cap_manage_permission.sql`) adds it to the catalogue under `platform` and grants it to **Super Admin** and **Developer** only. Product Owner and the others keep
`ai:manage` (look at the cap and today's spend) and do not get it unless someone switches it on in Settings > Roles and permissions (with a reason). It is deliberately not a
client scope: service clients and the MCP server cannot change the cap (ai-gateway requires a staff principal), and a test keeps every MCP tool away from it. Numbering: V52 was
the next free identity version on `main` and `release-1`; check both (`ls java/services/identity/src/main/resources/db/migration | sort -V | tail -3`) before adding the next one.

## Worked example: `orders:override-shipment` (Super Admin only)

A permission that must **not** be handed out widely: it lets a person override the status of a shipment the seller ships
themselves (`SELLER_SHIPS`), forward only, with a required reason, audited.

1. Migration `identity V53__orders_override_shipment_permission.sql` adds the permission (module `orders`) and grants it to
   `platform.super_admin` only. It is idempotent and is the same file on `main`, `develop` and `release-1`.
2. Nobody else holds it. To give it to one more role: Settings > Roles and permissions > the role > search "override" > switch it
   on with a reason. For one person: People > the person > *Grant a permission directly*. Both are in the change log.
3. The check is on the server (orders-service `OrdersAccess.overrideActor`) and in gateway-bff, which only shows the
   **Override status** action to holders. Without it the order sheet says "Seller ships this order" and offers nothing.
4. Mock BFF catalogue: `python3 tools/identity/gen_access_fixture.py` (already run for V52).

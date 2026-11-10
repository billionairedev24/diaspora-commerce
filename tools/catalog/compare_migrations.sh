#!/usr/bin/env bash
# Compares two catalog databases built by different migration chains (for example the pre-baseline chain V1..V20
# against the baseline V1__baseline.sql + V2__seed_initial_data.sql) and exits 1 on any difference.
#
#   tools/catalog/compare_migrations.sh DB_A DB_B [OUT_DIR]
#
# Connection: the usual libpq variables (PGHOST, PGPORT, PGUSER, PGPASSWORD). The two databases must already exist and
# be migrated (the catalog service or an IT start applies the migrations; the Flyway history table is not compared).
#
#   1. Schema: `pg_dump --schema-only` without owners, privileges, the Flyway history table, `SET` noise and comments,
#      compared in dump order and again with the lines sorted (so a different but equivalent creation order is told
#      apart from a real difference).
#   2. Data: every table of the public schema, one canonical JSON line per row, sorted, with timestamp columns removed
#      (created_at, updated_at, ... differ by construction). `brand` rows are random uuids in a database built by the old
#      V13 chain and deterministic ones in the baseline, so brands (and brand_scope) are compared by name and by the
#      code of the scoped node instead of by id.
set -euo pipefail

A=${1:?usage: compare_migrations.sh DB_A DB_B [OUT_DIR]}
B=${2:?usage: compare_migrations.sh DB_A DB_B [OUT_DIR]}
OUT=${3:-$(mktemp -d)}
mkdir -p "$OUT"
status=0

normalise_schema() {
  pg_dump --schema-only --no-owner --no-privileges --no-comments -T flyway_schema_history "$1" \
    | grep -v -E '^(--|SET |SELECT pg_catalog.set_config|\\restrict|\\unrestrict)' \
    | sed -e 's/[[:space:]]*$//' | cat -s
}

for db in "$A" "$B"; do
  normalise_schema "$db" > "$OUT/$db.schema.sql"
  sort "$OUT/$db.schema.sql" > "$OUT/$db.schema.sorted.sql"
done

echo "== schema (pg_dump --schema-only, normalised)"
if diff -u "$OUT/$A.schema.sql" "$OUT/$B.schema.sql" > "$OUT/schema.diff"; then
  echo "   identical in dump order ($(wc -l < "$OUT/$A.schema.sql") lines)"
else
  status=1
  echo "   DIFFERENT in dump order, see $OUT/schema.diff"
  if diff -u "$OUT/$A.schema.sorted.sql" "$OUT/$B.schema.sorted.sql" > "$OUT/schema.sorted.diff"; then
    echo "   identical once the lines are sorted (same objects, different order)"
  else
    echo "   DIFFERENT even sorted, see $OUT/schema.sorted.diff"
  fi
fi

# One canonical line per row; the timestamp columns of each table are found in information_schema.
data_sql() {
  psql -X -q -At -v ON_ERROR_STOP=1 "$1" <<'SQL'
create temporary table _cols as
  select table_name, array_agg(column_name order by ordinal_position) filter (where data_type like 'timestamp%') as ts
  from information_schema.columns where table_schema = 'public' group by table_name;
do $$
declare r record; q text;
begin
  create temporary table _rows (tbl text, line text);
  for r in select c.table_name, c.ts from _cols c join information_schema.tables t
             on t.table_schema = 'public' and t.table_name = c.table_name and t.table_type = 'BASE TABLE'
           where c.table_name not in ('flyway_schema_history', 'brand', 'brand_scope') loop
    q := format('insert into _rows select %L, (to_jsonb(x) - coalesce(%L::text[], ''{}''))::text from %I x',
                r.table_name, r.ts, r.table_name);
    execute q;
  end loop;
  insert into _rows select 'brand', (to_jsonb(b) - array['id', 'merged_into_id', 'created_at', 'updated_at', 'decided_at'])::text
    || coalesce(' merged_into=' || (select m.name from brand m where m.id = b.merged_into_id), '') from brand b;
  insert into _rows select 'brand_scope', b.name || ' -> ' || c.code
    from brand_scope s join brand b on b.id = s.brand_id join category c on c.id = s.node_id;
end $$;
select tbl || ' ' || line from _rows order by 1;
select '#count ' || tbl || ' ' || count(*) from _rows group by tbl order by 1;
SQL
}

for db in "$A" "$B"; do
  data_sql "$db" > "$OUT/$db.data.txt"
done
echo "== data (every public table except flyway_schema_history; timestamps ignored; brands by name)"
grep '^#count' "$OUT/$A.data.txt" | awk '{printf "   %-22s %s rows\n", $2, $3}'
if diff -u "$OUT/$A.data.txt" "$OUT/$B.data.txt" > "$OUT/data.diff"; then
  echo "   identical ($(grep -c -v '^#count' "$OUT/$A.data.txt") rows)"
else
  status=1
  echo "   DIFFERENT, see $OUT/data.diff ($(grep -c '^[-+][^-+]' "$OUT/data.diff") differing lines)"
fi
echo "== output in $OUT"
exit $status

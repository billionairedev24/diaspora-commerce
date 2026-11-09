// Copies the parts of tools/taxonomy/category_tax_map.json the staff tax mocks need (73 categories, their facts and decision
// rows, 13 departments, fact definitions) into src/mocks/staff/fixtures/category-tax-map.json, so the mocks show the real
// dataset without importing from outside the package.
//   node scripts/sync-tax-fixture.mjs          → writes the fixture
//   node scripts/sync-tax-fixture.mjs --check  → exits 1 when it is stale (run by the staff-tax test)
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, '../../../../tools/taxonomy/category_tax_map.json');
const target = resolve(here, '../src/mocks/staff/fixtures/category-tax-map.json');

const map = JSON.parse(readFileSync(source, 'utf8'));
const slim = {
  ruleVersion: map.ruleVersion,
  decisions: map.decisions,
  departments: map.departments,
  facts: map.facts,
  categories: map.categories.map((c) => ({
    code: c.code,
    department: c.department,
    name: c.name,
    stripeCodes: c.stripeCodes,
    conflictingCodes: c.conflictingCodes,
    defaultOutcome: c.defaultOutcome,
    status: c.status,
    statusReasons: c.statusReasons,
    facts: c.facts,
    decisionTable: c.decisionTable,
    note: c.note ?? null
  }))
};
const text = `${JSON.stringify(slim, null, 1)}\n`;
if (process.argv.includes('--check')) {
  const current = existsSync(target) ? readFileSync(target, 'utf8') : '';
  if (current !== text) {
    console.error('stale: src/mocks/staff/fixtures/category-tax-map.json, run node scripts/sync-tax-fixture.mjs');
    process.exit(1);
  }
} else {
  writeFileSync(target, text);
  console.log('wrote src/mocks/staff/fixtures/category-tax-map.json');
}

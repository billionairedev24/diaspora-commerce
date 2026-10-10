#!/usr/bin/env node
// Prebuild step for the developer portal. Everything it writes is generated and gitignored
// (see docs/portal/.gitignore); never edit the outputs by hand.
//
//   contracts/**                       -> static/contracts/**            (raw YAML, downloadable)
//   contracts/openapi/*.yaml           -> docs/api/<name>.mdx            (Redoc + Scalar page each, discovered by glob)
//   contracts/asyncapi/events.yaml     -> src/generated/events.json      (event catalog data)
//                                      -> docs/events/topics/<topic>.mdx (one page per Kafka topic)
//   {java,go}/services/*/README.md     -> docs/services/<name>.md        (+ PORTS.md rows for services without a README)
//   CLAUDE.md, docs/engineering/*.md   -> docs/engineering/guides/*.md
//   docs/runbooks/*.md                 -> docs/runbooks/*.md
//   docs/adr/**/*.md (if present)      -> docs/architecture/adr/**
//   infra/local/PORTS.md, Makefile     -> docs/getting-started/{ports,make-targets}.md
//   docs/backlog/backlog.csv           -> src/generated/backlog.json
//   mcp-server tool sources (or $MCP_TOOLS_URL/v1/tools) -> src/generated/mcp-tools.json
//
// Run: node scripts/sync-contracts.mjs   (runs automatically before `start`, `build` and `typecheck`)
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { basename, dirname, extname, join, posix, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import YAML from 'yaml';

const PORTAL = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = resolve(PORTAL, '../..');
const DOCS = join(PORTAL, 'docs');
const STATIC = join(PORTAL, 'static');
const GEN = join(PORTAL, 'src/generated');
const REPO_URL = process.env.REPO_URL ?? 'https://github.com/billionairedev24/diaspora-commerce';
const REPO_BRANCH = process.env.REPO_BRANCH ?? 'main';

const warnings = [];
const warn = (msg) => {
  warnings.push(msg);
  console.warn(`[sync] warn: ${msg}`);
};

// ── helpers ──────────────────────────────────────────────────────────────────────────────────────
const rel = (p) => relative(ROOT, p).split('\\').join('/');
const read = (p) => readFileSync(p, 'utf8');
const write = (p, text) => {
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, text);
};
const isDir = (p) => existsSync(p) && statSync(p).isDirectory();
const isFile = (p) => existsSync(p) && statSync(p).isFile();
const list = (dir, filter = () => true) =>
  isDir(dir) ? readdirSync(dir).filter((n) => filter(n)).sort((a, b) => a.localeCompare(b)) : [];
const yamlString = (s) => JSON.stringify(String(s ?? ''));
const firstSentence = (s, max = 180) => {
  const text = String(s ?? '').replace(/\s+/g, ' ').trim();
  const cut = text.match(/^(.+?[.!?])(\s|$)/);
  const out = cut ? cut[1] : text;
  return out.length > max ? `${out.slice(0, max - 1)}…` : out;
};
const frontMatter = (fields) =>
  `---\n${Object.entries(fields)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}: ${typeof v === 'string' ? yamlString(v) : JSON.stringify(v)}`)
    .join('\n')}\n---\n\n`;
const blob = (repoPath) => `${REPO_URL}/blob/${REPO_BRANCH}/${repoPath}`;
const tree = (repoPath) => `${REPO_URL}/tree/${REPO_BRANCH}/${repoPath}`;
const editUrl = (repoPath) => `${REPO_URL}/edit/${REPO_BRANCH}/${repoPath}`;
const titleCase = (s) => s.replace(/(^|[-_ ])(\w)/g, (_, sep, c) => (sep ? ' ' : '') + c.toUpperCase());
const slugify = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const mdCell = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\s*\n\s*/g, ' ');

// ── 0. clean previous output ─────────────────────────────────────────────────────────────────────
const GENERATED = [
  'docs/api',
  'docs/services',
  'docs/runbooks',
  'docs/engineering/guides',
  'docs/events/topics',
  'docs/architecture/adr',
  'docs/getting-started/ports.md',
  'docs/getting-started/make-targets.md',
  'static/contracts',
  'static/fonts',
  'static/img/logo-mark.svg',
  'src/generated',
];
for (const p of GENERATED) rmSync(join(PORTAL, p), { recursive: true, force: true });
mkdirSync(GEN, { recursive: true });

// ── 1. repo document map (repo path -> generated doc path, relative to docs/) ────────────────────
// Filled before any markdown is written so cross-links between copied files resolve to portal pages.
const docMap = new Map();

// Services: PORTS.md rows + directories under java/services and go/services.
const portsPath = join(ROOT, 'infra/local/PORTS.md');
const ports = new Map();
if (isFile(portsPath)) {
  for (const line of read(portsPath).split('\n')) {
    const cells = line.split('|').slice(1, -1).map((c) => c.trim());
    if (cells.length >= 5 && /^\d+$/.test(cells[2]) && !/^-+$/.test(cells[0])) {
      ports.set(cells[0], { runtime: cells[1], port: Number(cells[2]), owns: cells[3], store: cells[4] });
    }
  }
}
const services = new Map();
for (const [lang, dir] of [
  ['java', 'java/services'],
  ['go', 'go/services'],
]) {
  for (const name of list(join(ROOT, dir), (n) => isDir(join(ROOT, dir, n)))) {
    const readme = join(ROOT, dir, name, 'README.md');
    services.set(name, { name, lang, dir: `${dir}/${name}`, readme: isFile(readme) ? readme : null });
  }
}
for (const name of ports.keys()) if (!services.has(name)) services.set(name, { name, lang: null, dir: null, readme: null });
for (const svc of services.values()) {
  svc.ports = ports.get(svc.name) ?? null;
  if (!svc.lang && svc.ports) svc.lang = /go/i.test(svc.ports.runtime) ? 'go' : 'java';
  if (svc.readme) docMap.set(rel(svc.readme), `services/${svc.name}.md`);
}

const engineeringSources = [];
if (isFile(join(ROOT, 'CLAUDE.md'))) engineeringSources.push([join(ROOT, 'CLAUDE.md'), 'conventions']);
for (const f of list(join(ROOT, 'docs/engineering'), (n) => n.endsWith('.md'))) {
  engineeringSources.push([join(ROOT, 'docs/engineering', f), basename(f, '.md')]);
}
for (const [src, slug] of engineeringSources) docMap.set(rel(src), `engineering/guides/${slug}.md`);

const runbookSources = list(join(ROOT, 'docs/runbooks'), (n) => n.endsWith('.md')).map((f) => [
  join(ROOT, 'docs/runbooks', f),
  f === 'README.md' ? 'index' : basename(f, '.md'),
]);
for (const [src, slug] of runbookSources) docMap.set(rel(src), `runbooks/${slug}.md`);

const adrSources = [];
const walkMd = (dir, base = dir) => {
  for (const n of list(dir)) {
    const p = join(dir, n);
    if (isDir(p)) walkMd(p, base);
    else if (n.endsWith('.md')) adrSources.push([p, relative(base, p).split('\\').join('/').replace(/\.md$/, '')]);
  }
};
walkMd(join(ROOT, 'docs/adr'));
for (const [src, slug] of adrSources) {
  docMap.set(rel(src), `architecture/adr/${slug.replace(/(^|\/)README$/, '$1index')}.md`);
}
if (isFile(portsPath)) docMap.set('infra/local/PORTS.md', 'getting-started/ports.md');

// Contracts are discovered by glob (new services keep adding them); never hard-code the list.
const openapiDir = join(ROOT, 'contracts/openapi');
const contractFiles = list(openapiDir, (n) => /\.(ya?ml|json)$/.test(n));
for (const f of contractFiles) docMap.set(`contracts/openapi/${f}`, `api/${basename(f, extname(f))}.mdx`);
const eventsPath = join(ROOT, 'contracts/asyncapi/events.yaml');
docMap.set('contracts/asyncapi/events.yaml', 'events/index.mdx');

// ── 2. markdown link rewriting ───────────────────────────────────────────────────────────────────
function resolveLink(target, srcRepoPath, destDocPath, isImage) {
  if (/^([a-z][a-z0-9+.-]*:|#|\/\/)/i.test(target)) return target; // external, mailto, anchors
  const [pathPart, hash = ''] = target.split(/#(.*)/s);
  const anchor = hash ? `#${hash}` : '';
  if (!pathPart) return target;
  const repoPath = pathPart.startsWith('/')
    ? posix.normalize(pathPart.slice(1))
    : posix.normalize(posix.join(posix.dirname(srcRepoPath), decodeURI(pathPart)));
  const clean = repoPath.replace(/\/$/, '');
  if (clean.startsWith('..')) return target;
  if (!isImage) {
    let doc = docMap.get(clean);
    if (!doc && clean.startsWith('contracts/asyncapi/')) doc = 'events/index.mdx';
    if (!doc && isDir(join(ROOT, clean)) && docMap.has(`${clean}/README.md`)) doc = docMap.get(`${clean}/README.md`);
    if (doc) {
      const r = posix.relative(posix.dirname(destDocPath), doc);
      return (r.startsWith('.') ? r : `./${r}`) + anchor;
    }
  }
  if (isImage) return `${REPO_URL}/raw/${REPO_BRANCH}/${clean}`;
  return (isDir(join(ROOT, clean)) ? tree(clean) : blob(clean)) + anchor;
}

function rewriteMarkdown(md, srcRepoPath, destDocPath) {
  // Leave fenced code untouched.
  const parts = md.split(/(^(?:```|~~~)[^\n]*\n[\s\S]*?^(?:```|~~~)[ \t]*$)/m);
  return parts
    .map((part, i) => {
      if (i % 2 === 1) return part;
      return part
        .replace(/(!?)\[((?:[^\]\[]|\[[^\]]*\])*)\]\(\s*<?([^)\s>]+)>?(\s+"[^"]*")?\s*\)/g, (_m, bang, text, url, title = '') => {
          return `${bang}[${text}](${resolveLink(url, srcRepoPath, destDocPath, bang === '!')}${title})`;
        })
        .replace(/^(\s{0,3}\[[^\]]+\]:\s*)(\S+)/gm, (_m, lead, url) => lead + resolveLink(url, srcRepoPath, destDocPath, false));
    })
    .join('');
}

function splitTitle(md, fallback) {
  const m = md.match(/^\s*#\s+(.+?)\s*#*\s*$/m);
  if (m && md.slice(0, m.index).trim() === '') {
    return { title: m[1].replace(/[`*_]/g, '').trim(), body: md.slice(m.index + m[0].length).replace(/^\s*\n/, '') };
  }
  return { title: fallback, body: md };
}

function copyMarkdown(src, destDocPath, extra = {}) {
  const repoPath = rel(src);
  const { title, body } = splitTitle(read(src), titleCase(basename(destDocPath, '.md')));
  const fm = frontMatter({
    title: extra.title ?? title,
    sidebar_label: extra.sidebar_label ?? extra.title ?? title,
    description: extra.description,
    sidebar_position: extra.sidebar_position,
    custom_edit_url: editUrl(repoPath),
  });
  const notice = `<!-- GENERATED from ${repoPath} by docs/portal/scripts/sync-contracts.mjs — edit the source file. -->\n\n`;
  write(join(DOCS, destDocPath), fm + notice + (extra.prefix ?? '') + rewriteMarkdown(body, repoPath, destDocPath));
}

// ── 3. contracts -> static/contracts + API reference pages ──────────────────────────────────────
if (isDir(join(ROOT, 'contracts'))) cpSync(join(ROOT, 'contracts'), join(STATIC, 'contracts'), { recursive: true });

const apis = [];
for (const file of contractFiles) {
  const name = basename(file, extname(file));
  const entry = { name, file, url: `/contracts/openapi/${file}`, title: name, version: '', description: '', ops: 0, paths: 0, tags: [], servers: [], error: null };
  try {
    const doc = YAML.parse(read(join(openapiDir, file)), { maxAliasCount: -1 });
    if (!doc || typeof doc !== 'object') throw new Error('empty document');
    entry.openapi = doc.openapi ?? doc.swagger ?? '';
    entry.title = doc.info?.title ?? name;
    entry.version = String(doc.info?.version ?? '');
    entry.description = doc.info?.description ?? '';
    entry.paths = Object.keys(doc.paths ?? {}).length;
    for (const item of Object.values(doc.paths ?? {})) {
      for (const m of ['get', 'put', 'post', 'delete', 'patch', 'options', 'head', 'trace']) if (item?.[m]) entry.ops += 1;
    }
    entry.tags = (doc.tags ?? []).map((t) => t.name);
    entry.servers = (doc.servers ?? []).map((s) => s.url);
  } catch (e) {
    entry.error = String(e.message ?? e).split('\n')[0];
    warn(`contracts/openapi/${file} does not parse: ${entry.error}`);
  }
  apis.push(entry);
}
const serviceForContract = (name) => (services.has(name) ? name : null);

for (const [i, api] of apis.entries()) {
  const svc = serviceForContract(api.name);
  const props = {
    name: api.name,
    title: api.title,
    version: api.version,
    specUrl: api.url,
    summary: firstSentence(api.description, 400),
    operations: api.ops,
    paths: api.paths,
    servers: api.servers,
    error: api.error,
    sourceUrl: blob(`contracts/openapi/${api.file}`),
  };
  const body =
    frontMatter({
      title: `${api.title}`,
      sidebar_label: api.name,
      description: firstSentence(api.description) || `OpenAPI contract of ${api.name}`,
      sidebar_position: i + 1,
      hide_table_of_contents: true,
      custom_edit_url: editUrl(`contracts/openapi/${api.file}`),
    }) +
    `{/* GENERATED from contracts/openapi/${api.file} by scripts/sync-contracts.mjs */}\n\n` +
    `import ApiReference from '@site/src/components/ApiReference';\n\n` +
    (svc ? `Owned by the [**${svc}** service](../services/${svc}.md). ` : '') +
    `Source: [\`contracts/openapi/${api.file}\`](${blob(`contracts/openapi/${api.file}`)}).\n\n` +
    `<ApiReference {...${JSON.stringify(props)}} />\n`;
  write(join(DOCS, `api/${api.name}.mdx`), body);
}
write(
  join(DOCS, 'api/index.mdx'),
  frontMatter({ title: 'API reference', sidebar_label: 'Overview', sidebar_position: 0, description: 'Every OpenAPI 3.1 contract, rendered with Redoc and Scalar.' }) +
    `{/* GENERATED by scripts/sync-contracts.mjs */}\n\n` +
    `Every service is contract first: \`contracts/openapi/<service>.yaml\` (OpenAPI 3.1) is the source of truth and the\n` +
    `running service serves the same document at \`/v3/api-docs\` (Java) or \`/openapi.yaml\` (Go). Each page below offers a\n` +
    `**Redoc** view, an interactive **Scalar** view and the raw YAML. Contracts are discovered at build time, so a new\n` +
    `file in \`contracts/openapi/\` shows up here on the next build.\n\n` +
    `| Contract | Title | Version | Operations | Download |\n|---|---|---|---|---|\n` +
    apis
      .map(
        (a) =>
          `| [${a.name}](./${a.name}.mdx) | ${mdCell(a.title)}${a.error ? ' ⚠ does not parse' : ''} | ${mdCell(a.version)} | ${a.ops} | <a href="${a.url}" download>${a.file}</a> |`,
      )
      .join('\n') +
    `\n\nTypeScript clients are generated from the BFF contracts, see [TypeScript clients](../engineering/typescript-clients.md).\n`,
);
write(join(DOCS, 'api/_category_.json'), JSON.stringify({ label: 'API reference', position: 6 }, null, 2));
write(join(GEN, 'contracts.json'), JSON.stringify(apis.map(({ description, ...a }) => a), null, 2));

// ── 4. service pages ─────────────────────────────────────────────────────────────────────────────
const sortedServices = [...services.values()].sort((a, b) => {
  const pa = a.ports?.port ?? 99999;
  const pb = b.ports?.port ?? 99999;
  return pa - pb || a.name.localeCompare(b.name);
});
for (const [i, svc] of sortedServices.entries()) {
  const dest = `services/${svc.name}.md`;
  const contract = apis.find((a) => a.name === svc.name);
  const facts = [
    ['Runtime', svc.ports?.runtime ?? (svc.lang === 'go' ? 'Go' : 'Spring Boot')],
    ['Local port', svc.ports ? `\`${svc.ports.port}\`` : '—'],
    ['Store', svc.ports?.store ?? '—'],
    ['API reference', contract ? `[${contract.file}](../api/${contract.name}.mdx)` : 'no contract yet'],
    ['Source', svc.dir ? `[\`${svc.dir}\`](${tree(svc.dir)})` : 'not created yet'],
  ];
  const table =
    (svc.ports?.owns ? `**Owns:** ${svc.ports.owns}\n\n` : '') +
    `| ${facts.map(([k]) => k).join(' | ')} |\n|${facts.map(() => '---').join('|')}|\n| ${facts.map(([, v]) => mdCell(v)).join(' | ')} |\n\n`;
  if (svc.readme) {
    copyMarkdown(svc.readme, dest, { title: svc.name, sidebar_label: svc.name, sidebar_position: i + 1, prefix: table, description: svc.ports?.owns });
  } else {
    write(
      join(DOCS, dest),
      frontMatter({ title: svc.name, sidebar_label: svc.name, sidebar_position: i + 1, description: svc.ports?.owns }) +
        `<!-- GENERATED by scripts/sync-contracts.mjs from infra/local/PORTS.md -->\n\n` +
        table +
        `:::note\n\nThis service has no README yet${svc.dir ? ` (\`${svc.dir}/README.md\`)` : ''}. ` +
        `The page is generated from \`infra/local/PORTS.md\` and fills in automatically once the README exists.\n\n:::\n`,
    );
  }
}
write(
  join(DOCS, 'services/index.md'),
  frontMatter({ title: 'Services', sidebar_label: 'Overview', sidebar_position: 0, description: 'Every back-end service, its port, store and contract.' }) +
    `<!-- GENERATED by scripts/sync-contracts.mjs -->\n\n` +
    `One page per service, generated from its README and the service catalogue in [ports](../getting-started/ports.md).\n\n` +
    `| Service | Runtime | Port | Owns | Contract |\n|---|---|---|---|---|\n` +
    sortedServices
      .map((s) => {
        const c = apis.find((a) => a.name === s.name);
        return `| [${s.name}](./${s.name}.md) | ${mdCell(s.ports?.runtime ?? s.lang ?? '')} | ${s.ports?.port ?? ''} | ${mdCell(s.ports?.owns ?? '')} | ${c ? `[API](../api/${c.name}.mdx)` : '—'} |`;
      })
      .join('\n') +
    '\n',
);

write(join(DOCS, 'services/_category_.json'), JSON.stringify({ label: 'Services', position: 5 }, null, 2));

// ── 5. engineering guides, runbooks, ADRs, ports ────────────────────────────────────────────────
for (const [i, [src, slug]] of engineeringSources.entries()) {
  copyMarkdown(src, `engineering/guides/${slug}.md`, {
    sidebar_position: i + 1,
    ...(slug === 'conventions' ? { title: 'Engineering conventions (CLAUDE.md)', sidebar_label: 'Conventions' } : {}),
  });
}
write(join(DOCS, 'engineering/guides/_category_.json'), JSON.stringify({ label: 'Guides', position: 1, collapsed: false }, null, 2));

for (const [src, slug] of runbookSources) {
  copyMarkdown(src, `runbooks/${slug}.md`, slug === 'index' ? { sidebar_label: 'Overview', sidebar_position: 0 } : {});
}
write(join(DOCS, 'runbooks/_category_.json'), JSON.stringify({ label: 'Runbooks', position: 9 }, null, 2));
if (adrSources.length) {
  for (const [src] of adrSources) copyMarkdown(src, docMap.get(rel(src)));
  write(join(DOCS, 'architecture/adr/_category_.json'), JSON.stringify({ label: 'ADRs', position: 10 }, null, 2));
}
if (isFile(portsPath)) {
  copyMarkdown(portsPath, 'getting-started/ports.md', { title: 'Service catalogue and local ports', sidebar_label: 'Ports', sidebar_position: 2 });
}

// Make targets (documented with "## " comments in the root Makefile).
const makefile = join(ROOT, 'Makefile');
if (isFile(makefile)) {
  const targets = [...read(makefile).matchAll(/^([a-zA-Z0-9_.-]+):[^\n#]*##\s*(.+)$/gm)].map((m) => [m[1], m[2].trim()]);
  write(
    join(DOCS, 'getting-started/make-targets.md'),
    frontMatter({ title: 'Make targets', sidebar_position: 3, custom_edit_url: editUrl('Makefile') }) +
      `<!-- GENERATED from Makefile by scripts/sync-contracts.mjs -->\n\n` +
      `The root \`Makefile\` wraps the local stack and the checks CI runs. \`make help\` prints this list.\n\n` +
      `| Target | What it does |\n|---|---|\n` +
      targets.map(([t, d]) => `| \`make ${t}\` | ${mdCell(d)} |`).join('\n') +
      '\n',
  );
}

// ── 6. AsyncAPI event catalog ────────────────────────────────────────────────────────────────────
const events = { info: {}, servers: [], topics: [], messages: {}, eventTypes: [], schemas: {}, problems: [], services: [...services.keys()].sort() };
if (isFile(eventsPath)) {
  const doc = YAML.parse(read(eventsPath), { maxAliasCount: -1 });
  events.info = { title: doc.info?.title, version: doc.info?.version, description: doc.info?.description, asyncapi: doc.asyncapi };
  events.servers = Object.entries(doc.servers ?? {}).map(([id, s]) => ({ id, host: s.host, protocol: s.protocol, description: s.description }));
  events.schemas = doc.components?.schemas ?? {};

  // operation id -> owning service, from the fragment that declares it.
  const opService = new Map();
  const fragDir = join(ROOT, 'contracts/asyncapi/fragments');
  for (const f of list(fragDir, (n) => /\.ya?ml$/.test(n))) {
    try {
      const frag = YAML.parse(read(join(fragDir, f)));
      for (const op of Object.keys(frag?.operations ?? {})) opService.set(op, basename(f, extname(f)));
    } catch (e) {
      warn(`contracts/asyncapi/fragments/${f} does not parse: ${e.message}`);
    }
  }
  const refKey = (ref, prefix) => (typeof ref === 'string' && ref.startsWith(prefix) ? decodeURIComponent(ref.slice(prefix.length)) : null);
  const channelMsgKey = (channel, ref) => {
    // Channel message entries are {$ref: '#/components/messages/x'} (or inline, keyed by name).
    const m = channel.messages?.[ref];
    return refKey(m?.$ref, '#/components/messages/') ?? ref;
  };
  const channels = new Map();
  for (const [id, ch] of Object.entries(doc.channels ?? {})) {
    channels.set(id, {
      id,
      address: ch.address ?? id,
      title: ch.title,
      description: ch.description ?? '',
      messageKeys: Object.keys(ch.messages ?? {}).map((k) => channelMsgKey(ch, k)),
      raw: ch,
    });
  }
  const ops = Object.entries(doc.operations ?? {}).map(([id, op]) => {
    const channelId = refKey(op.channel?.$ref, '#/channels/');
    const ch = channels.get(channelId);
    let messageKeys = ch?.messageKeys ?? [];
    if (Array.isArray(op.messages) && op.messages.length && ch) {
      messageKeys = op.messages
        .map((m) => {
          const parts = String(m.$ref ?? '').split('/');
          return parts[1] === 'channels' ? channelMsgKey(ch.raw, decodeURIComponent(parts[4])) : refKey(m.$ref, '#/components/messages/');
        })
        .filter(Boolean);
    }
    let service = opService.get(id);
    if (!service) {
      service = 'unknown';
      events.problems.push(`operation ${id} is not declared by any fragment`);
    }
    if (!ch) events.problems.push(`operation ${id} references unknown channel ${op.channel?.$ref}`);
    return { id, action: op.action, channelId, address: ch?.address, service, summary: op.summary ?? '', description: op.description ?? '', messageKeys };
  });

  const comps = doc.components?.messages ?? {};
  for (const [key, msg] of Object.entries(comps)) {
    events.messages[key] = {
      key,
      name: msg.name ?? key,
      title: msg.title ?? '',
      summary: msg.summary ?? '',
      description: msg.description ?? '',
      contentType: msg.contentType ?? doc.defaultContentType ?? '',
      payload: msg.payload ?? null,
      headers: msg.headers ?? null,
      examples: msg.examples ?? [],
      topics: [],
    };
  }

  const byAddress = new Map();
  for (const ch of channels.values()) {
    if (!byAddress.has(ch.address)) byAddress.set(ch.address, []);
    byAddress.get(ch.address).push(ch);
  }
  for (const [address, chs] of [...byAddress.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    const chIds = new Set(chs.map((c) => c.id));
    const topicOps = ops.filter((o) => chIds.has(o.channelId));
    const party = (action) =>
      topicOps
        .filter((o) => o.action === action)
        .map((o) => ({ service: o.service, operation: o.id, summary: o.summary, description: o.description, channel: o.channelId, messageKeys: o.messageKeys }));
    const messageKeys = [...new Set(chs.flatMap((c) => c.messageKeys))];
    for (const k of messageKeys) {
      if (events.messages[k]) {
        if (!events.messages[k].topics.includes(address)) events.messages[k].topics.push(address);
      } else events.problems.push(`channel on ${address} references missing message ${k}`);
    }
    const topic = {
      address,
      slug: slugify(address),
      channels: chs.map((c) => ({ id: c.id, title: c.title, description: c.description, messageKeys: c.messageKeys })),
      producers: party('send'),
      consumers: party('receive'),
      messageKeys,
    };
    if (!topic.producers.length) events.problems.push(`topic ${address} has consumers but no documented producer (send operation)`);
    events.topics.push(topic);
  }

  // Event types (message `name`, e.g. catalog.product.created) across topics; published = reachable from a send op.
  const types = new Map();
  for (const t of events.topics) {
    for (const k of t.messageKeys) {
      const m = events.messages[k];
      if (!m) continue;
      if (!types.has(m.name)) types.set(m.name, { name: m.name, title: '', keys: [], topics: [], producers: [], consumers: [] });
      const ty = types.get(m.name);
      if (!ty.keys.includes(k)) ty.keys.push(k);
      if (!ty.title) ty.title = m.title || m.summary;
      if (!ty.topics.includes(t.address)) ty.topics.push(t.address);
      for (const p of t.producers) if (p.messageKeys.includes(k) && !ty.producers.includes(p.service)) ty.producers.push(p.service);
      for (const c of t.consumers) if (c.messageKeys.includes(k) && !ty.consumers.includes(c.service)) ty.consumers.push(c.service);
    }
  }
  for (const [key, m] of Object.entries(events.messages)) if (!m.topics.length) events.problems.push(`message ${key} (${m.name}) is not on any channel`);
  events.eventTypes = [...types.values()].sort((a, b) => a.name.localeCompare(b.name));

  for (const [i, t] of events.topics.entries()) {
    const desc = firstSentence(t.channels.map((c) => c.description).find(Boolean) ?? '');
    write(
      join(DOCS, `events/topics/${t.slug}.mdx`),
      frontMatter({ title: t.address, sidebar_label: t.address, sidebar_position: i + 1, description: desc || `Kafka topic ${t.address}`, hide_table_of_contents: true }) +
        `{/* GENERATED from contracts/asyncapi/events.yaml by scripts/sync-contracts.mjs */}\n\n` +
        `import { TopicView } from '@site/src/components/EventCatalog';\n\n<TopicView address=${JSON.stringify(t.address)} />\n`,
    );
  }
  write(join(DOCS, 'events/topics/_category_.json'), JSON.stringify({ label: 'Topics', position: 2, collapsed: false }, null, 2));
} else {
  warn('contracts/asyncapi/events.yaml is missing — event catalog is empty');
}
write(join(GEN, 'events.json'), JSON.stringify(events));

// ── 7. backlog status ────────────────────────────────────────────────────────────────────────────
function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') {
      row.push(field);
      field = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      field = '';
      if (row.some((f) => f !== '')) rows.push(row);
      row = [];
    } else field += c;
  }
  if (field !== '' || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
const backlogPath = join(ROOT, 'docs/backlog/backlog.csv');
const backlog = { generatedFrom: 'docs/backlog/backlog.csv', epics: [], stories: [] };
if (isFile(backlogPath)) {
  const [header, ...rows] = parseCsv(read(backlogPath));
  const col = (name) => header.indexOf(name);
  const get = (r, name) => (col(name) >= 0 ? (r[col(name)] ?? '').trim() : '');
  const items = rows.map((r) => ({
    id: get(r, 'Issue Id'),
    type: get(r, 'Issue Type'),
    summary: get(r, 'Summary'),
    description: get(r, 'Description'),
    acceptance: get(r, 'Acceptance Criteria'),
    parent: get(r, 'Parent Id'),
    priority: get(r, 'Priority'),
    points: Number(get(r, 'Story Points')) || 0,
    labels: get(r, 'Labels'),
    component: get(r, 'Component'),
    status: get(r, 'Status') || 'To Do',
  }));
  backlog.stories = items.filter((x) => x.type !== 'Epic');
  backlog.epics = items
    .filter((x) => x.type === 'Epic')
    .map((e) => {
      const stories = backlog.stories.filter((s) => s.parent === e.id);
      return {
        id: e.id,
        summary: e.summary,
        description: e.description,
        status: e.status,
        priority: e.priority,
        total: stories.length,
        done: stories.filter((s) => s.status === 'Done').length,
        inProgress: stories.filter((s) => s.status === 'In Progress').length,
        points: stories.reduce((n, s) => n + s.points, 0),
        donePoints: stories.filter((s) => s.status === 'Done').reduce((n, s) => n + s.points, 0),
      };
    });
} else warn('docs/backlog/backlog.csv is missing');
write(join(GEN, 'backlog.json'), JSON.stringify(backlog));

// ── 8. MCP tool catalogue snapshot (same shape as GET /v1/tools) ───────────────────────────────
function javaStrings(expr) {
  return [...(expr ?? '').matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => JSON.parse(`"${m[1]}"`)).join('');
}
function balanced(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === '"') {
      i++;
      while (i < src.length && src[i] !== '"') i += src[i] === '\\' ? 2 : 1;
    } else if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return i;
  }
  return -1;
}
function splitTopLevel(s) {
  const out = [];
  let depth = 0;
  let cur = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '"') {
      let j = i + 1;
      while (j < s.length && s[j] !== '"') j += s[j] === '\\' ? 2 : 1;
      cur += s.slice(i, j + 1);
      i = j;
      continue;
    }
    if (c === '(' || c === '<') depth++;
    if (c === ')' || c === '>') depth--;
    if (c === ',' && depth === 0) {
      out.push(cur);
      cur = '';
    } else cur += c;
  }
  if (cur.trim()) out.push(cur);
  return out;
}
const JAVA_JSON = { String: 'string', Integer: 'integer', int: 'integer', Long: 'integer', long: 'integer', Boolean: 'boolean', boolean: 'boolean', Double: 'number', double: 'number', BigDecimal: 'number', UUID: 'string' };
async function mcpTools() {
  const live = process.env.MCP_TOOLS_URL;
  if (live) {
    try {
      const res = await fetch(`${live.replace(/\/$/, '')}/v1/tools`, { signal: AbortSignal.timeout(5000) });
      if (res.ok) return { source: `${live}/v1/tools`, tools: await res.json() };
      warn(`MCP_TOOLS_URL answered ${res.status}; falling back to the source snapshot`);
    } catch (e) {
      warn(`MCP_TOOLS_URL unreachable (${e.message}); falling back to the source snapshot`);
    }
  }
  const base = join(ROOT, 'java/services/mcp-server/src/main/java/com/afrotransact/mcp');
  const catalogFile = join(base, 'application/ToolCatalog.java');
  const permFile = join(base, 'application/McpPermissions.java');
  if (!isFile(catalogFile)) return { source: null, tools: [] };
  const consts = (text) => new Map([...text.matchAll(/static final String (\w+)\s*=\s*"([^"]*)"/g)].map((m) => [m[1], m[2]]));
  const toolNames = consts(read(catalogFile));
  const perms = isFile(permFile) ? consts(read(permFile)) : new Map();
  const descriptors = new Map(
    [...read(catalogFile).matchAll(/new ToolDescriptor\(\s*(\w+)\s*,\s*Toolset\.(\w+)\s*,\s*([\w.]+)\s*,\s*(true|false)\s*\)/g)].map((m) => [
      toolNames.get(m[1]) ?? m[1],
      { toolset: m[2], permission: m[3] === 'null' ? null : perms.get(m[3].split('.').pop()) ?? m[3], readOnly: m[4] === 'true' },
    ]),
  );
  const tools = [];
  const adapterDir = join(base, 'adapter/in/mcp');
  for (const f of list(adapterDir, (n) => n.endsWith('.java'))) {
    const src = read(join(adapterDir, f));
    let idx = 0;
    while ((idx = src.indexOf('@McpTool(', idx)) >= 0) {
      const open = idx + '@McpTool'.length;
      const close = balanced(src, open);
      if (close < 0) break;
      const ann = src.slice(open + 1, close);
      const sigOpen = src.indexOf('(', close + 1);
      const sigClose = balanced(src, sigOpen);
      const params = src.slice(sigOpen + 1, sigClose);
      idx = sigClose;
      const nameExpr = ann.match(/name\s*=\s*([^,]+)/)?.[1].trim() ?? '';
      const name = nameExpr.startsWith('"') ? javaStrings(nameExpr) : toolNames.get(nameExpr.split('.').pop()) ?? nameExpr;
      const strAttr = (attr) => {
        const m = ann.match(new RegExp(`${attr}\\s*=\\s*((?:"(?:[^"\\\\]|\\\\.)*"\\s*\\+?\\s*)+)`));
        return m ? javaStrings(m[1]) : '';
      };
      const hint = (h) => ann.match(new RegExp(`${h}\\s*=\\s*(true|false)`))?.[1];
      const properties = {};
      const required = [];
      for (const p of splitTopLevel(params)) {
        const pm = p.match(/@McpToolParam\s*\(/);
        if (!pm) continue;
        const pOpen = p.indexOf('(', pm.index);
        const pClose = balanced(p, pOpen);
        const pAnn = p.slice(pOpen + 1, pClose);
        const [type, pname] = p.slice(pClose + 1).trim().replace(/^final\s+/, '').split(/\s+(?=\w+$)/);
        const dm = pAnn.match(/description\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)/);
        const baseType = type.replace(/<.*$/, '');
        properties[pname] = {
          type: /^(List|Set|Collection)$/.test(baseType) || type.endsWith('[]') ? 'array' : JAVA_JSON[baseType] ?? 'object',
          description: dm ? javaStrings(dm[1]) : undefined,
        };
        if (!/required\s*=\s*false/.test(pAnn)) required.push(pname);
      }
      const d = descriptors.get(name) ?? {};
      tools.push({
        name,
        title: strAttr('title'),
        description: strAttr('description'),
        toolset: d.toolset ?? basename(f, '.java').replace(/McpTools$/, '').toUpperCase(),
        requiredScope: d.toolset ? `mcp:${d.toolset.toLowerCase()}` : undefined,
        requiredPermission: d.permission ?? undefined,
        readOnly: d.readOnly ?? hint('readOnlyHint') === 'true',
        destructive: hint('destructiveHint') === 'true',
        idempotent: hint('idempotentHint') === 'true',
        inputSchema: { type: 'object', properties, required },
      });
    }
  }
  const order = [...descriptors.keys()];
  tools.sort((a, b) => (order.indexOf(a.name) + 1 || 999) - (order.indexOf(b.name) + 1 || 999));
  for (const name of descriptors.keys()) if (!tools.some((t) => t.name === name)) warn(`MCP tool ${name} is in ToolCatalog but has no @McpTool method`);
  return { source: 'java/services/mcp-server (@McpTool annotations + ToolCatalog)', tools };
}
const mcp = await mcpTools();
write(join(GEN, 'mcp-tools.json'), JSON.stringify(mcp, null, 2));

// ── 9. fonts + logo for the portal and the Redoc/Scalar iframes ────────────────────────────────
try {
  const tokensPkg = join(ROOT, 'web/packages/tokens/package.json');
  const req = createRequire(isFile(tokensPkg) ? tokensPkg : join(PORTAL, 'package.json'));
  const fontDir = join(dirname(req.resolve('@fontsource/figtree/package.json')), 'files');
  const css = [];
  for (const w of [400, 500, 600, 700, 800]) {
    const file = `figtree-latin-${w}-normal.woff2`;
    if (!isFile(join(fontDir, file))) continue;
    cpSync(join(fontDir, file), join(STATIC, 'fonts', file));
    css.push(`@font-face{font-family:"Figtree";font-style:normal;font-display:swap;font-weight:${w};src:url(./${file}) format("woff2");}`);
  }
  write(join(STATIC, 'fonts/figtree.css'), `${css.join('\n')}\n`);
} catch (e) {
  warn(`could not copy Figtree for the API reference frames: ${e.message}`);
  write(join(STATIC, 'fonts/figtree.css'), '/* Figtree unavailable at build time */\n');
}
const logo = join(ROOT, 'web/packages/ui/assets/logo-mark.svg');
if (isFile(logo)) cpSync(logo, join(STATIC, 'img/logo-mark.svg'));
else write(join(STATIC, 'img/logo-mark.svg'), '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8"/></svg>\n');

// ── summary ──────────────────────────────────────────────────────────────────────────────────────
write(join(GEN, 'sync-report.json'), JSON.stringify({ warnings, eventProblems: events.problems }, null, 2));
const published = events.eventTypes.filter((t) => t.producers.length).length;
console.log(
  `[sync] ${apis.length} OpenAPI contracts, ${services.size} services, ${events.topics.length} topics / ${events.eventTypes.length} event types ` +
    `(${published} with a documented producer), ${backlog.stories.length} stories in ${backlog.epics.length} epics, ` +
    `${mcp.tools.length} MCP tools, ${runbookSources.length} runbooks, ${engineeringSources.length} engineering guides` +
    (warnings.length ? `, ${warnings.length} warning(s)` : ''),
);

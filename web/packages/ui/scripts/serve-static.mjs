#!/usr/bin/env node
/*
 * Serves the static Storybook build (storybook-static/) for the visual tests, with no extra
 * dependencies. Optionally runs a command against it and exits with that command's status:
 *
 *   node scripts/serve-static.mjs                         # serve until Ctrl-C
 *   node scripts/serve-static.mjs -- <command> ...        # serve, run the command, stop
 *
 * Env: STORYBOOK_PORT (default 6007), STORYBOOK_STATIC_DIR (default storybook-static).
 */
import { spawn } from 'node:child_process';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';

const root = resolve(process.env.STORYBOOK_STATIC_DIR ?? 'storybook-static');
const port = Number(process.env.STORYBOOK_PORT ?? 6007);

if (!existsSync(join(root, 'index.json'))) {
  console.error(`${root}/index.json not found: run \`pnpm build-storybook\` first.`);
  process.exit(1);
}

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json'
};

const server = createServer((req, res) => {
  const pathname = decodeURIComponent(new URL(req.url ?? '/', 'http://localhost').pathname);
  let file = normalize(join(root, pathname));
  if (file !== root && !file.startsWith(root + sep)) {
    res.writeHead(403).end();
    return;
  }
  if (existsSync(file) && statSync(file).isDirectory()) file = join(file, 'index.html');
  if (!existsSync(file)) {
    res.writeHead(404).end('not found');
    return;
  }
  res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
  createReadStream(file).pipe(res);
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Serving ${root} on http://127.0.0.1:${port}`);
  const sepIdx = process.argv.indexOf('--');
  if (sepIdx === -1) return;
  const [cmd, ...args] = process.argv.slice(sepIdx + 1);
  if (!cmd) return;
  const child = spawn(cmd, args, { stdio: 'inherit', shell: process.platform === 'win32', env: { ...process.env, STORYBOOK_URL: `http://127.0.0.1:${port}` } });
  child.on('exit', (code, signal) => {
    server.close();
    process.exit(code ?? (signal ? 1 : 0));
  });
});

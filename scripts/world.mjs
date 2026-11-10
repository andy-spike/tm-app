#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createServer } from 'node:net';

const root = realpathSync(resolve(fileURLToPath(new URL('..', import.meta.url))));
const local = resolve(root, '.world');
const statePath = resolve(local, 'state.json');
const command = process.argv[2] ?? 'status';
const run = (bin, args, options = {}) => {
  const result = spawnSync(bin, args, { cwd: root, stdio: 'inherit', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${bin} exited ${result.status ?? result.signal}`);
};
async function available(port) {
  return new Promise((done) => {
    const server = createServer();
    server.once('error', () => done(false));
    server.listen(port, '0.0.0.0', () => server.close(() => done(true)));
  });
}
async function setup() {
  mkdirSync(local, { recursive: true });
  let state;
  if (existsSync(statePath)) {
    state = JSON.parse(readFileSync(statePath, 'utf8'));
    if (state.root !== root) throw new Error('Copied .world detected. Remove the copied .world directory and run setup again.');
  } else {
    const seed = createHash('sha256').update(root).digest().readUInt32BE(0);
    for (let offset = 0; offset < 1000; offset++) {
      const base = 20000 + ((seed + offset) % 10000) * 3;
      if ((await Promise.all([base, base + 1, base + 2].map(available))).every(Boolean)) {
        state = { root, metro: base, motis: base + 1, bundle: base + 2 };
        break;
      }
    }
    if (!state) throw new Error('No free port group found');
    writeFileSync(statePath, JSON.stringify(state, null, 2) + '\n', { flag: 'wx' });
  }
  for (const dir of ['cache', 'npm-cache', 'pnpm-store']) mkdirSync(resolve(local, dir), { recursive: true });
  if (!process.argv.includes('--no-install')) {
    run('pnpm', ['install', '--frozen-lockfile', '--store-dir', resolve(local, 'pnpm-store'), '--package-import-method', 'copy'], { env: environment(state) });
  }
  console.log(JSON.stringify(state, null, 2));
}
function environment(state) {
  return { ...process.env, XDG_CACHE_HOME: resolve(local, 'cache'), npm_config_cache: resolve(local, 'npm-cache'),
    MOTIS_URL: `http://localhost:${state.motis}`, TM_BUNDLE_PORT: String(state.bundle),
    EXPO_PUBLIC_MOTIS_URL: `http://localhost:${state.motis}`, EXPO_PUBLIC_BUNDLE_URL: `http://localhost:${state.bundle}` };
}
try {
  if (command === 'setup') await setup();
  else {
    if (!existsSync(statePath)) throw new Error('Run pnpm worktree:setup first');
    const state = JSON.parse(readFileSync(statePath, 'utf8'));
    if (state.root !== root) throw new Error('This .world belongs to another checkout; run setup with a fresh .world');
    const env = environment(state);
    if (command === 'status') console.log(JSON.stringify(state, null, 2));
    else if (command === 'mobile' || command === 'android') {
      run('pnpm', ['--filter', '@tm/mobile', 'exec', 'expo', command === 'android' ? 'run:android' : 'start', '--port', String(state.metro), ...process.argv.slice(3)], { env });
    } else if (command === 'motis') {
      const path = resolve(root, 'services/motis/data/config.yml');
      if (!existsSync(path)) throw new Error('Run pnpm motis:setup in this checkout first');
      const config = readFileSync(path, 'utf8');
      if (!/^  port: \d+$/m.test(config)) throw new Error('Cannot locate MOTIS server port');
      writeFileSync(path, config.replace(/^  port: \d+$/m, `  port: ${state.motis}`));
      run(resolve(root, 'services/motis/bin/motis'), ['server'], { cwd: resolve(root, 'services/motis'), env });
    } else if (command === 'bundle') run('pnpm', ['--filter', '@tm/pipeline', 'serve'], { env });
    // The Cloudflare Worker replaces the static bundle server, so it reuses the bundle port.
    else if (command === 'api') run('pnpm', ['--filter', '@tm/api', 'dev', '--port', String(state.bundle), '--ip', '0.0.0.0'], { env });
    else if (command === 'exec') {
      const [bin, ...args] = process.argv.slice(3);
      if (!bin) throw new Error('Usage: pnpm world:exec <command> [arguments]');
      run(bin, args, { env });
    } else throw new Error(`Unknown world command: ${command}`);
  }
} catch (error) { console.error(error.message); process.exitCode = 1; }

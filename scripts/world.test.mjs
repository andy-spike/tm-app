import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test('checkout worlds have independent, stable state and reject copied state', () => {
  const temp = mkdtempSync(join(tmpdir(), 'tm-world-test-'));
  try {
    const setup = (name) => {
      const root = join(temp, name);
      mkdirSync(join(root, 'scripts'), { recursive: true });
      copyFileSync(new URL('./world.mjs', import.meta.url), join(root, 'scripts/world.mjs'));
      return root;
    };
    const run = (root, ...args) => spawnSync(process.execPath, [join(root, 'scripts/world.mjs'), ...args], { encoding: 'utf8' });
    const first = setup('first'), second = setup('second');
    assert.equal(run(first, 'setup', '--no-install').status, 0);
    assert.equal(run(second, 'setup', '--no-install').status, 0);
    const state = (root) => JSON.parse(readFileSync(join(root, '.world/state.json'), 'utf8'));
    const a = state(first), b = state(second);
    assert.notEqual(a.root, b.root);
    assert.equal(new Set([a.metro, a.motis, a.bundle, b.metro, b.motis, b.bundle]).size, 6);
    assert.equal(run(first, 'setup', '--no-install').status, 0);
    assert.deepEqual(state(first), a);
    const env = run(first, 'exec', process.execPath, '-e', 'console.log(JSON.stringify(process.env))');
    assert.equal(env.status, 0);
    const values = JSON.parse(env.stdout);
    assert.equal(values.EXPO_PUBLIC_MOTIS_URL, `http://localhost:${a.motis}`);
    assert.equal(values.TM_BUNDLE_PORT, String(a.bundle));
    assert.ok(values.XDG_CACHE_HOME.startsWith(first));
    writeFileSync(join(second, '.world/state.json'), JSON.stringify(a));
    const copied = run(second, 'setup', '--no-install');
    assert.equal(copied.status, 1);
    assert.match(copied.stderr, /Copied .world/);
  } finally { rmSync(temp, { recursive: true, force: true }); }
});

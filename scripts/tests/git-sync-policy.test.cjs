'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { runAutoCommit } = require('../../resources/git-sync-policy.cjs');

function graph(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'alfred-git-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
const noStage = { exitCode: 0, stdout: '' };

test('external sync skips app commits with real local Git configuration', async t => {
  const dir = graph(t);
  assert.equal(spawnSync('git', ['init', '-q', dir]).status, 0);
  assert.equal(spawnSync('git', ['-C', dir, 'config', '--local', 'logseq.external-sync', 'true']).status, 0);
  const result = await runAutoCommit(dir, true, args => {
    const r = spawnSync('git', args, { cwd: dir, encoding: 'utf8' });
    return { exitCode: r.status, stdout: r.stdout };
  }, () => assert.fail('must not commit'));
  assert.deepEqual(result, { skipped: 'external-sync' });
});

test('unmanaged graphs retain automatic commits', async t => {
  const dir = graph(t);
  let count = 0;
  await runAutoCommit(dir, true, args => args[0] === 'config' ? { exitCode: 1, stdout: '' } : noStage,
    () => { count++; });
  assert.equal(count, 1);
});

test('explicit false permits app commits', async t => {
  let committed = false;
  await runAutoCommit(graph(t), true, args => args[0] === 'config' ? { exitCode: 0, stdout: 'false\n' } : noStage,
    () => { committed = true; });
  assert.ok(committed);
});

test('new graphs can still initialize their repository', async t => {
  let committed = false;
  await runAutoCommit(graph(t), false, () => assert.fail('no git repository yet'), () => { committed = true; });
  assert.ok(committed);
});

test('staged work is not consumed by automatic commits', async t => {
  const result = await runAutoCommit(graph(t), true, args => ({ exitCode: 1, stdout: '' }),
    () => assert.fail('must not commit'));
  assert.deepEqual(result, { skipped: 'staged-changes' });
});

test('invalid configuration and index errors fail closed', async t => {
  for (const failConfig of [true, false]) {
    await assert.rejects(runAutoCommit(graph(t), true, args => (
      args[0] === 'config' && !failConfig ? { exitCode: 1, stdout: '' } : { exitCode: 128, stdout: '' }
    ), () => assert.fail('must not commit')), /Cannot/);
  }
});

test('timer and graph-close calls share one operation until the commit completes', async t => {
  const dir = graph(t);
  let finish;
  let calls = 0;
  const commit = () => { calls++; return new Promise(resolve => { finish = resolve; }); };
  const first = runAutoCommit(dir, false, null, commit);
  const second = runAutoCommit(dir, false, null, commit);
  assert.equal(first, second);
  await Promise.resolve();
  assert.equal(calls, 1);
  finish();
  await first;
  await runAutoCommit(dir, false, null, () => { calls++; });
  assert.equal(calls, 2);
});

test('failure releases the operation for retry', async t => {
  const dir = graph(t);
  await assert.rejects(runAutoCommit(dir, false, null, () => Promise.reject(new Error('failed'))));
  assert.equal(await runAutoCommit(dir, false, null, () => 'retried'), 'retried');
});

test('different graphs can commit independently', async t => {
  let finish;
  const first = runAutoCommit(graph(t), false, null, () => new Promise(resolve => { finish = resolve; }));
  await Promise.resolve();
  assert.equal(await runAutoCommit(graph(t), false, null, () => 'second'), 'second');
  finish();
  await first;
});

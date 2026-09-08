'use strict';

const fs = require('node:fs');
const path = require('node:path');
const pending = new Map();

// External coordinators own automatic commits for opted-in repositories.
// Coalesce timer/close requests, retaining the slot until the whole commit ends.
function runAutoCommit(graphPath, hasRepository, runGit, commit) {
  const key = fs.existsSync(graphPath) ? fs.realpathSync(graphPath) : path.resolve(graphPath);
  if (pending.has(key)) return pending.get(key);
  const operation = Promise.resolve().then(async () => {
    if (hasRepository) {
      const config = await runGit(['config', '--local', '--bool', '--get', 'logseq.external-sync']);
      if (config.exitCode === 0 && config.stdout.trim() === 'true') {
        return { skipped: 'external-sync' };
      }
      if (config.exitCode !== 1 && !(config.exitCode === 0 && config.stdout.trim() === 'false')) {
        throw new Error('Cannot determine whether this graph uses external Git synchronization. Automatic commit skipped.');
      }
      const staged = await runGit(['diff', '--cached', '--quiet']);
      if (staged.exitCode === 1) return { skipped: 'staged-changes' };
      if (staged.exitCode !== 0) throw new Error('Cannot inspect staged changes. Automatic commit skipped.');
    }
    return commit();
  }).finally(() => {
    if (pending.get(key) === operation) pending.delete(key);
  });
  pending.set(key, operation);
  return operation;
}

module.exports = { runAutoCommit };

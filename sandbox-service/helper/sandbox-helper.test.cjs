'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { parseArgs, projectAccount } = require('./sandbox-helper.cjs');

test('user:ensure accepts a project identity', () => {
  const options = parseArgs(['user:ensure', '--project', 'C123']);
  assert.equal(options.operation, 'user:ensure');
  assert.equal(options.project, 'C123');
});

test('bash preserves the command as a single argument', () => {
  const options = parseArgs(['bash', '--project', 'C123', '--workdir', 'repo', '--', 'echo "$HOME"; exit 7']);
  assert.equal(options.workdir, 'repo');
  assert.equal(options.command, 'echo "$HOME"; exit 7');
});

test('process:kill rejects arbitrary uid options', () => {
  assert.throws(() => parseArgs(['process:kill', '--project', 'C123', '--uid', '0']), /Unknown/);
});

test('rejects a project identity that could be a path', () => {
  assert.throws(() => parseArgs(['user:ensure', '--project', '../../root']), /Invalid project/);
});

test('rejects duplicate project options', () => {
  assert.throws(() => parseArgs(['user:ensure', '--project', 'C123', '--project', 'C456']), /duplicate/);
});

test('rejects a timeout that Node would reduce to one millisecond', () => {
  assert.throws(() => parseArgs(['bash', '--project', 'C123', '--timeout-seconds', '2147484', '--', 'pwd']), /too large/);
});

test('project identities map to separate managed account names', () => {
  const first = projectAccount('C123');
  const second = projectAccount('C456');
  assert.match(first.name, /^sku_[a-f0-9]{24}$/);
  assert.equal(first.home, `/home/${first.name}`);
  assert.notEqual(first.name, second.name);
  assert.deepEqual(first, projectAccount('C123'));
});

test('bash leaves workdir unspecified when omitted', () => {
  const options = parseArgs(['bash', '--project', 'C123', '--', 'pwd']);
  assert.equal(options.workdir, undefined);
});

test('bash accepts an absolute workdir', () => {
  const options = parseArgs(['bash', '--project', 'C123', '--workdir', '/tmp', '--', 'pwd']);
  assert.equal(options.workdir, '/tmp');
});

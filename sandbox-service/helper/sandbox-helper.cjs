#!/usr/bin/node
'use strict';

const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');

const PROJECT_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const OPERATIONS = new Set(['user:ensure', 'process:kill', 'bash']);

function parseArgs(args) {
  const operation = args.shift();
  if (!OPERATIONS.has(operation)) throw new Error('Expected user:ensure, process:kill, or bash');
  const options = {};
  while (args.length && args[0] !== '--') {
    const option = args.shift();
    const allowed = operation === 'bash'
      ? ['--project', '--workdir', '--timeout-seconds', '--max-output-bytes', '--json']
      : ['--project'];
    if (!allowed.includes(option) || Object.hasOwn(options, option)) {
      throw new Error(`Unknown or duplicate option: ${option}`);
    }
    if (option === '--json') options[option] = true;
    else {
      if (!args.length) throw new Error(`Missing value for ${option}`);
      options[option] = args.shift();
    }
  }
  const project = options['--project'];
  if (!PROJECT_ID_RE.test(project ?? '')) throw new Error('Invalid project ID');
  if (args[0] === '--') args.shift();
  if (operation === 'bash' ? args.length !== 1 || !args[0].trim() : args.length !== 0) {
    throw new Error('bash requires one command after --; other operations take no command');
  }
  return {
    operation, project, command: args[0], workdir: options['--workdir'],
    timeoutSeconds: positiveInteger(options['--timeout-seconds'] ?? '120', 'timeout-seconds'),
    maxOutputBytes: positiveInteger(options['--max-output-bytes'] ?? '51200', 'max-output-bytes'),
    json: options['--json'] === true,
  };
}

function positiveInteger(value, label) {
  const number = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(number) || number < 1) {
    throw new Error(`${label} must be a positive integer`);
  }
  if (label === 'timeout-seconds' && number > 2147483) throw new Error('timeout-seconds is too large');
  return number;
}

function projectAccount(project) {
  const hash = createHash('sha256').update(project).digest('hex');
  const name = `sku_${hash.slice(0, 24)}`;
  return { name, home: `/home/${name}`, marker: `sidekick-project-${hash}` };
}

function lookupAccount(expected) {
  const result = spawnSync('/usr/bin/getent', ['passwd', expected.name], { encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status === 2) return null;
  if (result.status !== 0) throw new Error(`getent failed: ${result.stderr.trim()}`);
  const [name, , uidText, gidText, marker, home, shell] = result.stdout.trim().split(':');
  const uid = Number(uidText);
  const gid = Number(gidText);
  if (name !== expected.name || marker !== expected.marker || home !== expected.home ||
       shell !== '/bin/bash' || !Number.isInteger(uid) || uid <= 0 ||
      !Number.isInteger(gid) || gid <= 0) {
    throw new Error('Refusing to operate on an unmanaged account');
  }
  return { name, uid, gid, home };
}

function ensureUser(project) {
  const expected = projectAccount(project);
  let account = lookupAccount(expected);
  if (!account) {
    const result = spawnSync('/usr/sbin/useradd', [
      '--create-home', '--user-group', '--shell', '/bin/bash', '--password', '!',
      '--home-dir', expected.home, '--comment', expected.marker, '--', expected.name,
    ], { encoding: 'utf8' });
    if (result.error) throw result.error;
    if (result.status !== 0 && result.status !== 9) {
      throw new Error(`useradd failed: ${result.stderr.trim()}`);
    }
    account = lookupAccount(expected);
  }
  if (!account) throw new Error('Project account was not created');
  fs.mkdirSync(account.home, { recursive: true, mode: 0o700 });
  fs.chownSync(account.home, account.uid, account.gid);
  fs.chmodSync(account.home, 0o700);
  console.error(`Ensured project user project=${project} uid=${account.uid}`);
  return account;
}

function requireUser(project) {
  const account = lookupAccount(projectAccount(project));
  if (!account) throw new Error('Project user does not exist; run user:ensure first');
  return account;
}

function killProject(project) {
  const account = requireUser(project);
  const result = spawnSync('/usr/bin/pkill', ['--signal', 'KILL', '--uid', String(account.uid)], {
    encoding: 'utf8',
  });
  if (result.error) throw result.error;
  if (result.status !== 0 && result.status !== 1) throw new Error(`pkill failed: ${result.stderr.trim()}`);
  console.error(`Killed project processes project=${project} uid=${account.uid}`);
}

function dropPrivileges(account) {
  process.setgroups([]);
  process.setgid(account.gid);
  process.setuid(account.uid);
  process.umask(0o077);
}

async function runBash(options, account) {
  const workdir = path.resolve(account.home, options.workdir ?? '.');
  process.chdir(workdir);
  const logs = path.join(account.home, '.sandbox-executions');
  fs.mkdirSync(logs, { recursive: true, mode: 0o700 });
  const execution = fs.mkdtempSync(path.join(logs, 'run-'));
  const outputPath = path.join(execution, 'output.log');
  const outputFd = fs.openSync(outputPath, 'wx', 0o600);
  let timedOut = false;
  let child;
  try {
    child = spawn('/bin/bash', ['-c', options.command], {
      cwd: workdir,
      env: {
        HOME: account.home, USER: account.name, LOGNAME: account.name,
        SHELL: '/bin/bash', PATH: '/usr/local/bin:/usr/bin:/bin', TMPDIR: '/tmp',
      },
      detached: true,
      stdio: ['ignore', outputFd, outputFd],
    });
  } finally {
    fs.closeSync(outputFd);
  }

  const exitCode = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      timedOut = true;
      try { process.kill(-child.pid, 'SIGKILL'); }
      catch (error) { if (error.code !== 'ESRCH') reject(error); }
    }, options.timeoutSeconds * 1000);
    child.once('error', (error) => { clearTimeout(timer); reject(error); });
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      resolve(code ?? 128 + require('node:os').constants.signals[signal]);
    });
  });

  const fd = fs.openSync(outputPath, 'r');
  let output;
  let outputTruncated;
  try {
    const size = fs.fstatSync(fd).size;
    const buffer = Buffer.alloc(Math.min(size, options.maxOutputBytes));
    const read = fs.readSync(fd, buffer, 0, buffer.length, 0);
    output = buffer.subarray(0, read).toString('utf8');
    outputTruncated = size > options.maxOutputBytes;
  } finally {
    fs.closeSync(fd);
  }
  console.error(`Executed project command project=${options.project} exitCode=${exitCode} timedOut=${timedOut} log=${outputPath}`);
  return { ok: !timedOut && exitCode === 0, exitCode: timedOut ? null : exitCode,
    timedOut, outputTruncated, output, workdir };
}

async function main(args) {
  const options = parseArgs(args);
  if (process.getuid() !== 0) throw new Error('sandbox-helper must be invoked as root through sudo');
  if (options.operation === 'user:ensure') {
    console.log(ensureUser(options.project).uid);
  } else if (options.operation === 'process:kill') {
    killProject(options.project);
  } else {
    const account = requireUser(options.project);
    dropPrivileges(account);
    const result = await runBash(options, account);
    if (options.json) console.log(JSON.stringify(result));
    else {
      process.stdout.write(result.output);
      process.exitCode = result.timedOut ? 124 : result.exitCode;
    }
  }
}

module.exports = { parseArgs, projectAccount, lookupAccount, runBash };
if (require.main === module) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

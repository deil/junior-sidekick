#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
fs.appendFileSync(path.join(__dirname, 'invocations.jsonl'), JSON.stringify(args) + '\n');
switch (args[0]) {
  case 'user:ensure':
    if (fs.existsSync(path.join(__dirname, 'fail-ensure'))) {
      console.error('stub provisioning failure');
      process.exit(1);
    }
    fs.writeFileSync(path.join(__dirname, 'user-ensured'), '1001');
    console.error('stub user ensured');
    console.log('1001');
    break;
  case 'bash': {
    if (!fs.existsSync(path.join(__dirname, 'user-ensured'))) process.exit(1);
    const command = args.at(-1);
    if (command === 'stub:missing-workdir') {
      console.error("ENOENT: no such file or directory, chdir '/data/project'");
      process.exit(1);
    }
    const responses = {
      'stub:success': { ok: true, exitCode: 0, timedOut: false, outputTruncated: false, output: 'stub output\n', workdir: '/home/stub' },
      'stub:failure': { ok: false, exitCode: 7, timedOut: false, outputTruncated: true, output: 'stub failure', workdir: '/home/stub/repo' },
      'stub:timeout': { ok: false, exitCode: null, timedOut: true, outputTruncated: false, output: 'stub timeout', workdir: '/home/stub' },
    };
    if (!Object.hasOwn(responses, command)) process.exit(1);
    console.log(JSON.stringify(responses[command]));
    break;
  }
  default: process.exit(1);
}

#!/usr/bin/env node
'use strict';
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
if (fs.existsSync(path.join(__dirname, 'fail-sudo'))) {
  console.error('sudo: a password is required');
  process.exit(1);
}
const args = process.argv.slice(2);
if (args[0] !== '-n' || args[1] !== '--') process.exit(1);
const result = spawnSync(args[2], args.slice(3), { stdio: 'inherit' });
process.exit(result.status ?? 1);

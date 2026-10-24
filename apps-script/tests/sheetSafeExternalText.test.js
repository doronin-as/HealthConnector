// Run with: node --test 'apps-script/tests/*.test.js'
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// Code.gs only declares constants and functions at top level, so it can be
// evaluated without Apps Script globals; nothing below calls a Google service.
const context = vm.createContext({});
vm.runInContext(
  fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'),
  context,
  { filename: 'Code.gs' }
);
const sheetSafeExternalText_ = context.sheetSafeExternalText_;

test('formula-injection payloads are forced to literal text', () => {
  const payloads = [
    '=HYPERLINK("https://evil.example","click")',
    '=IMPORTXML("https://evil.example","//a")',
    '=1+1',
    '+7 (999) 123-45-67',
    '-2+3',
    '@SUM(A1:A9)',
    '==double',
    "='already quoted'"
  ];
  for (const payload of payloads) {
    assert.equal(sheetSafeExternalText_(payload), `'${payload}`, payload);
  }
});

test('ordinary text is unchanged', () => {
  const values = [
    'Овсянка | Банан',
    'Yogurt, vanilla',
    'a=b',
    'x + y',
    'email@example.com',
    ' =leading space is not a formula',
    "'already literal",
    ''
  ];
  for (const value of values) {
    assert.equal(sheetSafeExternalText_(value), value, JSON.stringify(value));
  }
});

test('non-string values are stringified before escaping', () => {
  assert.equal(sheetSafeExternalText_(null), '');
  assert.equal(sheetSafeExternalText_(undefined), '');
  assert.equal(sheetSafeExternalText_(42), '42');
  assert.equal(sheetSafeExternalText_(-5), "'-5");
  assert.equal(sheetSafeExternalText_(['=a', 'b']), "'=a,b");
});

test('escaping is applied once, so repeated writes are stable', () => {
  const once = sheetSafeExternalText_('=cmd');
  assert.equal(sheetSafeExternalText_(once), once);
});

test('joined FatSecret food lists are escaped as a whole', () => {
  const foods = ['=HYPERLINK("x")', 'Apple'];
  assert.equal(sheetSafeExternalText_(foods.join(' | ')), `'=HYPERLINK("x") | Apple`);
});

#!/usr/bin/env node

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const snapshotDir = path.join(__dirname, 'snapshots');

const mode = process.argv[2];
if (mode !== 'record' && mode !== 'verify') {
  console.error('Usage: node perf/parity/parity.mjs <record|verify>');
  process.exit(1);
}

const baseUrl = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');
const pageSize = envInt('PAGE_SIZE', 20);

async function main() {
  if (mode === 'record') {
    await recordSnapshots();
    return;
  }

  await verifySnapshots();
}

function envInt(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  const value = Number(raw);
  return Number.isFinite(value) ? value : fallback;
}

function requestUrl(requestPath) {
  return `${baseUrl}${requestPath}`;
}

async function fetchJson(method, requestPath, body) {
  const response = await fetch(requestUrl(requestPath), {
    method,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const text = await response.text();
  let json;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (error) {
    throw new Error(`Failed to parse JSON for ${method} ${requestPath}: ${error}\nBody: ${text}`);
  }

  if (!response.ok) {
    throw new Error(`${method} ${requestPath} failed with ${response.status}: ${text}`);
  }

  return json;
}

function normalize(value) {
  if (Array.isArray(value)) {
    return value.map(normalize);
  }
  if (value && typeof value === 'object') {
    return Object.keys(value)
      .sort()
      .reduce((acc, key) => {
        acc[key] = normalize(value[key]);
        return acc;
      }, {});
  }
  return value;
}

function stableStringify(value) {
  return JSON.stringify(normalize(value), null, 2);
}

async function writeJson(filePath, value) {
  await mkdir(path.dirname(filePath), { recursive: true });
  await writeFile(filePath, `${stableStringify(value)}\n`, 'utf8');
}

async function readJson(filePath) {
  const raw = await readFile(filePath, 'utf8');
  return JSON.parse(raw);
}

function resolveBook(catalog) {
  const requestedId = process.env.BOOK_ID;
  if (requestedId) {
    const exact = catalog.find((entry) => String(entry.workId) === requestedId);
    if (!exact) {
      throw new Error(`BOOK_ID=${requestedId} not found in catalog`);
    }
    return exact;
  }

  const firstMultilingual = catalog.find((entry) => Array.isArray(entry.langs) && entry.langs.length >= 2);
  if (!firstMultilingual) {
    throw new Error('No multilingual book found in catalog');
  }
  return firstMultilingual;
}

function resolvePair(book) {
  const explicitSrc = process.env.SRC_LANG;
  const explicitTgt = process.env.TGT_LANG;

  if (explicitSrc && explicitTgt) {
    return { src: explicitSrc, tgt: explicitTgt };
  }

  const src =
    explicitSrc ||
    (book.originalLang && book.langs.includes(book.originalLang)
      ? book.originalLang
      : book.langs[0]);
  const tgt = explicitTgt || book.langs.find((lang) => lang !== src);

  if (!src || !tgt) {
    throw new Error(`Could not resolve source/target pair for workId=${book.workId}`);
  }

  return { src, tgt };
}

function extractWord(sentence) {
  const words = sentence
    .split(/[\s,.;:!?()"'-]+/)
    .map((part) => part.trim())
    .filter((part) => part.length >= 4);

  return words[0] ?? 'house';
}

async function buildFixture() {
  const catalog = await fetchJson('GET', '/api/catalog/books');
  const book = resolveBook(catalog);
  const { src, tgt } = resolvePair(book);
  const firstPage = await fetchJson(
    'GET',
    `/api/reader/pages?bookId=${encodeURIComponent(String(book.workId))}&src=${encodeURIComponent(src)}&tgt=${encodeURIComponent(tgt)}&page=0&size=${pageSize}`
  );

  const sampleSentence =
    firstPage.left?.find((sentence) => sentence.text?.trim().length > 20)?.text ??
    'This is a parity sentence.';

  const fixture = {
    bookId: String(book.workId),
    src,
    tgt,
    pageSize,
    pageOne: firstPage.totalSrc > pageSize ? 1 : 0,
    sampleSentence,
    sampleWord: extractWord(sampleSentence),
    sampleContext: [
      `Work: ${firstPage.title ?? book.title}`,
      `Author: ${firstPage.author ?? book.author ?? 'Unknown'}`,
      `Reader pair: ${src.toUpperCase()} → ${tgt.toUpperCase()}`,
      'Selected side: original',
      'Current page: 1',
    ].join(' | '),
  };

  return { fixture, catalog };
}

function buildCases(fixture) {
  const pageZeroPath =
    `/api/reader/pages?bookId=${encodeURIComponent(fixture.bookId)}` +
    `&src=${encodeURIComponent(fixture.src)}` +
    `&tgt=${encodeURIComponent(fixture.tgt)}` +
    `&page=0&size=${fixture.pageSize}`;

  const pageOnePath =
    `/api/reader/pages?bookId=${encodeURIComponent(fixture.bookId)}` +
    `&src=${encodeURIComponent(fixture.src)}` +
    `&tgt=${encodeURIComponent(fixture.tgt)}` +
    `&page=${fixture.pageOne}&size=${fixture.pageSize}`;

  return [
    { name: 'catalog_books', method: 'GET', path: '/api/catalog/books' },
    { name: 'reader_page_0', method: 'GET', path: pageZeroPath },
    { name: 'reader_page_1', method: 'GET', path: pageOnePath },
    {
      name: 'reader_front_matter',
      method: 'GET',
      path:
        `/api/reader/front-matter?bookId=${encodeURIComponent(fixture.bookId)}` +
        `&src=${encodeURIComponent(fixture.src)}` +
        `&tgt=${encodeURIComponent(fixture.tgt)}`,
    },
    {
      name: 'translate_word',
      method: 'GET',
      path:
        `/api/translate/word?text=${encodeURIComponent(fixture.sampleWord)}` +
        `&source=${encodeURIComponent(fixture.src)}` +
        `&target=${encodeURIComponent(fixture.tgt)}`,
    },
    {
      name: 'translate_sentence',
      method: 'POST',
      path: '/api/translate/sentence',
      body: {
        text: fixture.sampleSentence,
        source: fixture.src,
        target: fixture.tgt,
      },
    },
    {
      name: 'translate_explain',
      method: 'POST',
      path: '/api/translate/explain',
      body: {
        text: fixture.sampleSentence,
        source: fixture.src,
        target: fixture.tgt,
        context: fixture.sampleContext,
        translation: `[${fixture.src}→${fixture.tgt}] ${fixture.sampleSentence}`,
      },
    },
  ];
}

function assertStubOutputs(caseName, response) {
  if (caseName === 'translate_word' || caseName === 'translate_sentence') {
    if (typeof response.translated !== 'string' || !response.translated.startsWith('[')) {
      throw new Error(
        `${caseName} does not look like a stub response. Run both stacks with translate.provider=stub.`
      );
    }
  }

  if (caseName === 'translate_explain') {
    if (
      typeof response.explanation !== 'string' ||
      !response.explanation.startsWith('Explanation stub:')
    ) {
      throw new Error(
        'translate_explain does not look like a stub response. Run both stacks with translate.provider=stub.'
      );
    }
  }
}

async function recordSnapshots() {
  const { fixture, catalog } = await buildFixture();
  await writeJson(path.join(snapshotDir, '_fixture.json'), fixture);

  const cases = buildCases(fixture);
  const catalogSnapshot = {
    request: { method: 'GET', path: '/api/catalog/books' },
    response: catalog,
  };
  await writeJson(path.join(snapshotDir, 'catalog_books.json'), catalogSnapshot);

  for (const testCase of cases.slice(1)) {
    const response = await fetchJson(testCase.method, testCase.path, testCase.body);
    assertStubOutputs(testCase.name, response);
    await writeJson(path.join(snapshotDir, `${testCase.name}.json`), {
      request: {
        method: testCase.method,
        path: testCase.path,
        ...(testCase.body ? { body: testCase.body } : {}),
      },
      response,
    });
  }

  console.log(`Recorded ${cases.length} parity snapshots to ${snapshotDir}`);
  console.log(`Fixture: bookId=${fixture.bookId} ${fixture.src}->${fixture.tgt} pageSize=${fixture.pageSize}`);
}

async function verifySnapshots() {
  const fixture = await readJson(path.join(snapshotDir, '_fixture.json'));
  const cases = buildCases(fixture);
  let mismatches = 0;

  for (const testCase of cases) {
    const expected = await readJson(path.join(snapshotDir, `${testCase.name}.json`));
    const actualResponse = await fetchJson(testCase.method, testCase.path, testCase.body);
    assertStubOutputs(testCase.name, actualResponse);

    const expectedJson = stableStringify(expected.response);
    const actualJson = stableStringify(actualResponse);

    if (expectedJson !== actualJson) {
      mismatches += 1;
      console.error(`Mismatch: ${testCase.name}`);
      console.error(`Expected snapshot: ${path.join(snapshotDir, `${testCase.name}.json`)}`);
      console.error('First differing output:');
      printFirstDifference(expectedJson, actualJson);
      continue;
    }

    console.log(`OK ${testCase.name}`);
  }

  if (mismatches > 0) {
    process.exitCode = 1;
    console.error(`Parity verification failed: ${mismatches} mismatched case(s).`);
    return;
  }

  console.log(`Parity verification passed for ${cases.length} cases.`);
}

function printFirstDifference(expected, actual) {
  const max = Math.max(expected.length, actual.length);
  for (let index = 0; index < max; index += 1) {
    if (expected[index] === actual[index]) continue;
    const start = Math.max(0, index - 40);
    const end = Math.min(max, index + 120);
    console.error(`Expected: ${JSON.stringify(expected.slice(start, end))}`);
    console.error(`Actual:   ${JSON.stringify(actual.slice(start, end))}`);
    return;
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});

#!/usr/bin/env node

import { appendFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';

const [
  summaryPath,
  csvPath,
  architecture,
  mode,
  vus,
  duration,
  provider = 'unknown',
  runLabel = '',
] = process.argv.slice(2);

if (!summaryPath || !csvPath || !architecture || !mode || !vus || !duration) {
  console.error(
    'Usage: node perf/k6/append-results.mjs <summary.json> <results.csv> <architecture> <mode> <vus> <duration> [provider] [runLabel]'
  );
  process.exit(1);
}

const raw = await readFile(summaryPath, 'utf8');
const summary = JSON.parse(raw);
const metrics = summary.metrics ?? {};
const setup = summary.setup_data ?? {};

const fixtures = Array.isArray(setup.fixtures) ? setup.fixtures : [];
const fixtureList = fixtures
  .map((fixture) => `${fixture.bookId}:${fixture.src}:${fixture.tgt}`)
  .join(';');

const row = {
  timestamp_utc: new Date().toISOString(),
  architecture,
  provider,
  mode,
  vus,
  duration,
  run_label: runLabel,
  fixture_count: String(fixtures.length || (setup.bookId ? 1 : 0)),
  fixtures: fixtureList || (setup.bookId ? `${setup.bookId}:${setup.src}:${setup.tgt}` : ''),
  http_reqs_count: metricValue(metrics.http_reqs, 'count'),
  http_req_failed_rate: metricValue(metrics.http_req_failed, 'rate'),
  http_req_duration_med_ms: metricValue(metrics.http_req_duration, 'med'),
  http_req_duration_p95_ms: metricValue(metrics.http_req_duration, 'p(95)'),
  iteration_duration_med_ms: metricValue(metrics.iteration_duration, 'med'),
  iteration_duration_p95_ms: metricValue(metrics.iteration_duration, 'p(95)'),
  checks_rate: metricValue(metrics.checks, 'rate'),
  summary_json: summaryPath,
};

const headers = Object.keys(row);
const line = headers.map((header) => csvEscape(row[header] ?? '')).join(',') + '\n';

await mkdir(path.dirname(csvPath), { recursive: true });

try {
  await readFile(csvPath, 'utf8');
} catch {
  await appendFile(csvPath, headers.join(',') + '\n', 'utf8');
}

await appendFile(csvPath, line, 'utf8');
console.log(`Appended results row to ${csvPath}`);

function metricValue(metric, key) {
  const value = metric?.values?.[key];
  return value === undefined || value === null ? '' : String(value);
}

function csvEscape(value) {
  const text = String(value);
  if (/[",\n]/.test(text)) {
    return `"${text.replaceAll('"', '""')}"`;
  }
  return text;
}

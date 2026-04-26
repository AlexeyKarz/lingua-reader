#!/usr/bin/env node

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const [
  monoPath = 'perf/k6/results/monolith/results.csv',
  microPath = 'perf/k6/results/microservices/results.csv',
  outPath = 'perf/k6/results/comparison.csv',
] = process.argv.slice(2);

const monoRows = await readCsv(monoPath);
const microRows = await readCsv(microPath);

const filtered = [...monoRows, ...microRows].filter(
  (row) => !(row.run_label ?? '').toLowerCase().includes('warmup')
);

await mkdir(path.dirname(outPath), { recursive: true });
await writeFile(outPath, toCsv(filtered), 'utf8');
console.log(`Wrote merged comparison CSV to ${outPath}`);

async function readCsv(filePath) {
  const raw = await readFile(filePath, 'utf8');
  const lines = raw.trim().split(/\r?\n/);
  if (lines.length === 0) return [];
  const headers = parseCsvLine(lines[0]);
  return lines.slice(1).filter(Boolean).map((line) => {
    const values = parseCsvLine(line);
    return Object.fromEntries(headers.map((header, index) => [header, values[index] ?? '']));
  });
}

function toCsv(rows) {
  if (rows.length === 0) return '';
  const headers = Object.keys(rows[0]);
  const lines = [headers.join(',')];
  for (const row of rows) {
    lines.push(headers.map((header) => escapeCsv(row[header] ?? '')).join(','));
  }
  return lines.join('\n') + '\n';
}

function parseCsvLine(line) {
  const out = [];
  let current = '';
  let inQuotes = false;

  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (ch === ',' && !inQuotes) {
      out.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  out.push(current);
  return out;
}

function escapeCsv(value) {
  const text = String(value);
  if (/[",\n]/.test(text)) {
    return `"${text.replaceAll('"', '""')}"`;
  }
  return text;
}

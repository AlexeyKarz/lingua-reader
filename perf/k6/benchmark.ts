import http, { Response } from 'k6/http';
import { check, sleep } from 'k6';

type Mode = 'catalog' | 'reader' | 'translate' | 'mixed';

type CatalogEntry = {
  workId: number;
  title: string;
  author: string | null;
  year: number | null;
  langs: string[];
  originalLang?: string | null;
};

type Sentence = {
  id: number;
  text: string;
};

type PageData = {
  bookId: string;
  src: string;
  tgt: string;
  title?: string;
  author?: string | null;
  page: number;
  size: number;
  totalSrc: number;
  totalTgt: number;
  left: Sentence[];
  right: Sentence[];
};

type SetupData = {
  baseUrl: string;
  bookId: string;
  src: string;
  tgt: string;
  pageSize: number;
  totalPages: number;
  sampleSentence: string;
  sampleWord: string;
  sampleContext: string;
};

function envInt(name: string, fallback: number): number {
  const raw = __ENV[name];
  if (!raw) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function envFloat(name: string, fallback: number): number {
  const raw = __ENV[name];
  if (!raw) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function mode(): Mode {
  const raw = (__ENV.MODE ?? 'mixed').toLowerCase();
  if (raw === 'catalog' || raw === 'reader' || raw === 'translate') {
    return raw;
  }
  return 'mixed';
}

export const options = {
  vus: envInt('VUS', 4),
  duration: __ENV.DURATION ?? '2m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
  },
};

function headers() {
  return {
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
  };
}

function url(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

function requireJson<T>(response: Response, label: string): T {
  if (!check(response, { [`${label} status is 200`]: (res) => res.status === 200 })) {
    throw new Error(`${label} failed with status ${response.status}: ${response.body}`);
  }
  return response.json() as T;
}

function resolveBook(catalog: CatalogEntry[]): CatalogEntry {
  const requestedId = __ENV.BOOK_ID;
  if (requestedId) {
    const exact = catalog.find((entry) => String(entry.workId) === requestedId);
    if (!exact) {
      throw new Error(`BOOK_ID=${requestedId} not found in catalog`);
    }
    return exact;
  }

  const firstMultilingual = catalog.find((entry) => entry.langs.length >= 2);
  if (!firstMultilingual) {
    throw new Error('No multilingual book found in catalog');
  }
  return firstMultilingual;
}

function resolvePair(book: CatalogEntry): { src: string; tgt: string } {
  const explicitSrc = __ENV.SRC_LANG;
  const explicitTgt = __ENV.TGT_LANG;

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
    throw new Error(`Could not resolve language pair for workId=${book.workId}`);
  }

  return { src, tgt };
}

function extractWord(sentence: string): string {
  const words = sentence
    .split(/[\s,.;:!?()"'-]+/)
    .map((part) => part.trim())
    .filter((part) => part.length >= 4);

  return words[0] ?? 'house';
}

export function setup(): SetupData {
  const baseUrl = __ENV.BASE_URL ?? 'http://localhost:8080';
  const pageSize = envInt('PAGE_SIZE', 20);

  const catalog = requireJson<CatalogEntry[]>(
    http.get(url(baseUrl, '/api/catalog/books'), headers()),
    'catalog'
  );

  const book = resolveBook(catalog);
  const pair = resolvePair(book);

  const page = requireJson<PageData>(
    http.get(
      url(
        baseUrl,
        `/api/reader/pages?bookId=${encodeURIComponent(String(book.workId))}&src=${encodeURIComponent(pair.src)}&tgt=${encodeURIComponent(pair.tgt)}&page=0&size=${pageSize}`
      ),
      headers()
    ),
    'reader setup'
  );

  const sampleSentence =
    page.left.find((sentence) => sentence.text.trim().length > 20)?.text ??
    'This is a benchmark sentence for translation.';

  return {
    baseUrl,
    bookId: String(book.workId),
    src: pair.src,
    tgt: pair.tgt,
    pageSize,
    totalPages: Math.max(1, Math.ceil((page.totalSrc || pageSize) / pageSize)),
    sampleSentence,
    sampleWord: extractWord(sampleSentence),
    sampleContext: `Book: ${page.title ?? book.title}. Author: ${page.author ?? book.author ?? 'Unknown'}.`,
  };
}

function runCatalog(data: SetupData): void {
  const response = http.get(url(data.baseUrl, '/api/catalog/books'), headers());
  check(response, {
    'catalog ok': (res) => res.status === 200,
    'catalog has body': (res) => res.body.length > 0,
  });
}

function runReader(data: SetupData): void {
  const maxPages = Math.min(data.totalPages, envInt('MAX_PAGES', 10));
  const page = maxPages <= 1 ? 0 : __ITER % maxPages;

  const response = http.get(
    url(
      data.baseUrl,
      `/api/reader/pages?bookId=${encodeURIComponent(data.bookId)}&src=${encodeURIComponent(data.src)}&tgt=${encodeURIComponent(data.tgt)}&page=${page}&size=${data.pageSize}`
    ),
    headers()
  );

  check(response, {
    'reader ok': (res) => res.status === 200,
    'reader has left array': (res) => Array.isArray((res.json() as PageData).left),
  });
}

function runTranslate(data: SetupData): void {
  const selector = __ITER % 3;

  if (selector === 0) {
    const response = http.get(
      url(
        data.baseUrl,
        `/api/translate/word?text=${encodeURIComponent(data.sampleWord)}&source=${encodeURIComponent(data.src)}&target=${encodeURIComponent(data.tgt)}`
      ),
      headers()
    );
    check(response, {
      'word ok': (res) => res.status === 200,
      'word translated': (res) => !!(res.json() as { translated?: string }).translated,
    });
    return;
  }

  if (selector === 1) {
    const response = http.post(
      url(data.baseUrl, '/api/translate/sentence'),
      JSON.stringify({
        text: data.sampleSentence,
        source: data.src,
        target: data.tgt,
      }),
      headers()
    );
    check(response, {
      'sentence ok': (res) => res.status === 200,
      'sentence translated': (res) => !!(res.json() as { translated?: string }).translated,
    });
    return;
  }

  const response = http.post(
    url(data.baseUrl, '/api/translate/explain'),
    JSON.stringify({
      text: data.sampleSentence,
      source: data.src,
      target: data.tgt,
      context: data.sampleContext,
    }),
    headers()
  );
  check(response, {
    'explain ok': (res) => res.status === 200,
    'explain body': (res) => !!(res.json() as { explanation?: string }).explanation,
  });
}

export default function (data: SetupData): void {
  const currentMode = mode();

  if (currentMode === 'catalog') {
    runCatalog(data);
  } else if (currentMode === 'reader') {
    runReader(data);
  } else if (currentMode === 'translate') {
    runTranslate(data);
  } else {
    const selector = __ITER % 10;
    if (selector <= 1) {
      runCatalog(data);
    } else if (selector <= 6) {
      runReader(data);
    } else {
      runTranslate(data);
    }
  }

  sleep(envFloat('THINK_TIME_SEC', 1));
}

export function handleSummary(summary: Record<string, unknown>) {
  const outPath = __ENV.K6_SUMMARY_PATH ?? 'perf/k6/k6-summary.json';
  return {
    [outPath]: JSON.stringify(summary, null, 2),
    stdout: `k6 run complete. Summary written to ${outPath}\n`,
  };
}

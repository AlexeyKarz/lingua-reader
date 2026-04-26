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

type BookFixture = {
  bookId: string;
  src: string;
  tgt: string;
  pageSize: number;
  totalPages: number;
  sampleSentence: string;
  sampleWord: string;
  sampleContext: string;
};

type SetupData = {
  baseUrl: string;
  fixtures: BookFixture[];
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

function parseBookSpecs(raw: string): Array<{ bookId: string; src: string; tgt: string }> {
  return raw
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const [bookId, src, tgt] = part.split(':').map((value) => value.trim());
      if (!bookId || !src || !tgt) {
        throw new Error(`Invalid BOOK_SPECS entry "${part}". Expected bookId:src:tgt`);
      }
      return { bookId, src, tgt };
    });
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

function selectBooks(catalog: CatalogEntry[]): CatalogEntry[] {
  const explicitSpecs = __ENV.BOOK_SPECS;
  if (explicitSpecs) {
    return parseBookSpecs(explicitSpecs).map((spec) => {
      const exact = catalog.find((entry) => String(entry.workId) === spec.bookId);
      if (!exact) {
        throw new Error(`BOOK_SPECS references unknown BOOK_ID=${spec.bookId}`);
      }
      return exact;
    });
  }

  const requestedId = __ENV.BOOK_ID;
  if (requestedId) {
    return [resolveBook(catalog)];
  }

  const count = envInt('BOOK_COUNT', 3);
  const multilingual = catalog.filter((entry) => entry.langs.length >= 2);
  if (multilingual.length === 0) {
    throw new Error('No multilingual books found in catalog');
  }

  return multilingual.slice(0, Math.max(1, count));
}

function resolveExplicitPair(specs: string, bookId: string): { src: string; tgt: string } | null {
  const match = parseBookSpecs(specs).find((spec) => spec.bookId === bookId);
  return match ? { src: match.src, tgt: match.tgt } : null;
}

export function setup(): SetupData {
  const baseUrl = __ENV.BASE_URL ?? 'http://localhost:8080';
  const pageSize = envInt('PAGE_SIZE', 20);

  const catalog = requireJson<CatalogEntry[]>(
    http.get(url(baseUrl, '/api/catalog/books'), headers()),
    'catalog'
  );

  return {
    baseUrl,
    fixtures: selectBooks(catalog).map((book) => {
      const explicitPair = __ENV.BOOK_SPECS
        ? resolveExplicitPair(__ENV.BOOK_SPECS, String(book.workId))
        : null;
      const pair = explicitPair ?? resolvePair(book);

      const page = requireJson<PageData>(
        http.get(
          url(
            baseUrl,
            `/api/reader/pages?bookId=${encodeURIComponent(String(book.workId))}&src=${encodeURIComponent(pair.src)}&tgt=${encodeURIComponent(pair.tgt)}&page=0&size=${pageSize}`
          ),
          headers()
        ),
        `reader setup ${book.workId}`
      );

      const sampleSentence =
        page.left.find((sentence) => sentence.text.trim().length > 20)?.text ??
        'This is a benchmark sentence for translation.';

      return {
        bookId: String(book.workId),
        src: pair.src,
        tgt: pair.tgt,
        pageSize,
        totalPages: Math.max(1, Math.ceil((page.totalSrc || pageSize) / pageSize)),
        sampleSentence,
        sampleWord: extractWord(sampleSentence),
        sampleContext: `Book: ${page.title ?? book.title}. Author: ${page.author ?? book.author ?? 'Unknown'}.`,
      };
    }),
  };
}

function fixtureForIteration(data: SetupData): BookFixture {
  return data.fixtures[__ITER % data.fixtures.length];
}

function runCatalog(data: SetupData): void {
  const response = http.get(url(data.baseUrl, '/api/catalog/books'), headers());
  check(response, {
    'catalog ok': (res) => res.status === 200,
    'catalog has body': (res) => res.body.length > 0,
  });
}

function runReader(data: SetupData): void {
  const fixture = fixtureForIteration(data);
  const maxPages = Math.min(fixture.totalPages, envInt('MAX_PAGES', 10));
  const page = maxPages <= 1 ? 0 : __ITER % maxPages;

  const response = http.get(
    url(
      data.baseUrl,
      `/api/reader/pages?bookId=${encodeURIComponent(fixture.bookId)}&src=${encodeURIComponent(fixture.src)}&tgt=${encodeURIComponent(fixture.tgt)}&page=${page}&size=${fixture.pageSize}`
    ),
    headers()
  );

  check(response, {
    'reader ok': (res) => res.status === 200,
    'reader has left array': (res) => Array.isArray((res.json() as PageData).left),
  });
}

function runTranslate(data: SetupData): void {
  const fixture = fixtureForIteration(data);
  const selector = __ITER % 3;

  if (selector === 0) {
    const response = http.get(
      url(
        data.baseUrl,
        `/api/translate/word?text=${encodeURIComponent(fixture.sampleWord)}&source=${encodeURIComponent(fixture.src)}&target=${encodeURIComponent(fixture.tgt)}`
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
        text: fixture.sampleSentence,
        source: fixture.src,
        target: fixture.tgt,
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
      text: fixture.sampleSentence,
      source: fixture.src,
      target: fixture.tgt,
      context: fixture.sampleContext,
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

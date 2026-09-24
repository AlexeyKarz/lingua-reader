import { expect, test, type Route } from '@playwright/test'

const catalog = [
  {
    workId: 101,
    title: 'Smoke Book',
    author: 'CI Author',
    description: 'A browser smoke-test fixture.',
    year: 2026,
    langs: ['en', 'de'],
    originalLang: 'en',
    editionMeta: {
      en: { aiGenerated: false },
      de: { aiGenerated: true, format: 'ai-literary', translator: 'AI literary translation' },
    },
  },
]

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

test.beforeEach(async ({ page }) => {
    const savedVocabulary: unknown[] = []
    const savedPreferences: Record<string, unknown> = {}
    let savedProgress: unknown = null
  await page.route('**/api/me', route => fulfillJson(route, {
    authenticated: true,
    user: {
      id: 1,
      email: 'admin@example.test',
      displayName: 'Admin',
      roles: ['USER', 'ADMIN'],
    },
  }))
  await page.route('**/api/auth/csrf', route => fulfillJson(route, {
    parameterName: '_csrf',
    headerName: 'X-CSRF-TOKEN',
    token: 'test-csrf-token',
  }))
  await page.route('**/api/catalog/books', route => fulfillJson(route, catalog))
  await page.route('**/api/reader/front-matter?**', route => fulfillJson(route, {
    bookId: '101',
    src: 'en',
    tgt: 'de',
    left: [],
    right: [],
  }))
  await page.route('**/api/reader/pages?**', route => fulfillJson(route, {
    bookId: '101',
    src: 'en',
    tgt: 'de',
    title: 'Smoke Book',
    author: 'CI Author',
    description: 'A browser smoke-test fixture.',
    page: 0,
    size: 20,
    totalSrc: 1,
    totalTgt: 1,
    targetAiGenerated: true,
    left: [{ id: 1, text: 'Hello world.', chapterId: 11, chapterTitle: 'Chapter 1' }],
    right: [{ id: 2, text: 'Hallo Welt.', chapterId: 12, chapterTitle: 'Kapitel 1' }],
    alignments: [{ leftId: 1, rightId: 2, score: 0.99 }],
  }))
  await page.route('**/api/translate/sentence', route => fulfillJson(route, { translated: 'Hallo Welt.' }))
  await page.route('**/api/translate/literary', route => fulfillJson(route, { translated: 'Natural Hallo Welt.' }))
  await page.route('**/api/translate/word?**', route => fulfillJson(route, {
    dictionary: false,
    translated: 'Hallo',
  }))
  await page.route('**/api/translate/explain', route => fulfillJson(route, { explanation: 'A short explanation.' }))
  await page.route('**/api/user/vocabulary', async route => {
    if (route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}')
      const saved = {
        ...body,
        id: 'saved-1',
        normalizedText: String(body.text ?? '').toLowerCase(),
        createdAt: Date.now(),
        updatedAt: Date.now(),
        review: {
          status: 'new',
          dueAt: Date.now(),
          intervalDays: 0,
          ease: 2.5,
          reviewCount: 0,
        },
      }
      savedVocabulary.unshift(saved)
      await fulfillJson(route, saved)
      return
    }
    await fulfillJson(route, savedVocabulary)
  })
  await page.route('**/api/user/preferences', route => fulfillJson(route, savedPreferences))
  await page.route('**/api/user/preferences/**', async route => {
    const key = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop() ?? '')
    const body = JSON.parse(route.request().postData() ?? '{}') as { value?: unknown }
    savedPreferences[key] = body.value
    await fulfillJson(route, body.value ?? null)
  })
  await page.route('**/api/user/reader-progress/recent?**', route => {
    if (!savedProgress) {
      return fulfillJson(route, [])
    }
    const progress = savedProgress as {bookId?: string; source?: string; target?: string; page?: number; size?: number}
    if (!progress.bookId || !progress.source || !progress.target) {
      return fulfillJson(route, [])
    }
    return fulfillJson(route, [progress])
  })
  await page.route('**/api/user/reader-progress?**', route => {
    if (!savedProgress) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
    }
    return fulfillJson(route, savedProgress)
  })
  await page.route('**/api/user/reader-progress', async route => {
    savedProgress = JSON.parse(route.request().postData() ?? '{}')
    await fulfillJson(route, savedProgress)
  })
  await page.route('**/api/admin/metrics/overview', route => fulfillJson(route, {
    registeredUsers: 1,
    activeUsersToday: 1,
    activeUsers7d: 1,
    translationCallsToday: 4,
    translationFailuresToday: 0,
    translationFailureRateToday: 0,
    cacheHitRateToday: 50,
    ingestionRunning: 0,
    ingestionFailedToday: 0,
    translationP95LatencyMs: 123,
    topLanguagePair: 'EN -> DE',
  }))
  await page.route('**/api/admin/metrics/usage/daily?**', route => fulfillJson(route, [
    { day: '2026-09-11', value: 2 },
  ]))
  await page.route('**/api/admin/metrics/users', route => fulfillJson(route, [
    { day: '2026-09-11', value: 1 },
  ]))
  await page.route('**/api/admin/metrics/translations/daily', route => fulfillJson(route, [
    { day: '2026-09-11', value: 4 },
  ]))
  await page.route('**/api/admin/metrics/translations/operations', route => fulfillJson(route, [
    { operation: 'word', calls: 2, failures: 0, p95_latency_ms: 80 },
  ]))
  await page.route('**/api/admin/metrics/translations/providers', route => fulfillJson(route, [
    { label: 'OpenAI', calls: 4, failures: 0, p95_latency_ms: 123 },
  ]))
  await page.route('**/api/admin/metrics/books', route => fulfillJson(route, [
    { label: 'Smoke Book', value: 2 },
  ]))
  await page.route('**/api/admin/metrics/language-pairs', route => fulfillJson(route, [
    { label: 'EN -> DE', value: 4 },
  ]))
  await page.route('**/api/admin/metrics/reviews', route => fulfillJson(route, [
    { label: 'known', value: 1 },
  ]))
  await page.route('**/api/admin/metrics/ingestion', route => fulfillJson(route, {
    byStatus: [{ label: 'SUCCEEDED', value: 1 }],
    averageDurationSeconds: 12,
    recent: [],
  }))
  await page.route('**/api/admin/metrics/errors', route => fulfillJson(route, []))
  await page.route('**/api/admin/metrics/system', route => fulfillJson(route, {
    database: 'ok',
    providerFailures: 0,
    rawEventRetentionDays: 90,
    dailyAggregateRetentionDays: 730,
  }))
  await page.route('**/api/admin/tokens', route => fulfillJson(route, []))
  await page.route(/\/api\/admin\/ingest\/jobs\?limit=50$/, route => fulfillJson(route, []))
  await page.route('**/api/admin/ingest/literary-models', route => fulfillJson(route, {
    provider: 'openai',
    defaultModel: 'gpt-4o-mini',
    models: ['gpt-4o-mini', 'gpt-5-mini'],
    live: true,
    warning: null,
  }))
})

test('catalog to reader lookup save to vocabulary', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByText('Smoke Book').first()).toBeVisible()
  await page.getByRole('link', { name: /Read\s+EN\s+→\s+DE \(AI\)/i }).click()
  await expect(page).toHaveURL(/\/reader\?bookId=101&src=en&tgt=de/)
  await expect(page.getByText('Translation (DE - AI)')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled()
  await expect(page.getByText('Page 1 / 1').first()).toBeVisible()
  await expect(page.getByRole('checkbox', { name: 'Jump to match' })).not.toBeChecked()
  await expect(page.getByRole('button', { name: 'Stack' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Split' }).click()
  await expect(page.locator('.reader-pane-grid-mobile-split')).toBeVisible()
  await expect(page.locator('[data-sentence-id="1"]')).toContainText('Hello world.')
  await page.locator('[data-sentence-id="1"]').click()
  await expect(page.locator('[data-sentence-id="2"]')).toHaveClass(/right-sentence-highlight/)

  await page.locator('[data-sentence-id="1"]').evaluate(node => {
    const range = document.createRange()
    range.selectNodeContents(node)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    node.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 120, clientY: 120 }))
  })

  await page.getByRole('button', { name: 'Literal' }).click()
  await expect(page.getByText('Hallo Welt.').first()).toBeVisible()
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('Sentence saved for review.')).toBeVisible()

  await page.getByRole('link', { name: 'Vocabulary' }).click()
  await expect(page).toHaveURL(/\/vocabulary/)
  await expect(page.getByRole('heading', { name: 'Saved items' })).toBeVisible()
  await expect(page.getByText('Hello world.').first()).toBeVisible()
  await expect(page.getByText(/EN\s+.+\s+DE/).first()).toBeVisible()
})

test('admin ingestion accepts txt upload and shows completed status', async ({ page }) => {
  let uploadBody = ''

  await page.route('**/api/admin/ingest/jobs/upload', async route => {
    uploadBody = route.request().postData() ?? ''
    await fulfillJson(route, { jobId: 77, status: 'PENDING', statusUrl: '/api/admin/ingest/jobs/77' }, 202)
  })
  await page.route('**/api/admin/ingest/jobs/77', route => fulfillJson(route, {
    id: 77,
    status: 'SUCCEEDED',
    step: 'done',
    message: 'Imported',
    error: null,
    workTitle: 'Admin TXT Book',
    lang: 'de',
    fileSpec: 'file:/tmp/admin-txt-book.epub',
    workId: 101,
    editionId: 202,
    chapters: 1,
    paragraphs: 1,
    sentences: 1,
  }))

  await page.goto('/admin/ingest')
  await page.getByLabel('Title').fill('Admin TXT Book')
  await page.getByLabel('Original language').fill('en')
  await page.getByLabel('Language', { exact: true }).fill('de')
  await page.getByLabel('EPUB or TXT file').setInputFiles({
    name: 'admin-book.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('Chapter 1\n\nHello from txt.'),
  })
  await page.getByRole('button', { name: 'Start ingestion' }).click()

  await expect(page.getByText('SUCCEEDED')).toBeVisible()
  expect(uploadBody).toContain('name="workTitle"')
  expect(uploadBody).toContain('Admin TXT Book')
  expect(uploadBody).toContain('name="originalLang"')
  expect(uploadBody).toContain('en')
  expect(uploadBody).toContain('filename="admin-book.txt"')
})

test('admin ingestion queues generated literary edition for an existing source', async ({ page }) => {
  let generationBody = ''

  await page.route('**/api/admin/ingest/jobs/generate-literary', async route => {
    generationBody = route.request().postData() ?? ''
    await fulfillJson(route, { jobId: 78, status: 'PENDING', statusUrl: '/api/admin/ingest/jobs/78' }, 202)
  })
  await page.route('**/api/admin/ingest/jobs/78', route => fulfillJson(route, {
    id: 78,
    status: 'SUCCEEDED',
    step: 'done',
    message: 'Generated',
    error: null,
    workTitle: 'Smoke Book',
    lang: 'fr',
    fileSpec: 'ai-literary://en/fr/Smoke Book',
    workId: 101,
    editionId: 203,
    chapters: 1,
    paragraphs: 1,
    sentences: 2,
  }))

  await page.goto('/admin/ingest')
  await page.getByLabel('Existing work').selectOption('101')
  await page.getByRole('button', { name: 'AI' }).click()
  await page.getByLabel('Target edition language').fill('fr')
  await page.getByLabel('Publisher').fill('Generated Press')
  await page.getByLabel('Model').selectOption('gpt-4o-mini')
  await page.getByText('Advanced generation limits').click()
  await page.getByLabel('Maximum source characters').fill('25000')
  await page.getByLabel('Characters per AI request').fill('1200')
  await page.getByLabel('Previous-text context').fill('300')
  await page.getByRole('button', { name: 'Start ingestion' }).click()

  await expect(page.getByText('2 sentences')).toBeVisible()
  expect(JSON.parse(generationBody)).toMatchObject({
    workTitle: 'Smoke Book',
    sourceLang: 'en',
    targetLang: 'fr',
    year: 2026,
    publisher: 'Generated Press',
    translator: 'AI literary translation',
    bodyOnly: true,
    replaceExisting: true,
    maxSourceChars: 25000,
    chunkMaxChars: 1200,
    contextChars: 300,
    model: 'gpt-4o-mini',
  })
})

test('admin ingestion submits one source and two AI targets from the real button click', async ({ page }) => {
  const generatedTargets: string[] = []
  let uploadCount = 0
  let nextJobId = 80

  await page.route('**/api/admin/ingest/jobs/upload', route => {
    uploadCount += 1
    return fulfillJson(route, { jobId: 79, status: 'PENDING', statusUrl: '/api/admin/ingest/jobs/79' }, 202)
  })
  await page.route('**/api/admin/ingest/jobs/79', route => fulfillJson(route, {
    id: 79, status: 'SUCCEEDED', workTitle: 'Campos test', lang: 'es', fileSpec: 'file:/tmp/campos.txt',
    workId: 102, editionId: 204, chapters: 2, paragraphs: 2, sentences: 2,
  }))
  await page.route('**/api/admin/ingest/jobs/generate-literary', async route => {
    const body = JSON.parse(route.request().postData() ?? '{}') as {targetLang: string}
    generatedTargets.push(body.targetLang)
    const jobId = nextJobId++
    return fulfillJson(route, { jobId, status: 'PENDING', statusUrl: `/api/admin/ingest/jobs/${jobId}` }, 202)
  })
  await page.route(/\/api\/admin\/ingest\/jobs\/(80|81)$/, route => fulfillJson(route, {
    id: Number(new URL(route.request().url()).pathname.split('/').pop()),
    status: 'SUCCEEDED', workTitle: 'Campos test', lang: 'translated',
    fileSpec: 'ai-literary://es/translated/Campos test', workId: 102, editionId: 205,
    chapters: 2, paragraphs: 2, sentences: 2,
  }))

  await page.goto('/admin/ingest')
  await page.getByLabel('Title').fill('Campos test')
  await page.getByLabel('Original language').fill('es')
  await page.getByLabel('Language', { exact: true }).fill('es')
  await page.getByLabel('EPUB or TXT file').setInputFiles({
    name: 'campos.txt', mimeType: 'text/plain', buffer: Buffer.from('I\nPrimera estrofa.'),
  })
  await page.getByRole('button', { name: 'Add edition' }).click()
  await page.getByRole('button', { name: 'Add edition' }).click()
  const aiButtons = page.getByRole('button', { name: 'AI' })
  await aiButtons.nth(1).click()
  await aiButtons.nth(2).click()
  const targets = page.getByLabel('Target edition language')
  await targets.nth(0).fill('en')
  await targets.nth(1).fill('ru')

  await page.getByRole('button', { name: 'Start ingestion' }).click()

  await expect.poll(() => uploadCount).toBe(1)
  await expect.poll(() => generatedTargets).toEqual(['en', 'ru'])
})

test('admin dashboard shows metrics and links to ingestion', async ({ page }) => {
  await page.goto('/admin')

  await expect(page.getByRole('heading', { name: 'Metrics dashboard' })).toBeVisible()
  await expect(page.getByText('Translations today')).toBeVisible()
  await expect(page.getByText('Smoke Book')).toBeVisible()
  await page.getByRole('link', { name: 'Open ingestion' }).click()
  await expect(page).toHaveURL(/\/admin\/ingest/)
})

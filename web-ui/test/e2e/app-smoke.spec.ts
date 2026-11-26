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
  await page.route('**/api/auth/providers', route => fulfillJson(route, { google: true }))
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
    totalSrc: 2,
    totalTgt: 2,
    targetAiGenerated: true,
    left: [
      { id: 1, text: `Hello world. ${'This source passage is deliberately longer. '.repeat(18)}`, chapterId: 11, chapterTitle: 'Chapter 1' },
      { id: 3, text: 'The next source passage.' },
    ],
    right: [
      { id: 2, text: 'Hallo Welt.', chapterId: 12, chapterTitle: 'Kapitel 1' },
      { id: 4, text: 'Der nächste Zielabschnitt.' },
    ],
    alignments: [
      { leftId: 1, rightId: 2, score: 0.99 },
      { leftId: 3, rightId: 4, score: 0.99 },
    ],
  }))
  await page.route('**/api/translate/sentence', route => fulfillJson(route, { translated: 'Hallo Welt.' }))
  await page.route('**/api/translate/literary', route => fulfillJson(route, { translated: 'Natural Hallo Welt.' }))
  await page.route('**/api/translate/word', route => fulfillJson(route, {
    dictionary: false,
    aiGenerated: true,
    translated: 'long\nBase form: long\nPart of speech: adjective\nSelected form: comparative',
    lemma: 'long',
    baseSurface: 'long',
    partOfSpeech: 'adjective',
    gender: '',
    formTags: ['comparative'],
    translations: ['long'],
    definitions: [],
    examples: [],
    entries: [],
    sourceName: 'AI contextual analysis',
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

test('Jackets preserves multiple edition pairs and language filters', async ({ page }) => {
  await page.route('**/api/catalog/books', route => fulfillJson(route, [
    { ...catalog[0], langs: ['en', 'de', 'ru'] },
    { ...catalog[0], workId: 102, title: 'Another Work', originalLang: 'es', langs: ['es', 'en'] },
  ]))
  await page.goto('/')
  await expect(page.locator('.library-work')).toHaveCount(2)
  await expect(page.getByRole('link', { name: 'English / German AI' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'English / Russian' })).toHaveAttribute('href', '/reader?bookId=101&src=en&tgt=ru')
  await page.getByLabel('Translation language', { exact: true }).selectOption('ru')
  await expect(page.locator('.library-work')).toHaveCount(1)
  await expect(page.getByRole('link', { name: 'English / German AI' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Reset filters' }).click()
  await expect(page.locator('.library-work')).toHaveCount(2)
})

test('About panels keep comfortable content padding', async ({ page }) => {
  for (const width of [1360, 800, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/about')
    const pipeline = page.locator('.about-section.surface-panel')
    await expect(pipeline).toBeVisible()
    const insets = await pipeline.evaluate(el => {
      const outer = el.getBoundingClientRect()
      const inner = el.querySelector('.about-section-heading')!.getBoundingClientRect()
      return { left: inner.left - outer.left, right: parseFloat(getComputedStyle(el).paddingRight) }
    })
    expect(insets.left).toBeGreaterThanOrEqual(20)
    expect(insets.right).toBeGreaterThanOrEqual(20)
    if (width === 1360) await page.screenshot({ path: '/tmp/mlr-about-refined.png', fullPage: true })
    expect(await page.locator('.about-tech-note').first().evaluate(el => parseFloat(getComputedStyle(el).paddingLeft))).toBeGreaterThanOrEqual(20)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
})

test('long edition lists scroll without shrinking the pair labels', async ({ page }) => {
  await page.route('**/api/catalog/books', route => fulfillJson(route, [
    { ...catalog[0], langs: ['en', 'de', 'ru', 'el', 'es', 'fr', 'it', 'pt', 'nl'] },
  ]))
  for (const width of [1360, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/')
    const pairs = page.getByRole('group', { name: 'Reading pairs for Smoke Book' })
    await expect(pairs.getByRole('link')).toHaveCount(8)
    expect(await pairs.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true)
    await expect(pairs.getByRole('link').first()).toHaveCSS('font-size', '15px')
    await expect(page.getByLabel('Translation language', { exact: true })).toHaveCSS('font-size', '15px')
    await pairs.getByRole('link').last().focus()
    expect(await pairs.evaluate(el => el.scrollTop)).toBeGreaterThan(0)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
})

test('pagination reveals the new text after loading, including jumps and page size changes', async ({ page }) => {
  await page.route('**/api/reader/pages?**', async route => {
    const params = new URL(route.request().url()).searchParams
    const currentPage = Number(params.get('page'))
    const size = Number(params.get('size'))
    const offset = currentPage * size
    await fulfillJson(route, {
      bookId: '101', src: 'en', tgt: 'de', title: 'Smoke Book', page: currentPage, size,
      totalSrc: 60, totalTgt: 60,
      left: Array.from({ length: size }, (_, i) => ({ id: offset + i + 1, text: `Passage ${offset + i + 1}. ${'A lengthy paragraph of source text. '.repeat(12)}` })),
      right: Array.from({ length: size }, (_, i) => ({ id: 1000 + offset + i + 1, text: `Translation ${offset + i + 1}.` })),
      alignments: Array.from({ length: size }, (_, i) => ({ leftId: offset + i + 1, rightId: 1000 + offset + i + 1, score: .99 })),
    })
  })
  await page.goto('/reader?bookId=101&src=en&tgt=de')
  await expect(page.locator('[data-sentence-id="1"]')).toBeVisible()
  expect(await page.evaluate(() => scrollY)).toBe(0)
  await page.getByRole('checkbox', { name: 'Align passages' }).check()
  const expectStart = async (id: number) => {
    const first = page.locator(`[data-sentence-id="${id}"]`)
    await expect(first).toBeVisible()
    await expect.poll(() => first.evaluate(el => {
      const top = el.getBoundingClientRect().top
      return top >= 0 && top < 80
    })).toBe(true)
  }
  await page.getByRole('button', { name: 'Next', exact: true }).click()
  await expectStart(21)
  await page.getByRole('button', { name: 'Prev', exact: true }).click()
  await expectStart(1)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByLabel('Page number').fill('3')
  await page.getByRole('button', { name: 'Go', exact: true }).click()
  await expectStart(41)
  await page.getByLabel('Sentences / page', { exact: true }).selectOption('50')
  await expectStart(1)
  const select = page.getByLabel('Sentences / page', { exact: true })
  await expect(select).toHaveValue('50')
  expect(await select.evaluate(el => parseFloat(getComputedStyle(el).paddingRight))).toBeGreaterThanOrEqual(32)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('profile has balanced insets even with a long name and email', async ({ page }) => {
  await page.route('**/api/me', route => fulfillJson(route, {
    authenticated: true,
    user: { id: 2, email: 'a.very.long.reader.email.address@example.test', displayName: 'Alex · demo reader', roles: ['USER'] },
  }))
  for (const width of [1360, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/profile')
    await expect(page.getByRole('heading', { name: 'Alex · demo reader' })).toBeVisible()
    const insets = await page.locator('.profile-hero').evaluate(hero => {
      const rect = hero.getBoundingClientRect()
      const heading = hero.querySelector('h2')!.getBoundingClientRect()
      const avatar = hero.querySelector('.profile-avatar')!.getBoundingClientRect()
      const style = getComputedStyle(hero)
      return { left: heading.left - rect.left, right: rect.right - avatar.right, paddingLeft: parseFloat(style.paddingLeft), paddingRight: parseFloat(style.paddingRight) }
    })
    expect(insets.left).toBeGreaterThanOrEqual(24)
    expect(insets.right).toBeGreaterThanOrEqual(24)
    expect(insets.paddingLeft).toBe(insets.paddingRight)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
})

test('reading typography persists and recalculates aligned passages', async ({ page }) => {
  await page.goto('/reader?bookId=101&src=en&tgt=de')
  await expect(page.locator('[data-sentence-id="1"]')).toBeVisible()
  await page.getByRole('checkbox', { name: 'Align passages' }).check()
  await page.getByRole('button', { name: 'Aa · Reading type' }).click()
  await page.getByLabel('Typeface', { exact: true }).selectOption('sans')
  await page.getByLabel('Reading text size').fill('25')
  await expect(page.locator('.reader-pane .select-text').first()).toHaveCSS('font-size', '25px')
  // Padding aligns the text baseline, not the padded paragraph's bounding box.
  await expect.poll(() => page.evaluate(() => {
    const top = (id: number) => {
      const node = document.querySelector(`[data-sentence-id="${id}"]`)!
      return node.getBoundingClientRect().top + parseFloat(getComputedStyle(node).paddingTop)
    }
    return Math.abs(top(3) - top(4))
  })).toBeLessThan(2)
  await page.reload()
  await page.getByRole('button', { name: 'Aa · Reading type' }).click()
  await expect(page.getByLabel('Typeface', { exact: true })).toHaveValue('sans')
  await expect(page.getByLabel('Reading text size')).toHaveValue('25')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByLabel('Reading text size').fill('14')
  await expect(page.locator('.reader-pane .select-text').first()).toHaveCSS('font-size', '14px')
  await page.getByRole('button', { name: 'Split', exact: true }).click()
  await expect(page.locator('.reader-pane .select-text').first()).toHaveCSS('font-size', '14px')
  await page.reload()
  await page.getByRole('button', { name: 'Aa · Reading type' }).click()
  await expect(page.getByLabel('Reading text size')).toHaveValue('14')
  await expect(page.locator('.reader-pane .select-text').first()).toHaveCSS('font-size', '14px')
})

test('Jackets routes fit a narrow mobile viewport in dark mode', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 })
  await page.addInitScript(() => localStorage.setItem('mlr:theme', 'dark'))
  for (const [path, selector] of [
    ['/', '.library-work'],
    ['/reader?bookId=101&src=en&tgt=de', '[data-sentence-id="1"]'],
    ['/profile', '.profile-avatar'],
    ['/vocabulary', 'h2'],
    ['/about', '.about-hero'],
    ['/admin', 'table'],
    ['/admin/ingest', 'input[type="file"]'],
  ]) {
    await page.goto(path)
    await expect(page.locator(selector).first()).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), { message: `No overflow on ${path}` }).toBe(true)
  }
})

test('login offers Google while reading remains optional', async ({ page }) => {
  await page.route('**/api/me', route => fulfillJson(route, {
    authenticated: false,
    user: null,
  }))

  await page.goto('/login?next=%2Fadmin%2Fingest')

  await expect(page.getByRole('link', { name: 'Continue with Google' }))
    .toHaveAttribute('href', '/api/auth/oauth2/google?next=%2Fadmin%2Fingest')
  await expect(page.getByRole('link', { name: 'Continue without an account' })).toBeVisible()
  await expect(page.getByLabel('Email')).toBeVisible()
})

test('catalog to reader lookup save to vocabulary', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByText('Smoke Book').first()).toBeVisible()
  await page.getByRole('link', { name: 'English / German AI' }).click()
  await expect(page).toHaveURL(/\/reader\?bookId=101&src=en&tgt=de/)
  await expect(page).toHaveTitle('MLR — Smoke Book')
  await expect(page.getByText('Translation (DE - AI)')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled()
  await expect(page.getByText('Page 1 / 1').first()).toBeVisible()
  await expect(page.getByRole('checkbox', { name: 'Jump to match' })).not.toBeChecked()
  await expect(page.getByRole('checkbox', { name: 'Align passages' })).not.toBeChecked()
  await expect(page.getByRole('button', { name: 'Stack' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Split' }).click()
  await expect(page.locator('.reader-pane-grid-mobile-split')).toBeVisible()
  await page.getByRole('checkbox', { name: 'Align passages' }).check()
  await expect.poll(() => page.locator('[data-sentence-id="4"]').evaluate(element => (
    Number.parseFloat((element as HTMLElement).style.paddingTop || '0')
  ))).toBeGreaterThan(0)
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

  await page.getByRole('link', { name: 'Profile' }).click()
  await page.getByRole('link', { name: 'Vocabulary' }).click()
  await expect(page).toHaveURL(/\/vocabulary/)
  await expect(page.getByRole('heading', { name: 'Saved items' })).toBeVisible()
  await expect(page.getByText('Hello world.').first()).toBeVisible()
  await expect(page.getByText(/EN\s+.+\s+DE/).first()).toBeVisible()
})

test('word lookup uses sentence context and saves the base form', async ({ page }) => {
  let wordRequest: {text?: string; context?: string} = {}
  page.on('request', request => {
    if (request.url().endsWith('/api/translate/word')) {
      wordRequest = request.postDataJSON() as {text?: string; context?: string}
    }
  })

  await page.goto('/reader?bookId=101&src=en&tgt=de')
  const sourceSentence = page.locator('[data-sentence-id="1"]')
  await expect(sourceSentence).toContainText('deliberately longer')
  await sourceSentence.evaluate(node => {
    const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT)
    let textNode = walker.nextNode()
    while (textNode && !textNode.textContent?.includes('longer')) textNode = walker.nextNode()
    if (!textNode?.textContent) throw new Error('Could not find word in sentence')
    const start = textNode.textContent.indexOf('longer')
    const range = document.createRange()
    range.setStart(textNode, start)
    range.setEnd(textNode, start + 'longer'.length)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    node.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 120, clientY: 120 }))
  })

  await page.getByRole('button', { name: 'Translate' }).click()
  await expect(page.getByText('AI word analysis')).toBeVisible()
  await expect(page.getByText('Contextual word analysis')).toBeVisible()
  await expect(page.getByText('base form: long')).toBeVisible()
  await expect(page.getByText('comparative')).toBeVisible()
  await expect(page.getByText('Analysed using the surrounding sentence.')).toHaveCount(0)
  await expect(page.locator('.dictionary-meta-chip').first()).toHaveCSS('font-size', '13px')
  await expect(page.locator('.dictionary-translations .dictionary-chip').first()).toHaveCSS('font-size', '16px')
  for (const width of [1360, 390, 320]) {
    await page.setViewportSize({ width, height: 844 })
    const popup = page.locator('.popover-surface')
    await expect.poll(() => popup.evaluate(el => {
      const r = el.getBoundingClientRect()
      return r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight
    })).toBe(true)
    if (width === 390) await page.screenshot({ path: '/tmp/mlr-lookup-refined.png' })
  }
  expect(wordRequest.text).toBe('longer')
  expect(wordRequest.context).toContain('Selected sentence: Hello world.')
  expect(wordRequest.context).toContain('Aligned translation sentence: Hallo Welt.')

  await page.getByRole('button', { name: 'Save' }).click()
  await page.getByRole('link', { name: 'Profile' }).click()
  await page.getByRole('link', { name: 'Vocabulary' }).click()
  await expect(page.getByRole('heading', { name: 'Saved items' })).toBeVisible()
  await expect(page.getByText('long', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/Selected in the text as\s+longer/)).toBeVisible()
})

test('profile keeps user and admin actions out of the main navigation', async ({ page }) => {
  await page.goto('/profile')

  const navigation = page.getByRole('navigation')
  await expect(navigation.getByRole('link').allTextContents()).resolves.toEqual(['Catalog', 'Reader', 'Profile'])
  await expect(page.getByRole('heading', { name: 'Admin' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Vocabulary' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Admin panel' })).toBeVisible()
  await expect(page.getByRole('link', { name: /^Content Ingestion/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

test('standard user profile only exposes personal actions', async ({ page }) => {
  await page.route('**/api/me', route => fulfillJson(route, {
    authenticated: true,
    user: {
      id: 2,
      email: 'reader@example.test',
      displayName: 'Reader',
      roles: ['USER'],
    },
  }))

  await page.goto('/profile')

  await expect(page.getByRole('heading', { name: 'Reader', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Vocabulary' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Admin panel' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /^Content Ingestion/ })).toHaveCount(0)
})

test('about explains reader features, ingestion, and architecture', async ({ page }) => {
  await page.goto('/about')

  await expect(page).toHaveTitle('About — Multilang Reader')
  await expect(page.getByRole('heading', { name: 'Read across languages without losing your place.' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'One text, several ways in' })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Book ingestion pipeline' }).getByRole('listitem')).toHaveCount(7)
  await expect(page.getByRole('heading', { name: 'A modular full-stack pet project' })).toBeVisible()
  await expect(page.getByText('PostgreSQL + pgvector')).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
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
  await page.getByLabel('EPUB, TXT, or PDF file').setInputFiles({
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
  await page.getByLabel('EPUB, TXT, or PDF file').setInputFiles({
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

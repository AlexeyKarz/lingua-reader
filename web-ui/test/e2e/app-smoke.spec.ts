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
    left: [{ id: 1, text: 'Hello world.', chapterId: 11, chapterTitle: 'Chapter 1' }],
    right: [{ id: 2, text: 'Hallo Welt.', chapterId: 12, chapterTitle: 'Kapitel 1' }],
    alignments: [{ leftId: 1, rightId: 2, score: 0.99 }],
  }))
  await page.route('**/api/translate/sentence', route => fulfillJson(route, { translated: 'Hallo Welt.' }))
  await page.route('**/api/translate/word?**', route => fulfillJson(route, {
    dictionary: false,
    translated: 'Hallo',
  }))
  await page.route('**/api/translate/explain', route => fulfillJson(route, { explanation: 'A short explanation.' }))
})

test('catalog to reader lookup save to vocabulary', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByText('Smoke Book').first()).toBeVisible()
  await page.getByRole('link', { name: /Read\s+EN/i }).click()
  await expect(page).toHaveURL(/\/reader\?bookId=101&src=en&tgt=de/)
  await expect(page.locator('[data-sentence-id="1"]')).toContainText('Hello world.')

  await page.locator('[data-sentence-id="1"]').evaluate(node => {
    const range = document.createRange()
    range.selectNodeContents(node)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    node.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: 120, clientY: 120 }))
  })

  await page.getByRole('button', { name: 'Translate' }).click()
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
  let uploadAuth = ''
  let uploadBody = ''

  await page.route('**/api/admin/ingest/jobs/upload', async route => {
    uploadAuth = route.request().headers().authorization ?? ''
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
  await page.getByLabel('Admin token').fill('secret')
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
  expect(uploadAuth).toBe('Bearer secret')
  expect(uploadBody).toContain('name="workTitle"')
  expect(uploadBody).toContain('Admin TXT Book')
  expect(uploadBody).toContain('name="originalLang"')
  expect(uploadBody).toContain('en')
  expect(uploadBody).toContain('filename="admin-book.txt"')
})

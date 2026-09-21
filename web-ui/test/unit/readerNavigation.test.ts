import { describe, expect, it } from 'vitest'
import {
  LAST_READER_SESSION_KEY,
  buildReaderPath,
  getSourceLang,
  readLastReaderSession,
  resolveReaderPath,
  resolveReaderPathForSession,
  type CatalogEntry,
} from '../../src/readerNavigation'

function book(overrides: Partial<CatalogEntry>): CatalogEntry {
  return {
    workId: 1,
    title: 'Book',
    author: null,
    description: null,
    year: null,
    langs: ['en', 'de'],
    originalLang: 'en',
    ...overrides,
  }
}

describe('readerNavigation', () => {
  it('reads a valid saved reader session and applies defaults', () => {
    window.localStorage.setItem(
      LAST_READER_SESSION_KEY,
      JSON.stringify({ bookId: '12', src: 'en', tgt: 'de', page: 3 })
    )

    expect(readLastReaderSession()).toMatchObject({
      bookId: '12',
      src: 'en',
      tgt: 'de',
      page: 3,
      size: 20,
    })
  })

  it('ignores malformed saved reader sessions', () => {
    window.localStorage.setItem(LAST_READER_SESSION_KEY, '{bad json')
    expect(readLastReaderSession()).toBeNull()

    window.localStorage.setItem(
      LAST_READER_SESSION_KEY,
      JSON.stringify({ bookId: '12', src: 'en', page: 0 })
    )
    expect(readLastReaderSession()).toBeNull()
  })

  it('prefers original language when it is available', () => {
    expect(getSourceLang(book({ langs: ['de', 'en'], originalLang: 'en' }))).toBe('en')
    expect(getSourceLang(book({ langs: ['de', 'fr'], originalLang: 'en' }))).toBe('de')
  })

  it('uses the last valid session before falling back to the first readable book', () => {
    window.localStorage.setItem(
      LAST_READER_SESSION_KEY,
      JSON.stringify({ bookId: '7', src: 'fr', tgt: 'en', page: 0, size: 10, savedAt: 1 })
    )

    expect(resolveReaderPath([
      book({ workId: 1, langs: ['de'] }),
      book({ workId: 7, langs: ['fr', 'en'], originalLang: 'fr' }),
    ])).toBe('/reader?bookId=7&src=fr&tgt=en')

    window.localStorage.setItem(
      LAST_READER_SESSION_KEY,
      JSON.stringify({ bookId: '7', src: 'fr', tgt: 'fr', page: 0 })
    )

    expect(resolveReaderPath([
      book({ workId: 2, langs: ['es', 'en'], originalLang: 'es' }),
    ])).toBe('/reader?bookId=2&src=es&tgt=en')
  })

  it('builds encoded reader paths', () => {
    expect(buildReaderPath({ bookId: 'A Book', src: 'pt-BR', tgt: 'zh cn' }))
      .toBe('/reader?bookId=A%20Book&src=pt-BR&tgt=zh%20cn')
  })

  it('validates direct reader sessions against available catalog languages', () => {
    const books = [book({ workId: 7, langs: ['el', 'en'], originalLang: 'el' })]

    expect(resolveReaderPathForSession(books, { bookId: '7', src: 'el', tgt: 'en' }))
      .toBe('/reader?bookId=7&src=el&tgt=en')
    expect(resolveReaderPathForSession(books, { bookId: '7', src: 'el', tgt: 'el' }))
      .toBeNull()
    expect(resolveReaderPathForSession(books, { bookId: '7', src: 'el', tgt: 'de' }))
      .toBeNull()
  })
})

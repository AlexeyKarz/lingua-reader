import { describe, expect, it, vi } from 'vitest'
import {
  VOCABULARY_STORAGE_KEY,
  dueVocabularyItems,
  readVocabularyItems,
  reviewVocabularyItem,
  saveVocabularyItem,
  type SaveVocabularyInput,
  type SavedVocabularyItem,
} from '../../src/vocabularyStorage'

const dayMs = 24 * 60 * 60 * 1000

function input(overrides: Partial<SaveVocabularyInput> = {}): SaveVocabularyInput {
  return {
    kind: 'word',
    text: 'Hallo',
    source: 'de',
    target: 'en',
    result: 'Hello',
    dictionary: false,
    bookId: '1',
    page: 0,
    ...overrides,
  }
}

function item(id: string, dueAt: number, updatedAt: number): SavedVocabularyItem {
  return {
    id,
    kind: 'word',
    text: id,
    normalizedText: id,
    source: 'de',
    target: 'en',
    result: id,
    dictionary: false,
    bookId: '1',
    page: 0,
    createdAt: updatedAt,
    updatedAt,
    review: {
      status: 'new',
      dueAt,
      intervalDays: 0,
      ease: 2.5,
      reviewCount: 0,
    },
  }
}

describe('vocabularyStorage', () => {
  it('returns an empty list for missing or corrupt storage', () => {
    expect(readVocabularyItems()).toEqual([])

    window.localStorage.setItem(VOCABULARY_STORAGE_KEY, 'not json')
    expect(readVocabularyItems()).toEqual([])
  })

  it('normalizes and updates existing saved items instead of duplicating them', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))

    const first = saveVocabularyItem(input({ text: '  Hallo   Welt ', result: 'Hello world' }))

    vi.setSystemTime(new Date('2026-01-01T00:01:00Z'))
    const second = saveVocabularyItem(input({ text: 'hallo welt', result: 'Hi world', page: 2 }))

    expect(second.id).toBe(first.id)
    expect(second.createdAt).toBe(first.createdAt)
    expect(second.updatedAt).toBeGreaterThan(first.updatedAt)
    expect(second.normalizedText).toBe('hallo welt')
    expect(readVocabularyItems()).toHaveLength(1)
    expect(readVocabularyItems()[0]).toMatchObject({
      result: 'Hi world',
      page: 2,
    })
  })

  it('deduplicates inflected selections by the saved base form while keeping the observed form', () => {
    saveVocabularyItem(input({ text: 'das Kind', selectedText: 'Kindern', result: 'children' }))
    const updated = saveVocabularyItem(input({ text: 'das Kind', selectedText: 'Kinder', result: 'children' }))

    expect(readVocabularyItems()).toHaveLength(1)
    expect(updated.text).toBe('das Kind')
    expect(updated.normalizedText).toBe('das kind')
    expect(updated.selectedText).toBe('Kinder')
  })

  it('filters due items and sorts by due time then newest update', () => {
    const now = Date.parse('2026-01-01T00:00:00Z')
    const items = [
      item('future', now + dayMs, now),
      item('same-due-old', now, now - 10),
      item('earliest', now - dayMs, now - 20),
      item('same-due-new', now, now + 10),
    ]

    expect(dueVocabularyItems(items, now).map(value => value.id)).toEqual([
      'earliest',
      'same-due-new',
      'same-due-old',
    ])
  })

  it('reviews items and persists the updated schedule', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const saved = saveVocabularyItem(input())

    vi.setSystemTime(new Date('2026-01-02T00:00:00Z'))
    const reviewed = reviewVocabularyItem(saved, 'known')

    expect(reviewed.review.status).toBe('known')
    expect(reviewed.review.reviewCount).toBe(1)
    expect(reviewed.review.dueAt).toBeGreaterThanOrEqual(Date.now() + 7 * dayMs)
    expect(readVocabularyItems()[0].review.status).toBe('known')
  })
})

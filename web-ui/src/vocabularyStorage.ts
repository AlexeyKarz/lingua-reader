export const VOCABULARY_STORAGE_KEY = 'mlr:vocabulary-items'

export type ReviewGrade = 'again' | 'good' | 'known'

export type ReviewState = {
    status: 'new' | 'learning' | 'review' | 'known'
    dueAt: number
    intervalDays: number
    ease: number
    reviewCount: number
    lastReviewedAt?: number
}

export type SavedVocabularyItem = {
    id: string
    kind: 'word' | 'sentence'
    text: string
    selectedText?: string
    normalizedText: string
    source: string
    target: string
    result: string
    dictionary: boolean
    bookId: string
    title?: string
    author?: string | null
    page: number
    sentenceId?: number
    createdAt: number
    updatedAt: number
    review: ReviewState
}

export type SaveVocabularyInput = Omit<SavedVocabularyItem, 'id' | 'normalizedText' | 'createdAt' | 'updatedAt' | 'review'>

export function normalizeSavedText(text: string) {
    return text.trim().replace(/\s+/g, ' ').toLocaleLowerCase()
}

export function readVocabularyItems(): SavedVocabularyItem[] {
    try {
        const raw = window.localStorage.getItem(VOCABULARY_STORAGE_KEY)
        if (!raw) return []
        const parsed = JSON.parse(raw) as SavedVocabularyItem[]
        if (!Array.isArray(parsed)) return []
        return parsed.filter(isVocabularyItem)
    } catch {
        return []
    }
}

export function saveVocabularyItem(input: SaveVocabularyInput): SavedVocabularyItem {
    const now = Date.now()
    const normalizedText = normalizeSavedText(input.text)
    const items = readVocabularyItems()
    const existingIndex = items.findIndex(item =>
        item.kind === input.kind &&
        item.source === input.source &&
        item.target === input.target &&
        item.normalizedText === normalizedText
    )

    const next: SavedVocabularyItem = existingIndex >= 0
        ? {
            ...items[existingIndex],
            ...input,
            normalizedText,
            updatedAt: now,
        }
        : {
            ...input,
            id: createId(),
            normalizedText,
            createdAt: now,
            updatedAt: now,
            review: {
                status: 'new',
                dueAt: now,
                intervalDays: 0,
                ease: 2.5,
                reviewCount: 0,
            },
        }

    const updated = existingIndex >= 0
        ? items.map((item, index) => index === existingIndex ? next : item)
        : [next, ...items]

    writeVocabularyItems(updated)
    return next
}

export function updateVocabularyItem(updated: SavedVocabularyItem) {
    writeVocabularyItems(readVocabularyItems().map(item => item.id === updated.id ? updated : item))
}

export function deleteVocabularyItem(id: string) {
    writeVocabularyItems(readVocabularyItems().filter(item => item.id !== id))
}

export function reviewVocabularyItem(item: SavedVocabularyItem, grade: ReviewGrade): SavedVocabularyItem {
    const now = Date.now()
    const currentInterval = Math.max(0, item.review.intervalDays)
    const ease = Math.max(1.3, item.review.ease + (grade === 'again' ? -0.2 : grade === 'known' ? 0.15 : 0))
    const intervalDays = grade === 'again'
        ? 0
        : grade === 'good'
            ? Math.max(1, Math.round((currentInterval || 1) * ease))
            : Math.max(7, Math.round((currentInterval || 3) * (ease + 0.6)))

    const reviewed: SavedVocabularyItem = {
        ...item,
        updatedAt: now,
        review: {
            status: grade === 'again' ? 'learning' : grade === 'known' ? 'known' : 'review',
            dueAt: grade === 'again' ? now : now + intervalDays * 24 * 60 * 60 * 1000,
            intervalDays,
            ease,
            reviewCount: item.review.reviewCount + 1,
            lastReviewedAt: now,
        },
    }
    updateVocabularyItem(reviewed)
    return reviewed
}

export function dueVocabularyItems(items = readVocabularyItems(), now = Date.now()) {
    return items
        .filter(item => item.review.dueAt <= now)
        .sort((a, b) => a.review.dueAt - b.review.dueAt || b.updatedAt - a.updatedAt)
}

export function readerPathForVocabularyItem(item: SavedVocabularyItem) {
    const params = new URLSearchParams({
        bookId: item.bookId,
        src: item.source,
        tgt: item.target,
    })
    return `/reader?${params.toString()}`
}

function writeVocabularyItems(items: SavedVocabularyItem[]) {
    window.localStorage.setItem(VOCABULARY_STORAGE_KEY, JSON.stringify(items))
}

function createId() {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
        return crypto.randomUUID()
    }
    return `vocab-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function isVocabularyItem(value: unknown): value is SavedVocabularyItem {
    if (!value || typeof value !== 'object') return false
    const item = value as Partial<SavedVocabularyItem>
    return typeof item.id === 'string' &&
        (item.kind === 'word' || item.kind === 'sentence') &&
        typeof item.text === 'string' &&
        (item.selectedText === undefined || typeof item.selectedText === 'string') &&
        typeof item.normalizedText === 'string' &&
        typeof item.source === 'string' &&
        typeof item.target === 'string' &&
        typeof item.result === 'string' &&
        typeof item.bookId === 'string' &&
        typeof item.page === 'number' &&
        typeof item.createdAt === 'number' &&
        typeof item.updatedAt === 'number' &&
        !!item.review &&
        typeof item.review.dueAt === 'number'
}

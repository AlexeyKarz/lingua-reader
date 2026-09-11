export type CatalogEditionMeta = {
    translator?: string | null
    format?: string | null
    aiGenerated?: boolean
}

export type CatalogEntry = {
    workId: number
    title: string
    author: string | null
    description: string | null
    year: number | null
    langs: string[]
    originalLang?: string | null
    editionMeta?: Record<string, CatalogEditionMeta>
}

export type LastReaderSession = {
    bookId: string
    src: string
    tgt: string
    page: number
    size: number
    savedAt: number
}

export const LAST_READER_SESSION_KEY = 'mlr:last-reader-session'

export function readLastReaderSession(): LastReaderSession | null {
    try {
        const raw = window.localStorage.getItem(LAST_READER_SESSION_KEY)
        if (!raw) return null
        const parsed = JSON.parse(raw) as Partial<LastReaderSession>
        if (
            typeof parsed.bookId !== 'string' ||
            typeof parsed.src !== 'string' ||
            typeof parsed.tgt !== 'string' ||
            typeof parsed.page !== 'number'
        ) {
            return null
        }
        return {
            bookId: parsed.bookId,
            src: parsed.src,
            tgt: parsed.tgt,
            page: parsed.page,
            size: typeof parsed.size === 'number' ? parsed.size : 20,
            savedAt: typeof parsed.savedAt === 'number' ? parsed.savedAt : Date.now(),
        }
    } catch {
        return null
    }
}

export function getSourceLang(book: CatalogEntry) {
    return book.originalLang && book.langs.includes(book.originalLang)
        ? book.originalLang
        : book.langs[0]
}

export function isAiGeneratedEdition(book: CatalogEntry, lang: string) {
    return book.editionMeta?.[lang]?.aiGenerated === true
}

export function buildReaderPath(session: Pick<LastReaderSession, 'bookId' | 'src' | 'tgt'>) {
    return `/reader?bookId=${encodeURIComponent(session.bookId)}&src=${encodeURIComponent(session.src)}&tgt=${encodeURIComponent(session.tgt)}`
}

export function resolveReaderPath(books: CatalogEntry[]) {
    const lastSession = readLastReaderSession()
    if (lastSession) {
        const lastBook = books.find(book => String(book.workId) === lastSession.bookId)
        if (
            lastBook &&
            lastBook.langs.includes(lastSession.src) &&
            lastBook.langs.includes(lastSession.tgt) &&
            lastSession.src !== lastSession.tgt
        ) {
            return buildReaderPath(lastSession)
        }
    }

    const firstReadableBook = books.find(book => book.langs.length >= 2)
    if (!firstReadableBook) return null

    const src = getSourceLang(firstReadableBook)
    const tgt = firstReadableBook.langs.find(lang => lang !== src)
    if (!src || !tgt) return null

    return buildReaderPath({
        bookId: String(firstReadableBook.workId),
        src,
        tgt,
    })
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
    buildReaderPath,
    getSourceLang as getCatalogSourceLang,
    isAiGeneratedEdition,
    readLastReaderSession,
    type CatalogEntry,
    type LastReaderSession,
} from '../readerNavigation'

export default function CatalogPage() {
    const [books, setBooks] = useState<CatalogEntry[]>([])
    const [status, setStatus] = useState<'idle' | 'loading' | 'error'>('idle')
    const [sourceFilter, setSourceFilter] = useState('all')
    const [targetFilter, setTargetFilter] = useState('all')
    const [lastSession, setLastSession] = useState<LastReaderSession | null>(null)

    const getSourceLang = useCallback(getCatalogSourceLang, [])

    function langLabel(book: CatalogEntry, lang: string) {
        return `${lang.toUpperCase()}${isAiGeneratedEdition(book, lang) ? ' (AI)' : ''}`
    }

    const matchesFilters = useCallback((book: CatalogEntry, source: string, target: string) => {
        const resolvedSource = getSourceLang(book)
        const sourceMatches = source === 'all' || resolvedSource === source
        const targetMatches =
            target === 'all' ||
            (book.langs.includes(target) && target !== resolvedSource)
        return sourceMatches && targetMatches
    }, [getSourceLang])

    useEffect(() => {
        ;(async () => {
            setStatus('loading')
            try {
                const res = await fetch('/api/catalog/books')
                if (!res.ok) throw new Error('Failed to load catalog')
                const data: CatalogEntry[] = await res.json()
                setBooks(data)
                setStatus('idle')
            } catch {
                setStatus('error')
            }
        })()
    }, [])

    useEffect(() => {
        setLastSession(readLastReaderSession())
    }, [])

    const sourceOptions = useMemo(() => {
        const counts = new Map<string, number>()
        books.forEach(book => {
            const lang = getSourceLang(book)
            if (targetFilter !== 'all' && !matchesFilters(book, lang, targetFilter)) return
            counts.set(lang, (counts.get(lang) ?? 0) + 1)
        })
        return Array.from(counts.entries())
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([lang, count]) => ({ lang, count }))
    }, [books, targetFilter, getSourceLang, matchesFilters])

    const targetOptions = useMemo(() => {
        const counts = new Map<string, number>()
        books.forEach(book => {
            const resolvedSource = getSourceLang(book)
            if (sourceFilter !== 'all' && resolvedSource !== sourceFilter) return
            book.langs.forEach(lang => {
                if (lang === resolvedSource) return
                counts.set(lang, (counts.get(lang) ?? 0) + 1)
            })
        })
        return Array.from(counts.entries())
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([lang, count]) => ({ lang, count }))
    }, [books, sourceFilter, getSourceLang])

    const filteredBooks = useMemo(
        () =>
            books.filter(book => matchesFilters(book, sourceFilter, targetFilter)),
        [books, sourceFilter, targetFilter, matchesFilters]
    )

    const continueBook = useMemo(
        () =>
            lastSession
                ? books.find(book => String(book.workId) === lastSession.bookId) ?? null
                : null,
        [books, lastSession]
    )

    useEffect(() => {
        if (sourceFilter !== 'all' && !sourceOptions.some(option => option.lang === sourceFilter)) {
            setSourceFilter('all')
        }
    }, [sourceFilter, sourceOptions])

    useEffect(() => {
        if (targetFilter !== 'all' && !targetOptions.some(option => option.lang === targetFilter)) {
            setTargetFilter('all')
        }
    }, [targetFilter, targetOptions])

    return (
        <div>
            <section className="surface-panel rounded-[28px] px-5 py-4 md:px-6 md:py-5">
                <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
                    <div>
                        <div className="soft-badge mb-2">Library</div>
                        <h2 className="theme-heading text-2xl font-semibold md:text-[1.85rem]">
                            Pick a work and open a language pair
                        </h2>
                        <p className="mt-2 max-w-2xl text-sm leading-6 muted-copy">
                            Each entry lists available editions. Choose a source language and open one
                            of its paired reading views.
                        </p>
                    </div>
                    <div className="text-sm muted-copy md:text-right">
                        {filteredBooks.length > 0
                            ? `${filteredBooks.length} work${filteredBooks.length === 1 ? '' : 's'} shown`
                            : 'Catalog ready'}
                    </div>
                </div>

                {continueBook && lastSession && (
                    <div className="subtle-card mt-4 rounded-[24px] border border-[color:var(--border)] px-4 py-4">
                        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                            <div>
                                <div className="text-xs uppercase tracking-[0.18em] muted-copy">
                                    Continue reading
                                </div>
                                <div className="theme-heading mt-1 text-base font-medium">
                                    {continueBook.title}
                                </div>
                                <div className="mt-1 text-sm muted-copy">
                                    {lastSession.src.toUpperCase()} → {langLabel(continueBook, lastSession.tgt)} · page {lastSession.page + 1}
                                </div>
                            </div>
                            <Link
                                to={buildReaderPath(lastSession)}
                                className="btn"
                            >
                                Resume
                            </Link>
                        </div>
                    </div>
                )}

                <div className="mt-5 flex flex-col gap-3 border-t border-[color:var(--border)] pt-4 lg:flex-row lg:items-end lg:justify-between">
                    <div className="grid gap-3 sm:grid-cols-2">
                        <label className="flex flex-col gap-2 text-sm">
                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">
                                Original language
                            </span>
                            <select
                                value={sourceFilter}
                                onChange={e => setSourceFilter(e.target.value)}
                                className="input min-w-44"
                            >
                                <option value="all">Any source</option>
                                {sourceOptions.map(option => (
                                    <option key={option.lang} value={option.lang}>
                                        {option.lang.toUpperCase()} ({option.count})
                                    </option>
                                ))}
                            </select>
                        </label>

                        <label className="flex flex-col gap-2 text-sm">
                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">
                                Target language
                            </span>
                            <select
                                value={targetFilter}
                                onChange={e => setTargetFilter(e.target.value)}
                                className="input min-w-44"
                            >
                                <option value="all">Any target</option>
                                {targetOptions.map(option => (
                                    <option key={option.lang} value={option.lang}>
                                        {option.lang.toUpperCase()} ({option.count})
                                    </option>
                                ))}
                            </select>
                        </label>
                    </div>

                    <button
                        type="button"
                        className="btn-secondary w-fit"
                        onClick={() => {
                            setSourceFilter('all')
                            setTargetFilter('all')
                        }}
                        disabled={sourceFilter === 'all' && targetFilter === 'all'}
                    >
                        Reset filters
                    </button>
                </div>
            </section>

            {status === 'loading' && <p className="mt-4 text-sm muted-copy">Loading…</p>}
            {status === 'error' && (
                <p className="theme-danger mt-4 rounded-2xl border px-4 py-3 text-sm">
                    Could not load catalog.
                </p>
            )}

            <div className="mt-5 space-y-4">
                {filteredBooks.map(book => (
                    <article key={book.workId} className="card overflow-hidden p-5 md:p-6">
                        <div className="flex flex-col gap-5 md:flex-row md:items-start md:justify-between">
                            <div className="min-w-0">
                                <div className="mb-3 flex flex-wrap items-center gap-2">
                                    {book.originalLang && (
                                        <span className="soft-badge">
                                            Original {book.originalLang.toUpperCase()}
                                        </span>
                                    )}
                                    {book.year && (
                                        <span className="soft-badge">{book.year}</span>
                                    )}
                                </div>
                                <h3 className="theme-heading text-2xl font-semibold md:text-[1.9rem]">
                                    {book.title}
                                </h3>
                                <div className="mt-2 text-[15px] muted-copy">
                                    {book.author ? `by ${book.author}` : 'Author unavailable'}
                                </div>
                                {book.description && (
                                    <p
                                        className="mt-3 max-w-3xl text-sm leading-6 muted-copy"
                                        style={{
                                            display: '-webkit-box',
                                            WebkitLineClamp: 3,
                                            WebkitBoxOrient: 'vertical',
                                            overflow: 'hidden',
                                        }}
                                    >
                                        {book.description}
                                    </p>
                                )}
                            </div>

                            <div className="surface-panel rounded-[24px] px-4 py-3 md:min-w-56">
                                <div className="text-xs uppercase tracking-[0.18em] muted-copy">
                                    Available editions
                                </div>
                                <div className="mt-3 flex flex-wrap gap-2">
                                    {book.langs.map(l => (
                                        <span key={l} className="soft-badge">
                                            {langLabel(book, l)}
                                        </span>
                                    ))}
                                </div>
                            </div>
                        </div>

                        <div className="mt-6 border-t border-[color:var(--border)] pt-5">
                            <div className="mb-3 text-xs uppercase tracking-[0.18em] muted-copy">
                                Open reader
                            </div>

                            {book.langs.length < 2 ? (
                                <span className="text-sm muted-copy">
                                    Add another language edition to read side by side.
                                </span>
                            ) : (
                                <div className="flex flex-wrap gap-3">
                                    {(() => {
                                        const src = getSourceLang(book)
                                        const tgts = book.langs.filter(l =>
                                            l !== src && (targetFilter === 'all' || l === targetFilter)
                                        )
                                        if (tgts.length === 0) return null
                                        return tgts.map(tgt => (
                                            <Link
                                                key={`${src}-${tgt}`}
                                                to={`/reader?bookId=${encodeURIComponent(String(book.workId))}&src=${src}&tgt=${tgt}`}
                                                className="btn"
                                            >
                                                <span>Read</span>
                                                <span className="text-xs tracking-[0.12em]">
                                                    {src.toUpperCase()} → {langLabel(book, tgt)}
                                                </span>
                                            </Link>
                                        ))
                                    })()}
                                </div>
                            )}
                        </div>
                    </article>
                ))}

                {filteredBooks.length === 0 && status === 'idle' && (
                    <p className="rounded-[28px] border border-dashed border-[color:var(--border)] px-5 py-6 text-sm muted-copy">
                        No books match the current language filters.
                    </p>
                )}
            </div>

            <section className="page-dock mt-8">
                <div className="grid gap-6 md:grid-cols-3">
                    <div>
                        <div className="mb-2 text-xs uppercase tracking-[0.18em] muted-copy">How it works</div>
                        <p className="text-sm leading-6 muted-copy">
                            Choose a bilingual pair, open the reader, then select any word or sentence to request a literal translation or short explanation.
                        </p>
                    </div>
                    <div>
                        <div className="mb-2 text-xs uppercase tracking-[0.18em] muted-copy">Reading model</div>
                        <p className="text-sm leading-6 muted-copy">
                            The left side shows the source edition. The right side shows aligned translated passages where data is available.
                        </p>
                    </div>
                    <div>
                        <div className="mb-2 text-xs uppercase tracking-[0.18em] muted-copy">Project</div>
                        <p className="text-sm leading-6 muted-copy">
                            Multilang Reader is a thesis prototype focused on parallel reading, translation assistance, and architecture comparison.
                        </p>
                    </div>
                </div>
            </section>
        </div>
    )
}

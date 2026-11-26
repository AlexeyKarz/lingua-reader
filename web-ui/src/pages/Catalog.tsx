import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import BookJacket from '../BookJacket'
import { languageLabel } from '../languageLabel'
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
        <>
            <section className="library-intro">
                <div>
                    <p className="eyebrow">The multilingual library</p>
                    <h1>Read beyond one language.</h1>
                    <p>Original works. Different voices.<br />Choose the editions you want to read together.</p>
                </div>
                <aside className="reading-resume">
                    <p className="eyebrow">{continueBook ? 'Continue reading' : 'A place to begin'}</p>
                    <h2>{continueBook?.title || 'Read freely. Stay curious.'}</h2>
                    <p>{continueBook && lastSession
                        ? `${languageLabel(lastSession.src)} / ${languageLabel(lastSession.tgt)} · page ${lastSession.page + 1}`
                        : 'An account is optional. Your next language is not.'}</p>
                    {continueBook && lastSession
                        ? <Link to={buildReaderPath(lastSession)}>Resume reading →</Link>
                        : <Link to="/about">How it works →</Link>}
                </aside>
            </section>
            <section className="library-filters" aria-label="Language filters">
                <label>Original language
                    <select aria-label="Original language" value={sourceFilter} onChange={e => setSourceFilter(e.target.value)}>
                        <option value="all">All originals</option>
                        {sourceOptions.map(option => <option key={option.lang} value={option.lang}>{languageLabel(option.lang)} ({option.count})</option>)}
                    </select>
                </label>
                <label>Translation language
                    <select aria-label="Translation language" value={targetFilter} onChange={e => setTargetFilter(e.target.value)}>
                        <option value="all">All translations</option>
                        {targetOptions.map(option => <option key={option.lang} value={option.lang}>{languageLabel(option.lang)} ({option.count})</option>)}
                    </select>
                </label>
                <button type="button" disabled={sourceFilter === 'all' && targetFilter === 'all'} onClick={() => { setSourceFilter('all'); setTargetFilter('all') }}>Reset filters</button>
                <span role="status">{filteredBooks.length} work{filteredBooks.length === 1 ? '' : 's'}</span>
            </section>
            {status === 'loading' && <p className="empty-state" role="status">Opening the library…</p>}
            {status === 'error' && <p className="theme-danger mt-4 border px-4 py-3" role="alert">Could not load catalog. Please refresh to try again.</p>}
            <section className="library-works" aria-label="Books">
                {filteredBooks.map(book => {
                    const src = getSourceLang(book)
                    return (
                        <article className="library-work" key={book.workId}>
                            <BookJacket book={book} />
                            <div className="work-copy">
                                <p className="eyebrow">{book.year ? `${book.year} / ` : ''}{languageLabel(src)} original</p>
                                <h2>{book.title}</h2>
                                <p className="work-author">{book.author || 'Author unavailable'}</p>
                                {book.description && <p className="work-description">{book.description}</p>}
                            </div>
                            <div className="edition-choices">
                                <p>{book.langs.length} editions · open a reading pair</p>
                                <div className="edition-pairs" role="group" aria-label={`Reading pairs for ${book.title}`} tabIndex={book.langs.length > 5 ? 0 : undefined}>
                                {book.langs.filter(lang => lang !== src && (targetFilter === 'all' || lang === targetFilter)).map(tgt => (
                                    <Link key={tgt} to={buildReaderPath({ bookId: String(book.workId), src, tgt })}>
                                        <span>{languageLabel(src)} <span className="pair-slash">/</span> {languageLabel(tgt)}</span>
                                        {isAiGeneratedEdition(book, tgt) && <small title="AI-generated translation">AI</small>}
                                        <span className="pair-arrow" aria-hidden="true">↗</span>
                                    </Link>
                                ))}
                                </div>
                                {book.langs.length < 2 && <span className="muted-copy">Add another language edition to read side by side.</span>}
                            </div>
                        </article>
                    )
                })}
            </section>
            {status === 'idle' && !filteredBooks.length && (
                <div className="empty-state">
                    <h2>{books.length ? 'No matching works.' : 'The library is waiting.'}</h2>
                    <p>{books.length ? 'Try another language pair or reset the filters.' : 'Books will appear here once their editions have been added.'}</p>
                </div>
            )}
        </>
    )
}

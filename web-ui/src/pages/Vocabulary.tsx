import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
    deleteVocabularyItem,
    dueVocabularyItems,
    readerPathForVocabularyItem,
    readVocabularyItems,
    reviewVocabularyItem,
    type SavedVocabularyItem,
} from '../vocabularyStorage'

const ALL_LANGUAGE_FILTER = 'all'

function languageKey(item: Pick<SavedVocabularyItem, 'source' | 'target'>) {
    return `${item.source}::${item.target}`
}

function formatDate(value: number) {
    return new Intl.DateTimeFormat(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
    }).format(new Date(value))
}

function nextDueText(item: SavedVocabularyItem) {
    if (item.review.dueAt <= Date.now()) return 'Due now'
    return `Due ${formatDate(item.review.dueAt)}`
}

export default function VocabularyPage() {
    const [items, setItems] = useState(() => readVocabularyItems())
    const [activeId, setActiveId] = useState<string | null>(null)
    const [answerVisible, setAnswerVisible] = useState(false)
    const [filter, setFilter] = useState<'due' | 'all'>('due')
    const [languageFilter, setLanguageFilter] = useState(ALL_LANGUAGE_FILTER)

    const languageOptions = useMemo(() => {
        const counts = new Map<string, {key: string; source: string; target: string; count: number}>()
        items.forEach(item => {
            const key = languageKey(item)
            const existing = counts.get(key)
            if (existing) {
                existing.count += 1
                return
            }
            counts.set(key, {
                key,
                source: item.source,
                target: item.target,
                count: 1,
            })
        })
        return Array.from(counts.values())
            .sort((a, b) => a.source.localeCompare(b.source) || a.target.localeCompare(b.target))
    }, [items])

    const languageFilteredItems = useMemo(
        () => languageFilter === ALL_LANGUAGE_FILTER
            ? items
            : items.filter(item => languageKey(item) === languageFilter),
        [items, languageFilter]
    )
    const dueItems = useMemo(() => dueVocabularyItems(languageFilteredItems), [languageFilteredItems])
    const totalDueItems = useMemo(() => dueVocabularyItems(items), [items])
    const visibleItems = filter === 'due' ? dueItems : languageFilteredItems
    const activeItem = useMemo(() => {
        if (activeId) {
            return visibleItems.find(item => item.id === activeId) ??
                dueItems[0] ??
                languageFilteredItems[0] ??
                null
        }
        return dueItems[0] ?? languageFilteredItems[0] ?? null
    }, [activeId, dueItems, languageFilteredItems, visibleItems])

    useEffect(() => {
        if (
            languageFilter !== ALL_LANGUAGE_FILTER &&
            !languageOptions.some(option => option.key === languageFilter)
        ) {
            setLanguageFilter(ALL_LANGUAGE_FILTER)
            setActiveId(null)
            setAnswerVisible(false)
        }
    }, [languageFilter, languageOptions])

    function refresh(nextActiveId?: string | null) {
        const nextItems = readVocabularyItems()
        setItems(nextItems)
        setActiveId(nextActiveId ?? null)
        setAnswerVisible(false)
    }

    function applyReview(grade: 'again' | 'good' | 'known') {
        if (!activeItem) return
        reviewVocabularyItem(activeItem, grade)
        const nextDue = dueVocabularyItems().find(item =>
            item.id !== activeItem.id &&
            (languageFilter === ALL_LANGUAGE_FILTER || languageKey(item) === languageFilter)
        )
        refresh(nextDue?.id ?? null)
    }

    function removeItem(id: string) {
        deleteVocabularyItem(id)
        refresh(activeId === id ? null : activeId)
    }

    return (
        <div className="space-y-5">
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
                    <div>
                        <div className="soft-badge mb-2">Vocabulary</div>
                        <h2 className="theme-heading text-2xl font-semibold md:text-[1.85rem]">
                            Saved lookups and review
                        </h2>
                        <p className="mt-2 max-w-2xl text-sm leading-6 muted-copy">
                            Saved words and sentences stay in this browser and can be reviewed without an account.
                        </p>
                    </div>
                    <div className="grid grid-cols-3 gap-2 text-sm">
                        <div className="surface-panel-strong rounded-[20px] px-3 py-2 text-center">
                            <div className="theme-heading font-semibold">{items.length}</div>
                            <div className="muted-copy text-xs">saved</div>
                        </div>
                        <div className="surface-panel-strong rounded-[20px] px-3 py-2 text-center">
                            <div className="theme-heading font-semibold">{totalDueItems.length}</div>
                            <div className="muted-copy text-xs">due</div>
                        </div>
                        <div className="surface-panel-strong rounded-[20px] px-3 py-2 text-center">
                            <div className="theme-heading font-semibold">
                                {items.filter(item => item.review.status === 'known').length}
                            </div>
                            <div className="muted-copy text-xs">known</div>
                        </div>
                    </div>
                </div>
            </section>

            {activeItem ? (
                <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                    <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                        <div className="min-w-0 flex-1">
                            <div className="mb-3 flex flex-wrap items-center gap-2">
                                <span className="soft-badge">{activeItem.kind}</span>
                                <span className="soft-badge">
                                    {activeItem.source.toUpperCase()} → {activeItem.target.toUpperCase()}
                                </span>
                                <span className="soft-badge">{nextDueText(activeItem)}</span>
                            </div>
                            <div className="theme-heading text-2xl font-semibold leading-tight">
                                {activeItem.text}
                            </div>
                            <div className="mt-2 text-sm muted-copy">
                                {activeItem.title || `Book ${activeItem.bookId}`} · page {activeItem.page + 1}
                            </div>

                            {answerVisible ? (
                                <div className="vocab-answer mt-4">
                                    <div className="dictionary-section-title">Answer</div>
                                    <div className="theme-copy mt-2 whitespace-pre-wrap text-sm leading-6">
                                        {activeItem.result}
                                    </div>
                                </div>
                            ) : (
                                <button
                                    type="button"
                                    className="btn mt-4"
                                    onClick={() => setAnswerVisible(true)}
                                >
                                    Show answer
                                </button>
                            )}
                        </div>

                        <div className="flex min-w-52 flex-col gap-2">
                            <Link className="btn-secondary" to={readerPathForVocabularyItem(activeItem)}>
                                Open in reader
                            </Link>
                            {answerVisible && (
                                <>
                                    <button className="btn-secondary" onClick={() => applyReview('again')}>
                                        Again
                                    </button>
                                    <button className="btn-secondary" onClick={() => applyReview('good')}>
                                        Good
                                    </button>
                                    <button className="btn" onClick={() => applyReview('known')}>
                                        Known
                                    </button>
                                </>
                            )}
                        </div>
                    </div>
                </section>
            ) : (
                <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                    <div className="theme-heading text-lg font-semibold">No saved lookups yet</div>
                    <p className="mt-2 text-sm muted-copy">
                        Open the reader, select a word or sentence, translate it, and save the result.
                    </p>
                </section>
            )}

            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
                    <div>
                        <h3 className="theme-heading text-xl font-semibold">Saved items</h3>
                        <p className="mt-1 text-sm muted-copy">
                            {visibleItems.length} item{visibleItems.length === 1 ? '' : 's'} shown
                        </p>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-[minmax(12rem,16rem)_auto] sm:items-end">
                        <label className="flex flex-col gap-2 text-sm">
                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">
                                Language
                            </span>
                            <select
                                value={languageFilter}
                                onChange={e => {
                                    setLanguageFilter(e.target.value)
                                    setActiveId(null)
                                    setAnswerVisible(false)
                                }}
                                className="input min-w-48"
                            >
                                <option value={ALL_LANGUAGE_FILTER}>All languages</option>
                                {languageOptions.map(option => (
                                    <option key={option.key} value={option.key}>
                                        {option.source.toUpperCase()} → {option.target.toUpperCase()} ({option.count})
                                    </option>
                                ))}
                            </select>
                        </label>
                        <div className="flex gap-2">
                            <button
                                type="button"
                                className={filter === 'due' ? 'btn' : 'btn-secondary'}
                                onClick={() => {
                                    setFilter('due')
                                    setActiveId(null)
                                    setAnswerVisible(false)
                                }}
                            >
                                Due
                            </button>
                            <button
                                type="button"
                                className={filter === 'all' ? 'btn' : 'btn-secondary'}
                                onClick={() => {
                                    setFilter('all')
                                    setActiveId(null)
                                    setAnswerVisible(false)
                                }}
                            >
                                All
                            </button>
                        </div>
                    </div>
                </div>

                <div className="mt-4 space-y-3">
                    {visibleItems.map(item => (
                        <article key={item.id} className="vocab-list-item">
                            <button
                                type="button"
                                className="min-w-0 flex-1 text-left"
                                onClick={() => {
                                    setActiveId(item.id)
                                    setAnswerVisible(false)
                                }}
                            >
                                <div className="flex flex-wrap items-center gap-2">
                                    <span className="theme-heading font-medium">{item.text}</span>
                                    <span className="soft-badge">{item.source.toUpperCase()} → {item.target.toUpperCase()}</span>
                                    <span className="soft-badge">{item.review.status}</span>
                                </div>
                                <div className="mt-1 truncate text-sm muted-copy">
                                    {item.result}
                                </div>
                                <div className="mt-1 text-xs muted-copy">
                                    {nextDueText(item)} · reviewed {item.review.reviewCount} time{item.review.reviewCount === 1 ? '' : 's'}
                                </div>
                            </button>
                            <button
                                type="button"
                                className="btn-secondary text-xs"
                                onClick={() => removeItem(item.id)}
                            >
                                Delete
                            </button>
                        </article>
                    ))}

                    {visibleItems.length === 0 && (
                        <div className="muted-copy rounded-[20px] border border-[color:var(--border)] px-4 py-4 text-sm">
                            No items in this view.
                        </div>
                    )}
                </div>
            </section>
        </div>
    )
}

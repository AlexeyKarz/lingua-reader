import { useEffect, useState } from 'react'

type CatalogEntry = {
    id: string
    title: string
    author: string | null
    year: number | null
    langs: string[]
    originalLang?: string | null
}

export default function CatalogPage() {
    const [books, setBooks] = useState<CatalogEntry[]>([])
    const [status, setStatus] = useState<'idle' | 'loading' | 'error'>('idle')

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

    return (
        <div>
            <h2 className="text-2xl font-semibold mb-4">Catalog</h2>

            {status === 'loading' && <p className="text-sm opacity-70">Loading…</p>}
            {status === 'error' && (
                <p className="text-sm text-red-700">Could not load catalog.</p>
            )}

            <div className="space-y-4">
                {books.map(book => (
                    <div key={book.id} className="card p-4 flex flex-col gap-1">
                        <div className="flex items-baseline justify-between">
                            <h3 className="text-lg font-medium">{book.title}</h3>
                            {book.year && (
                                <span className="text-xs opacity-60">{book.year}</span>
                            )}
                        </div>
                        {book.author && (
                            <div className="text-sm opacity-80">by {book.author}</div>
                        )}

                        <div className="mt-2 flex flex-wrap gap-2 items-center text-xs">
                            <span className="opacity-70 mr-1">Available languages:</span>
                            {book.langs.map(l => (
                                <span
                                    key={l}
                                    className="px-2 py-[2px] rounded-full border border-amber-300/70 bg-amber-50"
                                >
                  {l.toUpperCase()}
                </span>
                            ))}
                        </div>

                        {/* Reader links: original -> translations if originalLang is set; else first -> others */}
                        <div className="mt-3 flex flex-wrap gap-2 text-sm">
                            {book.langs.length < 2 ? (
                                <span className="text-xs opacity-60">Add another language edition to read side-by-side.</span>
                            ) : (
                                (() => {
                                    const src = book.originalLang && book.langs.includes(book.originalLang)
                                        ? book.originalLang
                                        : book.langs[0]
                                    const tgts = book.langs.filter(l => l !== src)
                                    return tgts.map(tgt => (
                                        <a
                                            key={`${src}-${tgt}`}
                                            href={`/reader?bookId=${encodeURIComponent(book.id)}&src=${src}&tgt=${tgt}`}
                                            className="btn"
                                        >
                                            Read {src.toUpperCase()} → {tgt.toUpperCase()}
                                        </a>
                                    ))
                                })()
                            )}
                        </div>
                    </div>
                ))}

                {books.length === 0 && status === 'idle' && (
                    <p className="text-sm opacity-70">No books yet.</p>
                )}
            </div>
        </div>
    )
}

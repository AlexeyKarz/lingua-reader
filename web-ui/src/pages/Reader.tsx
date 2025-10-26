import { useEffect, useLayoutEffect, useRef, useState } from 'react'

type Aligned = { left: string[]; right: string[]; bookId: string; src: string; tgt: string }

type PopState =
    | { kind: 'hidden' }
    | {
    kind: 'ready' | 'loading' | 'result' | 'error'
    text: string
    rect: DOMRect
    sourceLang: string
    targetLang: string
    result?: string
}

export default function Reader() {

    const [page, setPage] = useState(0)
    const size = 250 // words per page

    // Query params (example: ?bookId=faust&src=de&tgt=en)
    const params = new URLSearchParams(location.search)
    const bookId = params.get('bookId') ?? 'faust'
    // LEFT = original (src), RIGHT = publisher translation (tgt)
    const src = params.get('src') ?? 'de'
    const tgt = params.get('tgt') ?? 'en'

    const [data, setData] = useState<Aligned | null>(null)
    const [info, setInfo] = useState('Ready')

    // Popover & positioning
    const [pop, setPop] = useState<PopState>({ kind: 'hidden' })
    const popRef = useRef<HTMLDivElement>(null)
    const [coords, setCoords] = useState<{ top: number; left: number }>({ top: -9999, left: -9999 })

    // Panes — LEFT (original/src), RIGHT (translation/tgt)
    const leftRef = useRef<HTMLDivElement>(null)
    const rightRef = useRef<HTMLDivElement>(null)

    // Load first page (or fallback)
    useEffect(() => {
        ;(async () => {
            setInfo('Loading…')
            try {
                const r = await fetch(`/api/reader/pages?bookId=${bookId}&src=${src}&tgt=${tgt}&page=${page}&size=${size}`)
                const j = await r.json()
                setData(j)
                const fallback = {
                    bookId, src, tgt,
                    left: ["Habe nun, ach! Philosophie, Juristerei und Medizin, und leider auch Theologie!", "Durchaus studiert, mit heißem Bemühn."],
                    right:["I’ve studied now Philosophy and Jurisprudence, Medicine,—and even, alas! Theology—", "From end to end, with labor keen."]
                }
                setData(j.left ? j : fallback)
                setInfo('Loaded')
            } catch {
                setData(null)
                setInfo('Error loading text')
            }
        })()
    }, [bookId, src, tgt])

    // trigger reload when page changes
    useEffect(() => {
        (async () => {
            setInfo('Loading…')
            try {
                const r = await fetch(`/api/reader/pages?bookId=${bookId}&src=${src}&tgt=${tgt}&page=${page}&size=${size}`)
                const j = await r.json()
                setData(j)
                setInfo(`Loaded page ${page}`)
            } catch {
                setInfo('Error')
            }
        })()
    }, [bookId, src, tgt, page])

    // Utility: clamp number into [min, max]
    const clamp = (n: number, min: number, max: number) => Math.max(min, Math.min(n, max))

    // Compute popover coords after it (re)renders or when selection rect changes.
    useLayoutEffect(() => {
        if (pop.kind === 'hidden') return
        const el = popRef.current
        if (!el) return

        const margin = 8 // viewport padding
        const gap = 8    // gap from selection

        const vw = window.innerWidth
        const vh = window.innerHeight

        // Prefer placing ABOVE selection; if not enough room, place BELOW.
        // We need the element’s size to compute safe coords; measure after render.
        const { width: pw, height: ph } = el.getBoundingClientRect() // position by size of popover
        const sel = pop.rect

        const centerX = sel.left + sel.width / 2
        const placeAbove = sel.top - ph - gap >= margin
        const top = placeAbove ? sel.top - ph - gap : sel.bottom + gap

        // Center horizontally on selection, then clamp inside viewport.
        let left = centerX - pw / 2
        left = clamp(left, margin, vw - pw - margin)

        // Also clamp top within viewport just in case (e.g., extremely small vh).
        const safeTop = clamp(top, margin, vh - ph - margin)

        setCoords({ top: safeTop, left })
    }, [pop])

    // Reposition on resize/scroll while popover is visible
    useEffect(() => {
        if (pop.kind === 'hidden') return
        const onR = () => setPop(p => (p.kind === 'hidden' ? p : { ...p })) // trigger useLayoutEffect
        window.addEventListener('resize', onR)
        window.addEventListener('scroll', onR, true)
        return () => {
            window.removeEventListener('resize', onR)
            window.removeEventListener('scroll', onR, true)
        }
    }, [pop.kind])

    // close when the text is not selected anymore
    useEffect(() => {
        const onSelChange = () => {
            const sel = document.getSelection(); // or window.getSelection()
            // If selection is empty/collapsed -> close
            if (!sel || sel.isCollapsed || !sel.toString().trim()) {
                setPop({ kind: 'hidden' })
            }
        };
        document.addEventListener('selectionchange', onSelChange);
        return () => document.removeEventListener('selectionchange', onSelChange);
    }, []);


    // Selection handler for each pane
    function handleMouseUp(which: 'left' | 'right') {
        const host = which === 'left' ? leftRef.current : rightRef.current
        if (!host) return

        const sel = window.getSelection()
        if (!sel || sel.isCollapsed) return
        if (!sel.anchorNode || !sel.focusNode) return
        if (!host.contains(sel.anchorNode) || !host.contains(sel.focusNode)) return

        const text = sel.toString().trim()
        if (!text) return

        const range = sel.getRangeAt(0)
        const rect = range.getBoundingClientRect() // viewport rect of selection

        // LEFT pane = original (src) → translate to tgt; RIGHT pane = translation (tgt) → translate to src
        const sourceLang = which === 'left' ? src : tgt
        const targetLang = which === 'left' ? tgt : src

        // Show the popover in "ready" state (only a button for now)
        setPop({ kind: 'ready', text, rect, sourceLang, targetLang })
    }

    async function doTranslate() {
        if (pop.kind !== 'ready' && pop.kind !== 'error') return
        const { text, sourceLang, targetLang } = pop
        const isWord = text.length <= 40 && !/\s/.test(text)

        setPop({ ...pop, kind: 'loading' })
        try {
            if (isWord) {
                const qs = new URLSearchParams({ text, source: sourceLang, target: targetLang })
                const r = await fetch(`/api/translate/word?${qs}`)
                const translated = await r.text()
                setPop(p => (p.kind === 'loading' ? { ...p, kind: 'result', result: translated } : p))
            } else {
                const r = await fetch('/api/translate/sentence', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ text, source: sourceLang, target: targetLang })
                })
                const j = await r.json()
                setPop(p => (p.kind === 'loading' ? { ...p, kind: 'result', result: j.translated } : p))
            }
        } catch {
            setPop(p => (p.kind === 'loading' ? { ...p, kind: 'error' } : p))
        }
    }

    // Close on Escape / background clicks
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setPop({ kind: 'hidden' })
        const onDown = (e: MouseEvent) => {
            const target = e.target as Node
            if (pop.kind === 'hidden') return
            if (popRef.current && popRef.current.contains(target)) return
            if (leftRef.current?.contains(target) || rightRef.current?.contains(target)) return
            setPop({ kind: 'hidden' })
        }
        window.addEventListener('keydown', onKey)
        window.addEventListener('mousedown', onDown)
        return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('mousedown', onDown) }
    }, [pop.kind])

    return (
        <div>
            <div className="mb-3 flex items-baseline gap-4">
                <h3 className="text-xl font-medium">Reader</h3>
                <span className="text-sm opacity-70">{info}</span>
            </div>

            {/* Titles */}
            <div className="grid grid-cols-2 gap-4 mb-2">
                <div className="text-sm opacity-70">
                    <div className="font-semibold">Original ({src.toUpperCase()})</div>
                    <div className="opacity-70">Author(s): (e.g., Goethe)</div>
                </div>
                <div className="text-right text-sm opacity-70">
                    <div className="font-semibold">Publisher Translation ({tgt.toUpperCase()})</div>
                    <div className="opacity-70">Translator: (add manually)</div>
                </div>
            </div>

            {/* Split panes: LEFT original (src), RIGHT translation (tgt) */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 relative">
                <div
                    ref={leftRef}
                    onMouseUp={() => handleMouseUp('left')}
                    className="card p-4 leading-7 select-text"

                >
                    {data?.left && data.left[0]?.trim()
                        ? <p className="mb-3">{data.left[0]}</p>
                        : <p className="mb-3 italic opacity-60">No content loaded.</p>}
                </div>

                <div
                    ref={rightRef}
                    onMouseUp={() => handleMouseUp('right')}
                    className="card p-4 leading-7 select-text"
                >
                    {data?.right?.slice(0, 8).map((p, i) => <p key={i} className="mb-3">{p}</p>)}
                </div>

                {/* pager controls */}
                <div className="mb-3 flex items-center gap-3">
                    <button className="btn" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page <= 0}>Prev</button>
                    <div className="text-sm">Page {page + 1}</div>
                    <button className="btn" onClick={() => setPage(p => p + 1)}>Next</button>
                    <span className="text-xs opacity-70">(~{size} words per language)</span>
                </div>


                {/* Edge-aware popover */}
                {pop.kind !== 'hidden' && (
                    <div ref={popRef} className="fixed z-50" style={{ top: `${coords.top}px`, left: `${coords.left}px` }}>
                        <div className="card p-3 max-w-md relative">
                            {/* top-right close */}
                            <button
                                aria-label="Close"
                                onClick={() => setPop({ kind: 'hidden' })}
                                className="absolute -right-2 -top-2 h-6 w-6 rounded-full bg-red-600 text-white text-sm leading-6 text-center
                                    hover:bg-red-700 active:bg-red-800 transition-colors"
                                title="Close"
                            >
                                ×
                            </button>


                            <div className="text-xs mb-1 opacity-70">
                                {pop.sourceLang.toUpperCase()} → {pop.targetLang.toUpperCase()}
                            </div>

                            {pop.kind === 'ready' && (
                                <div className="flex items-center gap-3">
                                    <div className="text-sm font-semibold truncate max-w-[16rem]">“{pop.text}”</div>
                                    <button className="btn" onClick={doTranslate}>Translate</button>
                                </div>
                            )}
                            {pop.kind === 'loading' && <div className="text-sm">Translating…</div>}
                            {pop.kind === 'result' && (
                                <>
                                    <div className="text-sm font-semibold mb-1">“{pop.text}”</div>
                                    <div className="text-sm whitespace-pre-wrap">{pop.result ?? '—'}</div>
                                </>
                            )}
                            {pop.kind === 'error' && <div className="text-sm text-red-700">Error. Please try again.</div>}
                        </div>
                    </div>
                )}
            </div>

            <p className="mt-3 text-xs opacity-70">
                Tip: select a word or drag a sentence. Click <b>Translate</b> near your selection.
            </p>
        </div>
    )
}

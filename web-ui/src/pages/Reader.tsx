import {useEffect, useLayoutEffect, useRef, useState, useMemo, type JSX} from 'react'

// type Aligned = { left: string[]; right: string[]; bookId: string; src: string; tgt: string }

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

type Sentence = { id: number; text: string }

type AlignmentLink = {
    leftId: number
    rightId: number
    score: number
}

type PageData = {
    bookId: string
    src: string
    tgt: string
    page: number
    size: number
    totalSrc: number
    totalTgt: number
    left: Sentence[]
    right: Sentence[]
    alignments: AlignmentLink[]
}


export default function Reader() {

    const [page, setPage] = useState(0)
    const size = 20 // sentences per page

    const [notice, setNotice] = useState<string | null>(null)

    const [data, setData] = useState<PageData | null>(null)
    const [info, setInfo] = useState('Ready')

    // map leftId -> rightId for current page
    const [alignMap, setAlignMap] = useState<Map<number, number>>(new Map())
    const [highlightRightId, setHighlightRightId] = useState<number | null>(null)

    // right-side sentence refs for scrolling
    const rightRefs = useRef<Record<number, HTMLParagraphElement | null>>({})

    const alignedRightIds = useMemo(() => {
        const set = new Set<number>()
        data?.alignments?.forEach(link => {
            set.add(link.rightId)
        })
        return set
    }, [data])

    const [expandedGroups, setExpandedGroups] = useState<Set<number>>(new Set())

    // Query params (example: ?bookId=faust&src=de&tgt=en)
    const params = new URLSearchParams(location.search)
    // const bookId = params.get('bookId') ?? 'faust'
    const bookId =
        params.get('bookId') ?? 'Faust (Part I)' // fallback, but ideally always provided
    const src = params.get('src') ?? 'de'
    const tgt = params.get('tgt') ?? 'en'
    // LEFT = original (src), RIGHT = publisher translation (tgt)

    // const [data, setData] = useState<Aligned | null>(null)
    // const [info, setInfo] = useState('Ready')

    // Front matter states
    const [frontMatter, setFrontMatter] = useState<{
        left: Sentence[]
        right: Sentence[]
    } | null>(null)

    const [showFrontLeft, setShowFrontLeft] = useState(false)
    const [showFrontRight, setShowFrontRight] = useState(false)
    const [frontInfo, setFrontInfo] = useState<string>('') // optional small status

    // Popover & positioning
    const [pop, setPop] = useState<PopState>({ kind: 'hidden' })
    const popRef = useRef<HTMLDivElement>(null)
    const [coords, setCoords] = useState<{ top: number; left: number }>({ top: -9999, left: -9999 })

    // Panes — LEFT (original/src), RIGHT (translation/tgt)
    const leftRef = useRef<HTMLDivElement>(null)
    const rightRef = useRef<HTMLDivElement>(null)

    useEffect(() => {
        ;(async () => {
            setInfo('Loading…')
            try {
                const qs = new URLSearchParams({
                    bookId,
                    src,
                    tgt,
                    page: String(page),
                    size: String(size),
                    method: 'dp-mlMiniLM-v1-de-en', // or your method name
                })
                const r = await fetch(`/api/reader/pages?${qs}`)
                const j = (await r.json()) as PageData
                setData(j)
                setInfo(
                    `Loaded page ${j.page + 1} · ${j.left.length} DE sentences / ${j.right.length} EN sentences`
                )

                // Build alignment map leftId -> rightId (take best per leftId)
                const m = new Map<number, number>()
                j.alignments.forEach(link => {
                    if (!m.has(link.leftId)) {
                        m.set(link.leftId, link.rightId)
                    }
                })
                setAlignMap(m)
                setHighlightRightId(null)
                // reset refs
                rightRefs.current = {}
            } catch (e) {
                console.error(e)
                setData(null)
                setInfo('Error loading page')
            }
        })()
    }, [bookId, src, tgt, page])

    // close notice (no alignment) automatically
    useEffect(() => {
        if (!notice) return

        const id = window.setTimeout(() => {
            setNotice(null)
        }, 5_000) // 5 seconds

        return () => {
            window.clearTimeout(id)
        }
    }, [notice])

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

    // prefetch the front matter
    useEffect(() => {
        if (page !== 0) return
        // fire and forget; we don't care about the result here
        void ensureFrontMatterLoaded()
    }, [page, bookId, src, tgt])

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

    function toggleGroup(idx: number) {
        setExpandedGroups(prev => {
            const next = new Set(prev)
            if (next.has(idx)) next.delete(idx)
            else next.add(idx)
            return next
        })
    }


    function handleLeftSentenceClick(sentenceId: number) {
        const rightId = alignMap.get(sentenceId)
        if (!rightId) {
            setNotice(
                'No aligned publisher translation for this sentence. Check unaligned text (if any) or use AI translation.'
            )
            return
        }

        setHighlightRightId(rightId)
        const el = rightRefs.current[rightId]
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        }
    }




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

    async function ensureFrontMatterLoaded() {
        if (frontMatter !== null) return

        try {
            setFrontInfo('Loading front matter…')
            const qs = new URLSearchParams({
                bookId,
                src,
                tgt,
            })
            const r = await fetch(`/api/reader/front-matter?${qs}`)
            const j = await r.json()
            setFrontMatter({
                left: (j.left as Sentence[]) ?? [],
                right: (j.right as Sentence[]) ?? [],
            })
            setFrontInfo('')
        } catch (e) {
            console.error(e)
            setFrontInfo('Error loading front matter')
        }
    }

    async function toggleFrontLeft() {
        await ensureFrontMatterLoaded()
        setShowFrontLeft(v => !v)
    }

    async function toggleFrontRight() {
        await ensureFrontMatterLoaded()
        setShowFrontRight(v => !v)
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
                    {page === 0 && (
                        <>
                    {/* Front matter toggle, only on the first page */}
                    <div className="mb-3 flex items-center justify-between gap-2">
                        <button
                            type="button"
                            className="text-xs px-2 py-1 rounded border border-amber-300/70 text-amber-800 bg-amber-50 hover:bg-amber-100 transition-colors"
                            onClick={toggleFrontLeft}
                        >
                            {showFrontLeft
                                ? `Hide front matter`
                                : `Show front matter${
                                    frontMatter && frontMatter.left.length
                                        ? ` (${frontMatter.left.length} sentences)`
                                        : ''
                                }`}
                        </button>
                        {frontInfo && <span className="text-[10px] opacity-60">{frontInfo}</span>}
                    </div>

                    {/* Expanded front matter */}
                    {showFrontLeft && frontMatter && frontMatter.left.length > 0 && (
                        <div className="mb-3 border-l border-amber-200 pl-3 text-sm opacity-80">
                            {frontMatter.left.map(s => (
                                <p key={s.id} className="mb-2 italic">
                                    {s.text}
                                </p>
                            ))}
                            <hr className="border-amber-200/70 my-2" />
                        </div>
                    )}
                </>
                )}

                    {/* Main body sentences */}
                    {data?.left.map(s => (
                        <p
                            key={s.id}
                            className="mb-3 cursor-pointer hover:bg-amber-100/60 transition-colors"
                            onClick={() => handleLeftSentenceClick(s.id)}
                        >
                            {s.text}
                        </p>
                    ))}
                </div>


                {/*<div*/}
                {/*    ref={rightRef}*/}
                {/*    onMouseUp={() => handleMouseUp('right')}*/}
                {/*    className="card p-4 leading-7 select-text"*/}
                {/*>*/}
                {/*    {data?.right.map(s => (*/}
                {/*        <p*/}
                {/*            key={s.id}*/}
                {/*            ref={el => {*/}
                {/*                rightRefs.current[s.id] = el*/}
                {/*            }}*/}
                {/*            className={*/}
                {/*                'mb-3 transition-colors ' +*/}
                {/*                (highlightRightId === s.id ? 'bg-amber-200/80' : '')*/}
                {/*            }*/}
                {/*        >*/}
                {/*            {s.text}*/}
                {/*        </p>*/}
                {/*    ))}*/}
                {/*</div>*/}

                <div
                    ref={rightRef}
                    onMouseUp={() => handleMouseUp('right')}
                    className="card p-4 leading-7 select-text"
                >
                    {page === 0 && (
                        <>
                    {/* Front matter toggle */}
                    <div className="mb-3 flex items-center justify-between gap-2">
                        <button
                            type="button"
                            className="text-xs px-2 py-1 rounded border border-amber-300/70 text-amber-800 bg-amber-50 hover:bg-amber-100 transition-colors"
                            onClick={toggleFrontRight}
                        >
                            {showFrontRight
                                ? `Hide front matter`
                                : `Show front matter${
                                    frontMatter && frontMatter.right.length
                                        ? ` (${frontMatter.right.length} sentences)`
                                        : ''
                                }`}
                        </button>
                        {frontInfo && <span className="text-[10px] opacity-60">{frontInfo}</span>}
                    </div>

                    {showFrontRight && frontMatter && frontMatter.right.length > 0 && (
                        <div className="mb-3 border-l border-amber-200 pl-3 text-sm opacity-80">
                            {frontMatter.right.map(s => (
                                <p
                                    key={s.id}
                                    ref={el => {
                                        rightRefs.current[s.id] = el
                                    }}
                                    className="mb-2 italic"
                                >
                                    {s.text}
                                </p>
                            ))}
                            <hr className="border-amber-200/70 my-2" />
                        </div>
                    )}
                        </>
                    )}

                    {/* aligned sentences + buttons for unaligned sentences*/}
                    {(() => {
                        if (!data?.right) return null

                        const nodes: JSX.Element[] = []
                        let buffer: typeof data.right = []
                        let groupIndex = 0

                        const flushGroup = () => {
                            if (buffer.length === 0) return
                            const idx = groupIndex++
                            const expanded = expandedGroups.has(idx)

                            nodes.push(
                                <div key={`grp-${idx}`} className="mb-2">
                                    <button
                                        type="button"
                                        className="text-xs px-2 py-1 rounded border border-amber-300/70 text-amber-800 bg-amber-50 hover:bg-amber-100 transition-colors"
                                        onClick={() => toggleGroup(idx)}
                                    >
                                        {expanded
                                            ? `Hide ${buffer.length} unaligned sentence${buffer.length > 1 ? 's' : ''}`
                                            : `Show ${buffer.length} unaligned sentence${buffer.length > 1 ? 's' : ''}`}
                                    </button>
                                    {expanded && (
                                        <div className="mt-1 pl-3 border-l border-amber-200">
                                            {buffer.map(s => (
                                                <p
                                                    key={s.id}
                                                    ref={el => {
                                                        rightRefs.current[s.id] = el
                                                    }}
                                                    className="mb-2 text-sm opacity-80 italic"
                                                >
                                                    {s.text}
                                                </p>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            )

                            buffer = []
                        }

                        data.right.forEach(s => {
                            const isAligned = alignedRightIds.has(s.id)

                            if (isAligned) {
                                // Finish any buffered unaligned group before this aligned sentence
                                flushGroup()

                                nodes.push(
                                    <p
                                        key={s.id}
                                        ref={el => {
                                            rightRefs.current[s.id] = el
                                        }}
                                        className={
                                            'mb-3 transition-colors ' +
                                            (highlightRightId === s.id ? 'bg-amber-200/80' : '')
                                        }
                                    >
                                        {s.text}
                                    </p>
                                )
                            } else {
                                buffer.push(s)
                            }
                        })

                        // Flush trailing group if any
                        flushGroup()

                        return nodes
                    })()}
                </div>



                {/* pager controls */}
                <div className="mb-3 flex items-center gap-3">
                    <button className="btn" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page <= 0}>Prev</button>
                    <div className="text-sm">Page {page + 1}</div>
                    <button className="btn" onClick={() => setPage(p => p + 1)}>Next</button>
                    <span className="text-xs opacity-70">(~{size} sentences per language)</span>
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

            {notice && (
                <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50">
                    <div className="card px-4 py-3 shadow-lg border border-amber-200 bg-amber-50 max-w-lg flex items-start gap-3">
                        <div className="mt-1 text-amber-700">
                            {/* small info dot */}
                            <span className="inline-block h-2 w-2 rounded-full bg-amber-500" />
                        </div>
                        <div className="text-sm text-amber-900">
                            {notice}
                        </div>
                        <button
                            type="button"
                            aria-label="Close"
                            onClick={() => setNotice(null)}
                            className="ml-2 text-xs text-amber-700 hover:text-amber-900 px-1 rounded hover:bg-amber-100"
                        >
                            ✕
                        </button>
                    </div>
                </div>
            )}

        </div>
    )
}

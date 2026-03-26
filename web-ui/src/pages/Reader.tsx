import {useCallback, useEffect, useLayoutEffect, useRef, useState, useMemo, type JSX} from 'react'

// type Aligned = { left: string[]; right: string[]; bookId: string; src: string; tgt: string }

type PopState =
    | { kind: 'hidden' }
    | {
    kind: 'ready' | 'loading' | 'result' | 'error'
    pane: 'left' | 'right'
    sentenceId?: number
    text: string
    rect: DOMRect
    sourceLang: string
    targetLang: string
    result?: string
    action?: 'translate' | 'explain'
}

type Sentence = { id: number; text: string; chapterId?: number | null; chapterTitle?: string | null }

type AlignmentLink = {
    leftId: number
    rightId: number
    score: number
}

type PageData = {
    bookId: string
    src: string
    tgt: string
    title?: string
    author?: string | null
    page: number
    size: number
    totalSrc: number
    totalTgt: number
    left: Sentence[]
    right: Sentence[]
    alignments: AlignmentLink[]
}

type SavedReaderState = {
    page: number
    size: number
    savedAt: number
}

const DEFAULT_PAGE_SIZE = 20
const LAST_READER_SESSION_KEY = 'mlr:last-reader-session'

function getReaderProgressKey(bookId: string, src: string, tgt: string) {
    return `mlr:reader-progress:${bookId}:${src}:${tgt}`
}

function readSavedReaderState(bookId: string, src: string, tgt: string): SavedReaderState | null {
    try {
        const raw = window.localStorage.getItem(getReaderProgressKey(bookId, src, tgt))
        if (!raw) return null
        const parsed = JSON.parse(raw) as Partial<SavedReaderState>
        if (
            typeof parsed.page !== 'number' ||
            !Number.isFinite(parsed.page) ||
            parsed.page < 0 ||
            typeof parsed.size !== 'number' ||
            !Number.isFinite(parsed.size) ||
            parsed.size < 1
        ) {
            return null
        }
        return {
            page: Math.floor(parsed.page),
            size: Math.floor(parsed.size),
            savedAt: typeof parsed.savedAt === 'number' ? parsed.savedAt : Date.now(),
        }
    } catch {
        return null
    }
}

function writeSavedReaderState(bookId: string, src: string, tgt: string, state: SavedReaderState) {
    try {
        window.localStorage.setItem(getReaderProgressKey(bookId, src, tgt), JSON.stringify(state))
        window.localStorage.setItem(
            LAST_READER_SESSION_KEY,
            JSON.stringify({ bookId, src, tgt, ...state })
        )
    } catch {
        // Ignore quota/storage failures; reader should still work without persistence.
    }
}

export default function Reader() {
    const params = new URLSearchParams(location.search)

    const bookId =
        params.get('bookId') ?? 'Faust (Part I)' // fallback, but ideally always provided

    const src = params.get('src') ?? 'de'
    const tgt = params.get('tgt') ?? 'en'

    const readerStorageKey = useMemo(
        () => getReaderProgressKey(bookId, src, tgt),
        [bookId, src, tgt]
    )

    const [page, setPage] = useState(0)
    const [size, setSize] = useState(DEFAULT_PAGE_SIZE) // sentences per page
    const [jumpInput, setJumpInput] = useState('1')
    const [jumpError, setJumpError] = useState<string | null>(null)
    const [errorMsg, setErrorMsg] = useState<string | null>(null)
    const [progressReady, setProgressReady] = useState(false)

    const rangeRef = useRef<Range | null>(null)

    const [notice, setNotice] = useState<string | null>(null)

    const [data, setData] = useState<PageData | null>(null)
    const [info, setInfo] = useState('Ready')
    const totalPages = useMemo(
        () => (data?.totalSrc ? Math.max(1, Math.ceil(data.totalSrc / size)) : null),
        [data?.totalSrc, size]
    )
    const isEmptyPair =
        !!data &&
        data.totalSrc === 0 &&
        data.totalTgt === 0 &&
        data.left.length === 0 &&
        data.right.length === 0

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

    const leftById = useMemo(
        () => new Map((data?.left ?? []).map(sentence => [sentence.id, sentence])),
        [data?.left]
    )

    const rightById = useMemo(
        () => new Map((data?.right ?? []).map(sentence => [sentence.id, sentence])),
        [data?.right]
    )

    const rightToLeftMap = useMemo(() => {
        const map = new Map<number, number>()
        data?.alignments?.forEach(link => {
            if (!map.has(link.rightId)) {
                map.set(link.rightId, link.leftId)
            }
        })
        return map
    }, [data?.alignments])

    const [expandedGroups, setExpandedGroups] = useState<Set<number>>(new Set())

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

    const [status, setStatus] = useState<'idle' | 'loading' | 'error'>('idle')

    // Panes — LEFT (original/src), RIGHT (translation/tgt)
    const leftRef = useRef<HTMLDivElement>(null)
    const rightRef = useRef<HTMLDivElement>(null)
    const leftContentRef = useRef<HTMLDivElement>(null)
    const rightContentRef = useRef<HTMLDivElement>(null)

    // Jump handler
    function handleJump() {
        const val = Number(jumpInput)
        if (!Number.isFinite(val) || val < 1) {
            setJumpError('Enter a positive page number')
            return
        }
        if (totalPages && val > totalPages) {
            setJumpError(`Max page is ${totalPages}`)
            return
        }
        setJumpError(null)
        setPage(val - 1)
    }

    useEffect(() => {
        const saved = readSavedReaderState(bookId, src, tgt)
        const nextPage = saved?.page ?? 0
        const nextSize = saved?.size ?? DEFAULT_PAGE_SIZE

        setProgressReady(false)
        setPage(nextPage)
        setSize(nextSize)
        setJumpInput(String(nextPage + 1))
        setJumpError(null)
        setProgressReady(true)
    }, [readerStorageKey, bookId, src, tgt])

    useEffect(() => {
        if (!progressReady) return
        ;(async () => {
            setStatus('loading')
            setErrorMsg(null)
            setInfo('Loading…')
            try {
        const qs = new URLSearchParams({
            bookId,
            src,
            tgt,
            page: String(page),
                    size: String(size),
                    method: `dp-mlMiniLM-v1-${src}-${tgt}`,
                })
                const r = await fetch(`/api/reader/pages?${qs}`)
                if (!r.ok) {
                    const msg = await r.text().catch(() => '')
                    throw new Error(msg || 'Failed to load page')
                }
                const j = (await r.json()) as PageData
                setData(j)
                setInfo(
                    `Loaded page ${j.page + 1} · ${j.left.length} sentences (src) / ${j.right.length} sentences (tgt)`
                )
                setStatus('idle')

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
                setJumpInput(String(j.page + 1))
            } catch (e) {
                console.error(e)
                setData(null)
                setInfo('Error loading page (check language pair and alignments)')
                setErrorMsg(e instanceof Error ? e.message : 'Error loading page')
                setStatus('error')
            }
        })()
    }, [bookId, src, tgt, page, size, progressReady])

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

    useEffect(() => {
        if (!progressReady) return
        writeSavedReaderState(bookId, src, tgt, {
            page,
            size,
            savedAt: Date.now(),
        })
    }, [bookId, src, tgt, page, size, progressReady])

    // Utility: clamp number into [min, max]
    const clamp = (n: number, min: number, max: number) => Math.max(min, Math.min(n, max))

    function findSentenceIdFromSelectionNode(node: Node | null): number | undefined {
        if (!(node instanceof Element) && !(node instanceof Text)) return undefined
        const element = node instanceof Text ? node.parentElement : node
        const sentenceNode = element?.closest<HTMLElement>('[data-sentence-id]')
        if (!sentenceNode) return undefined
        const raw = sentenceNode.dataset.sentenceId
        if (!raw) return undefined
        const id = Number(raw)
        return Number.isFinite(id) ? id : undefined
    }

    type ChapterState = { current: number | null }

    function renderSentencesWithChapters(
        sentences: Sentence[] | undefined,
        side: 'left' | 'right',
        onSentenceClick?: (id: number) => void,
        chapterState?: ChapterState
    ) {
        if (!sentences) return null
        const nodes: JSX.Element[] = []
        const state = chapterState ?? { current: null }

        sentences.forEach(s => {
            const chapChanged = s.chapterId != null && s.chapterId !== state.current
            if (chapChanged) {
                state.current = s.chapterId as number
                if (s.chapterTitle) {
                    nodes.push(
                        <div key={`chap-${side}-${s.chapterId}`} className="mt-2 mb-1 text-sm font-semibold text-amber-800">
                            {s.chapterTitle}
                        </div>
                    )
                }
            }

            nodes.push(
                <p
                    key={s.id}
                    data-sentence-id={s.id}
                    ref={el => {
                        if (side === 'right') {
                            rightRefs.current[s.id] = el
                        }
                    }}
                    className={
                        'mb-3 transition-colors ' +
                        (side === 'left' ? 'cursor-pointer hover:bg-amber-100/60 ' : '') +
                        (highlightRightId === s.id && side === 'right' ? 'bg-amber-200/80' : '')
                    }
                    onClick={onSentenceClick ? () => onSentenceClick(s.id) : undefined}
                >
                    {s.text}
                </p>
            )
        })

        return nodes
    }

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

        const onR = () => {
            const r = rangeRef.current
            if (!r) return

            try {
                const newRect = r.getBoundingClientRect()
                // Avoid useless state churn when rect becomes empty
                if (!newRect || (newRect.width === 0 && newRect.height === 0)) return

                setPop(p => (p.kind === 'hidden' ? p : { ...p, rect: newRect }))
            } catch {
                // If the range becomes invalid (e.g., page changed),
                // we can safely hide the popover.
                setPop({ kind: 'hidden' })
                rangeRef.current = null
            }
        }

        window.addEventListener('resize', onR)
        window.addEventListener('scroll', onR, true)

        return () => {
            window.removeEventListener('resize', onR)
            window.removeEventListener('scroll', onR, true)
        }
    }, [pop.kind])


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
        const host = which === 'left' ? leftContentRef.current : rightContentRef.current
        if (!host) return

        const sel = window.getSelection()
        if (!sel || sel.isCollapsed) return
        if (!sel.anchorNode || !sel.focusNode) return
        if (!host.contains(sel.anchorNode) || !host.contains(sel.focusNode)) return

        const text = sel.toString().trim()
        if (!text) return

        const anchorSentenceId = findSentenceIdFromSelectionNode(sel.anchorNode)
        const focusSentenceId = findSentenceIdFromSelectionNode(sel.focusNode)
        const sentenceId =
            anchorSentenceId !== undefined && anchorSentenceId === focusSentenceId
                ? anchorSentenceId
                : undefined

        const range = sel.getRangeAt(0).cloneRange()
        rangeRef.current = range
        const rect = range.getBoundingClientRect()


        // LEFT pane = original (src) → translate to tgt; RIGHT pane = translation (tgt) → translate to src
        const sourceLang = which === 'left' ? src : tgt
        const targetLang = which === 'left' ? tgt : src

        // Show the popover in "ready" state (only a button for now)
        setPop({ kind: 'ready', pane: which, sentenceId, text, rect, sourceLang, targetLang })
    }

    async function doAction(action: 'translate' | 'explain') {
        if (pop.kind === 'hidden') return

        const canStart =
            pop.kind === 'ready' ||
            pop.kind === 'error' ||
            (action === 'explain' && pop.kind === 'result' && pop.action !== 'explain')

        if (!canStart) return

        const { text, sourceLang, targetLang } = pop
        const isWord = text.length <= 40 && !/\s/.test(text)

        setPop({ ...pop, kind: 'loading', action })

        try {
            let resultText = ''

            if (action === 'translate') {
                if (isWord) {
                    const qs = new URLSearchParams({ text, source: sourceLang, target: targetLang })
                    const r = await fetch(`/api/translate/word?${qs}`)
                    const j = await r.json()
                    resultText = j.translated ?? ''
                } else {
                    const r = await fetch('/api/translate/sentence', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ text, source: sourceLang, target: targetLang })
                    })
                    const j = await r.json()
                    resultText = j.translated ?? ''
                }
            } else {
                let alignedContext = ''
                if (pop.sentenceId !== undefined) {
                    if (pop.pane === 'left') {
                        const oppositeId = alignMap.get(pop.sentenceId)
                        const oppositeSentence = oppositeId ? rightById.get(oppositeId) : undefined
                        if (oppositeSentence?.text) {
                            alignedContext = `Aligned translation sentence: ${oppositeSentence.text}`
                        }
                    } else {
                        const oppositeId = rightToLeftMap.get(pop.sentenceId)
                        const oppositeSentence = oppositeId ? leftById.get(oppositeId) : undefined
                        if (oppositeSentence?.text) {
                            alignedContext = `Aligned original sentence: ${oppositeSentence.text}`
                        }
                    }
                }

                const contextParts = [
                    `Work: ${data?.title || bookId}`,
                    data?.author ? `Author: ${data.author}` : '',
                    `Selected side: ${pop.pane === 'left' ? 'original' : 'translation'}`,
                    `Reader pair: ${src.toUpperCase()} → ${tgt.toUpperCase()}`,
                    `Current page: ${page + 1}`,
                    alignedContext,
                ].filter(Boolean)
                const context = contextParts.join(' | ')
                const translation = pop.result ?? ''
                const r = await fetch('/api/translate/explain', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text,
                        source: sourceLang,
                        target: targetLang,
                        context,
                        translation
                    })
                })
                const j = await r.json()
                resultText = j.explanation ?? ''
            }

            setPop(p => (p.kind === 'loading'
                    ? { ...p, kind: 'result', result: resultText, action }
                    : p
            ))
        } catch {
            setPop(p => (p.kind === 'loading' ? { ...p, kind: 'error', action } : p))
        }
    }


    const ensureFrontMatterLoaded = useCallback(async () => {
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
    }, [frontMatter, bookId, src, tgt])

    // prefetch the front matter
    useEffect(() => {
        if (page !== 0) return
        void ensureFrontMatterLoaded()
    }, [page, ensureFrontMatterLoaded])

    useEffect(() => {
        rangeRef.current = null
        setPop({ kind: 'hidden' })
    }, [page, size, bookId, src, tgt])

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
            rangeRef.current = null
            setPop({ kind: 'hidden' })
        }
        window.addEventListener('keydown', onKey)
        window.addEventListener('mousedown', onDown)
        return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('mousedown', onDown) }
    }, [pop.kind])

    return (
        <div>
            <section className="surface-panel rounded-[32px] px-5 py-5 md:px-7 md:py-6">
                <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
                    <div>
                        <h3 className="text-3xl font-semibold text-slate-900 md:text-[2.2rem]">
                            {data?.title || bookId}
                        </h3>
                        <p className="mt-2 max-w-3xl text-sm leading-6 muted-copy md:text-[15px]">
                            Compare aligned passages, select words or sentences for literal translation,
                            and open short explanations without leaving the page.
                        </p>
                    </div>

                    <div className="grid gap-3 text-sm sm:grid-cols-3">
                        <div className="surface-panel-strong rounded-[24px] px-4 py-3">
                            <div className="text-xs uppercase tracking-[0.18em] muted-copy">Reading pair</div>
                            <div className="mt-2 font-medium text-slate-900">{src.toUpperCase()} → {tgt.toUpperCase()}</div>
                        </div>
                        <div className="surface-panel-strong rounded-[24px] px-4 py-3">
                            <div className="text-xs uppercase tracking-[0.18em] muted-copy">Author</div>
                            <div className="mt-2 font-medium text-slate-900">{data?.author || '—'}</div>
                        </div>
                        <div className="surface-panel-strong rounded-[24px] px-4 py-3">
                            <div className="text-xs uppercase tracking-[0.18em] muted-copy">Page</div>
                            <div className="mt-2 font-medium text-slate-900">
                                {page + 1}{data?.totalSrc ? ` / ${Math.max(1, Math.ceil(data.totalSrc / size))}` : ''}
                            </div>
                        </div>
                    </div>
                </div>
            </section>

            {status === 'error' && (
                <div className="mt-4 rounded-[24px] border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                    {errorMsg || 'Could not load this page. Check the language pair or try again.'}
                </div>
            )}
            {isEmptyPair && (
                <div className="mt-4 rounded-[24px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                    No aligned data for this book/language pair. Try another pair or ingest alignments.
                </div>
            )}

            <div className="mt-5 grid grid-cols-1 gap-4 md:grid-cols-2">
                <div
                    ref={leftRef}
                    className="reader-pane leading-8"
                >
                    <div className="reader-pane-header">
                        <div className="select-none">
                            <div className="soft-badge">Original ({src.toUpperCase()})</div>
                            <div className="mt-2 text-sm muted-copy">
                                Click a sentence to reveal the aligned translation on the opposite side.
                            </div>
                        </div>
                    </div>

                    {page === 0 && (
                        <>
                            <div className="mb-4 flex items-center justify-between gap-2 select-none">
                                <button
                                    type="button"
                                    className="btn-secondary text-xs"
                                    onClick={toggleFrontLeft}
                                >
                                    {showFrontLeft
                                        ? 'Hide front matter'
                                        : `Show front matter${
                                            frontMatter && frontMatter.left.length
                                                ? ` (${frontMatter.left.length})`
                                                : ''
                                        }`}
                                </button>
                                {frontInfo && <span className="text-[11px] muted-copy">{frontInfo}</span>}
                            </div>

                            {showFrontLeft && frontMatter && frontMatter.left.length > 0 && (
                                <div className="mb-4 rounded-[22px] border border-amber-200/80 bg-[color:var(--surface-tint)] px-4 py-4 text-sm text-slate-700">
                                    {frontMatter.left.map(s => (
                                        <p key={s.id} className="mb-2 italic last:mb-0">
                                            {s.text}
                                        </p>
                                    ))}
                                </div>
                            )}
                        </>
                    )}

                    <div
                        ref={leftContentRef}
                        onMouseUp={() => handleMouseUp('left')}
                        className="select-text"
                    >
                        {renderSentencesWithChapters(data?.left, 'left', handleLeftSentenceClick)}
                    </div>
                </div>

                <div
                    ref={rightRef}
                    className="reader-pane leading-8"
                >
                    <div className="reader-pane-header">
                        <div className="select-none">
                            <div className="soft-badge">Translation ({tgt.toUpperCase()})</div>
                            <div className="mt-2 text-sm muted-copy">
                                Select text here to translate back or request a short explanation.
                            </div>
                        </div>
                    </div>

                    {page === 0 && (
                        <>
                            <div className="mb-4 flex items-center justify-between gap-2 select-none">
                                <button
                                    type="button"
                                    className="btn-secondary text-xs"
                                    onClick={toggleFrontRight}
                                >
                                    {showFrontRight
                                        ? 'Hide front matter'
                                        : `Show front matter${
                                            frontMatter && frontMatter.right.length
                                                ? ` (${frontMatter.right.length})`
                                                : ''
                                        }`}
                                </button>
                                {frontInfo && <span className="text-[11px] muted-copy">{frontInfo}</span>}
                            </div>

                            {showFrontRight && frontMatter && frontMatter.right.length > 0 && (
                                <div className="mb-4 rounded-[22px] border border-amber-200/80 bg-[color:var(--surface-tint)] px-4 py-4 text-sm text-slate-700">
                                    {frontMatter.right.map(s => (
                                        <p
                                            key={s.id}
                                            ref={el => {
                                                rightRefs.current[s.id] = el
                                            }}
                                            className="mb-2 italic last:mb-0"
                                        >
                                            {s.text}
                                        </p>
                                    ))}
                                </div>
                            )}
                        </>
                    )}

                    <div
                        ref={rightContentRef}
                        onMouseUp={() => handleMouseUp('right')}
                        className="select-text"
                    >
                        {(() => {
                            if (!data?.right) return null

                            const nodes: JSX.Element[] = []
                            let buffer: typeof data.right = []
                            let groupIndex = 0
                            const rightChapterState: { current: number | null } = { current: null }

                            const flushGroup = () => {
                                if (buffer.length === 0) return
                                const idx = groupIndex++
                                const expanded = expandedGroups.has(idx)

                                nodes.push(
                                    <div key={`grp-${idx}`} className="mb-2">
                                        <button
                                            type="button"
                                            className="btn-secondary text-xs select-none"
                                            onClick={() => toggleGroup(idx)}
                                        >
                                            {expanded
                                                ? `Hide ${buffer.length} unaligned sentence${buffer.length > 1 ? 's' : ''}`
                                                : `Show ${buffer.length} unaligned sentence${buffer.length > 1 ? 's' : ''}`}
                                        </button>
                                        {expanded && (
                                            <div className="mt-1 pl-3 border-l border-amber-200">
                                                {renderSentencesWithChapters(buffer, 'right', undefined, rightChapterState)}
                                            </div>
                                        )}
                                    </div>
                                )

                                buffer = []
                            }

                            data.right.forEach(s => {
                                const isAligned = alignedRightIds.has(s.id)

                                if (isAligned) {
                                    flushGroup()

                                    nodes.push(
                                        <div key={s.id}>
                                            {renderSentencesWithChapters([s], 'right', undefined, rightChapterState)}
                                        </div>
                                    )
                                } else {
                                    buffer.push(s)
                                }
                            })

                            flushGroup()

                            return nodes
                        })()}
                    </div>
                </div>
            </div>

            <div className="page-dock">
                <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                    <div className="flex flex-wrap items-center gap-3">
                        <button className="btn" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page <= 0}>
                            Prev
                        </button>
                        <div className="surface-panel-strong rounded-full px-4 py-2 text-sm font-medium text-slate-900">
                            Page {page + 1}{data?.totalSrc ? ` / ${Math.max(1, Math.ceil(data.totalSrc / size))}` : ''}
                        </div>
                        <button className="btn" onClick={() => setPage(p => p + 1)}>
                            Next
                        </button>
                    </div>

                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
                        <div className="flex flex-wrap items-center gap-2 text-sm">
                            <label className="text-xs uppercase tracking-[0.18em] muted-copy">Jump to</label>
                            <input
                                type="text"
                                inputMode="numeric"
                                pattern="[0-9]*"
                                value={jumpInput}
                                onChange={e => {
                                    setJumpInput(e.target.value)
                                    setJumpError(null)
                                }}
                                className="input w-[4.5rem] text-center"
                                onKeyDown={e => {
                                    if (e.key === 'Enter') {
                                        e.preventDefault()
                                        handleJump()
                                    }
                                }}
                            />
                            <button className="btn-secondary" onClick={handleJump}>
                                Go
                            </button>
                            {data?.totalSrc && (
                                <span className="text-xs muted-copy">
                                    of {Math.max(1, Math.ceil(data.totalSrc / size))}
                                </span>
                            )}
                        </div>

                        <div className="flex flex-wrap items-center gap-2 text-sm">
                            <label className="text-xs uppercase tracking-[0.18em] muted-copy">Sentences / page</label>
                            <select
                                value={size}
                                onChange={e => {
                                    const val = Number(e.target.value)
                                    if (!Number.isFinite(val)) return
                                    const currentOffset = page * size
                                    const newPage = Math.floor(currentOffset / val)
                                    setPage(newPage)
                                    setSize(val)
                                    setJumpInput('1')
                                }}
                                className="input pr-8"
                            >
                                {[5, 10, 15, 20, 30, 40, 50].map(n => (
                                    <option key={n} value={n}>{n}</option>
                                ))}
                            </select>
                            <span className="text-xs muted-copy">Approx. {size} source sentences</span>
                        </div>
                    </div>
                </div>

                <div className="mt-4 flex flex-col gap-2 border-t border-[color:var(--border)] pt-4 text-sm muted-copy md:flex-row md:items-center md:justify-between">
                    <div>
                        Tip: select a word or sentence for a literal translation. On translated text, request an explanation for grammar or nuance.
                    </div>
                    <div className="flex flex-col items-start gap-1 md:items-end">
                        <div>{info}</div>
                        {jumpError && <div className="text-red-700">{jumpError}</div>}
                    </div>
                </div>
            </div>

            {pop.kind !== 'hidden' && (
                <div
                    ref={popRef}
                    className="fixed z-50"
                    style={{ top: `${coords.top}px`, left: `${coords.left}px` }}
                >
                    <div className="popover-surface relative max-w-md">
                        <button
                            aria-label="Close"
                            onClick={() => setPop({ kind: 'hidden' })}
                            className="absolute right-3 top-3 inline-flex h-8 w-8 items-center justify-center rounded-full bg-slate-100 text-slate-500 transition hover:bg-slate-200 hover:text-slate-700"
                            title="Close"
                        >
                            ×
                        </button>

                        <div className="pr-10">
                            <div className="text-xs uppercase tracking-[0.18em] muted-copy">
                                {pop.sourceLang.toUpperCase()} → {pop.targetLang.toUpperCase()}
                            </div>

                            {pop.kind === 'ready' && (
                                <div className="mt-3 flex flex-wrap items-center gap-3">
                                    <div className="max-w-[16rem] text-sm font-semibold text-slate-900">
                                        “{pop.text}”
                                    </div>
                                    <button className="btn" onClick={() => doAction('translate')}>
                                        Translate
                                    </button>
                                </div>
                            )}

                            {pop.kind === 'loading' && (
                                <div className="mt-3 text-sm text-slate-700">
                                    {pop.action === 'explain' ? 'Explaining…' : 'Translating…'}
                                </div>
                            )}

                            {pop.kind === 'result' && (
                                <div className="mt-3 max-h-[50vh] overflow-y-auto pr-1">
                                    <div className="text-xs uppercase tracking-[0.18em] muted-copy">
                                        {pop.action === 'explain' ? 'Explanation' : 'Translation'}
                                    </div>

                                    <div className="mt-2 text-sm font-semibold text-slate-900">“{pop.text}”</div>
                                    <div className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
                                        {pop.result ?? '—'}
                                    </div>

                                    {pop.action !== 'explain' && (
                                        <div className="mt-4 flex items-center gap-2">
                                            <button className="btn-secondary" onClick={() => doAction('explain')}>
                                                Explain
                                            </button>
                                        </div>
                                    )}
                                </div>
                            )}

                            {pop.kind === 'error' && (
                                <div className="mt-3 text-sm text-red-700">Error. Please try again.</div>
                            )}
                        </div>
                    </div>
                </div>
            )}

            {notice && (
                <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50">
                    <div className="surface-panel flex max-w-lg items-start gap-3 rounded-[24px] border border-amber-200 bg-amber-50 px-4 py-3 shadow-lg">
                        <div className="mt-1 text-amber-700">
                            <span className="inline-block h-2 w-2 rounded-full bg-amber-500" />
                        </div>
                        <div className="text-sm text-amber-900">{notice}</div>
                        <button
                            type="button"
                            aria-label="Close"
                            onClick={() => setNotice(null)}
                            className="ml-2 rounded-full px-2 py-1 text-xs text-amber-700 transition hover:bg-amber-100 hover:text-amber-900"
                        >
                            ✕
                        </button>
                    </div>
                </div>
            )}

        </div>
    )
}

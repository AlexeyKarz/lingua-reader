import {useCallback, useEffect, useLayoutEffect, useRef, useState, useMemo, type JSX} from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../authContext'
import { csrfFetch } from '../csrfFetch'
import { resolveReaderPath, LAST_READER_SESSION_KEY, type CatalogEntry } from '../readerNavigation'
import {
    fetchUserPreferences,
    fetchUserReaderProgress,
    saveUserPreference,
    saveUserReaderProgress,
    type SavedReaderState,
} from '../userReaderStateApi'
import { saveUserVocabularyItem } from '../vocabularyApi'
import { saveVocabularyItem, type SaveVocabularyInput } from '../vocabularyStorage'

// type Aligned = { left: string[]; right: string[]; bookId: string; src: string; tgt: string }

type PopAction = 'translate' | 'literary' | 'explain'

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
    errorMessage?: string
    action?: PopAction
    wordDetails?: WordDetails
}

function isSingleWordSelection(text: string) {
    return text.length <= 40 && !/\s/.test(text)
}

type Sentence = { id: number; text: string; speaker?: string | null; chapterId?: number | null; chapterTitle?: string | null }

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
    description?: string | null
    targetTranslator?: string | null
    targetFormat?: string | null
    targetAiGenerated?: boolean
    page: number
    size: number
    totalSrc: number
    totalTgt: number
    left: Sentence[]
    right: Sentence[]
    alignments: AlignmentLink[]
}

type WordDetails = {
    dictionary: boolean
    lemma?: string
    partOfSpeech?: string
    gender?: string
    baseSurface?: string
    baseNormalized?: string
    definitions?: string[]
    examples?: string[]
    translations?: string[]
    entries?: DictionaryEntryDetails[]
    sourceName?: string
    sourceUrl?: string
}

type DictionaryEntryDetails = {
    id: number
    surface: string
    lemma?: string
    partOfSpeech?: string
    gender?: string
    baseSurface?: string
    baseNormalized?: string
    formTags?: string[]
    definitions?: string[]
    examples?: string[]
    translations?: string[]
    sourceName?: string
    sourceUrl?: string
}

type WordTranslationResponse = WordDetails & {
    translated?: string
}

const DEFAULT_PAGE_SIZE = 20
const FOLLOW_ALIGNMENT_KEY = 'mlr:reader-follow-alignment'
const MOBILE_PANE_LAYOUT_KEY = 'mlr:reader-mobile-pane-layout'

type MobilePaneLayout = 'stack' | 'split'

function getReaderProgressKey(bookId: string, src: string, tgt: string) {
    return `mlr:reader-progress:${bookId}:${src}:${tgt}`
}

function readBooleanSetting(key: string, fallback: boolean) {
    try {
        const raw = window.localStorage.getItem(key)
        if (raw === null) return fallback
        return raw === 'true'
    } catch {
        return fallback
    }
}

function readMobilePaneLayout(): MobilePaneLayout {
    try {
        return window.localStorage.getItem(MOBILE_PANE_LAYOUT_KEY) === 'split' ? 'split' : 'stack'
    } catch {
        return 'stack'
    }
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

function writeLastReaderSession(bookId: string, src: string, tgt: string, state: SavedReaderState) {
    try {
        window.localStorage.setItem(
            LAST_READER_SESSION_KEY,
            JSON.stringify({ bookId, src, tgt, ...state })
        )
    } catch {
        // Ignore quota/storage failures; reader should still work without persistence.
    }
}

function writeSavedReaderState(bookId: string, src: string, tgt: string, state: SavedReaderState) {
    try {
        window.localStorage.setItem(getReaderProgressKey(bookId, src, tgt), JSON.stringify(state))
        writeLastReaderSession(bookId, src, tgt, state)
    } catch {
        // Ignore quota/storage failures; reader should still work without persistence.
    }
}

export default function Reader() {
    const auth = useAuth()
    const location = useLocation()
    const navigate = useNavigate()
    const params = useMemo(() => new URLSearchParams(location.search), [location.search])
    const hasReaderParams = params.has('bookId') && params.has('src') && params.has('tgt')

    const bookId = params.get('bookId') ?? ''

    const src = params.get('src') ?? ''
    const tgt = params.get('tgt') ?? ''

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
    const [readerPreferencesReady, setReaderPreferencesReady] = useState(false)
    const [followAlignment, setFollowAlignment] = useState(() => readBooleanSetting(FOLLOW_ALIGNMENT_KEY, false))
    const [mobilePaneLayout, setMobilePaneLayout] = useState<MobilePaneLayout>(() => readMobilePaneLayout())

    const rangeRef = useRef<Range | null>(null)

    const [notice, setNotice] = useState<string | null>(null)

    const [data, setData] = useState<PageData | null>(null)
    const [info, setInfo] = useState('Ready')
    const totalPages = useMemo(
        () => (data?.totalSrc ? Math.max(1, Math.ceil(data.totalSrc / size)) : null),
        [data?.totalSrc, size]
    )
    const isLastPage = totalPages !== null && page >= totalPages - 1
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
    const [expandedDictionaryExamples, setExpandedDictionaryExamples] = useState<Set<string>>(new Set())
    const [showGrammarForms, setShowGrammarForms] = useState(false)
    const [lastSavedLookupKey, setLastSavedLookupKey] = useState<string | null>(null)

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
        if (hasReaderParams) return
        ;(async () => {
            setStatus('loading')
            setInfo('Finding a readable book...')
            try {
                const res = await fetch('/api/catalog/books')
                if (!res.ok) throw new Error('Failed to load catalog')
                const books = (await res.json()) as CatalogEntry[]
                const target = resolveReaderPath(books)
                if (target) {
                    navigate(target, { replace: true })
                    return
                }
                setInfo('No readable books available')
                setErrorMsg('No books with at least two language editions are available yet.')
                setStatus('error')
            } catch (e) {
                setInfo('Error loading catalog')
                setErrorMsg(e instanceof Error ? e.message : 'Failed to load catalog')
                setStatus('error')
            }
        })()
    }, [hasReaderParams, navigate])

    useEffect(() => {
        if (!hasReaderParams) {
            setProgressReady(false)
            return
        }
        if (auth.loading) return
        let alive = true
        setProgressReady(false)

        ;(async () => {
            let saved: SavedReaderState | null = null
            if (auth.authenticated) {
                try {
                    saved = await fetchUserReaderProgress(bookId, src, tgt)
                } catch (caught) {
                    console.warn('Could not load account reader progress', caught)
                    saved = readSavedReaderState(bookId, src, tgt)
                }
            } else {
                saved = readSavedReaderState(bookId, src, tgt)
            }

            if (!alive) return
            const nextPage = saved?.page ?? 0
            const nextSize = saved?.size ?? DEFAULT_PAGE_SIZE

            setPage(nextPage)
            setSize(nextSize)
            setJumpInput(String(nextPage + 1))
            setJumpError(null)
            setProgressReady(true)
        })()

        return () => {
            alive = false
        }
    }, [readerStorageKey, bookId, src, tgt, hasReaderParams, auth.authenticated, auth.loading, auth.user?.id])

    useEffect(() => {
        if (!progressReady || !hasReaderParams) return
        const controller = new AbortController()
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
                const r = await fetch(`/api/reader/pages?${qs}`, { signal: controller.signal })
                if (!r.ok) {
                    const msg = await r.text().catch(() => '')
                    throw new Error(msg || 'Failed to load page')
                }
                const j = (await r.json()) as PageData
                const fetchedTotalPages = Math.max(1, Math.ceil(j.totalSrc / size))
                if (page >= fetchedTotalPages) {
                    const lastPage = fetchedTotalPages - 1
                    setPage(lastPage)
                    setJumpInput(String(lastPage + 1))
                    return
                }
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
                if (e instanceof DOMException && e.name === 'AbortError') return
                console.error(e)
                setData(null)
                setInfo('Error loading page (check language pair and alignments)')
                setErrorMsg(e instanceof Error ? e.message : 'Error loading page')
                setStatus('error')
            }
        })()
        return () => controller.abort()
    }, [bookId, src, tgt, page, size, progressReady, hasReaderParams])

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
        if (!progressReady || !hasReaderParams || auth.loading) return
        const state: SavedReaderState = {
            page,
            size,
            savedAt: Date.now(),
        }
        if (auth.authenticated) {
            writeLastReaderSession(bookId, src, tgt, state)
            void saveUserReaderProgress({
                bookId,
                source: src,
                target: tgt,
                page,
                size,
            }).catch(caught => {
                console.warn('Could not save account reader progress', caught)
            })
            return
        }
        writeSavedReaderState(bookId, src, tgt, state)
    }, [bookId, src, tgt, page, size, progressReady, hasReaderParams, auth.authenticated, auth.loading])

    useEffect(() => {
        if (auth.loading) return
        let alive = true
        setReaderPreferencesReady(false)

        ;(async () => {
            let nextFollowAlignment = readBooleanSetting(FOLLOW_ALIGNMENT_KEY, false)
            let nextMobilePaneLayout = readMobilePaneLayout()
            if (auth.authenticated) {
                try {
                    const preferences = await fetchUserPreferences()
                    if (typeof preferences['reader.followAlignment'] === 'boolean') {
                        nextFollowAlignment = preferences['reader.followAlignment']
                    }
                    if (
                        preferences['reader.mobilePaneLayout'] === 'stack' ||
                        preferences['reader.mobilePaneLayout'] === 'split'
                    ) {
                        nextMobilePaneLayout = preferences['reader.mobilePaneLayout']
                    }
                } catch (caught) {
                    console.warn('Could not load account reader preferences', caught)
                }
            }

            if (!alive) return
            setFollowAlignment(nextFollowAlignment)
            setMobilePaneLayout(nextMobilePaneLayout)
            setReaderPreferencesReady(true)
        })()

        return () => {
            alive = false
        }
    }, [auth.authenticated, auth.loading, auth.user?.id])

    useEffect(() => {
        if (!readerPreferencesReady || auth.loading) return
        if (auth.authenticated) {
            void saveUserPreference('reader.followAlignment', followAlignment)
                .catch(caught => console.warn('Could not save account reader preference', caught))
            return
        }
        try {
            window.localStorage.setItem(FOLLOW_ALIGNMENT_KEY, String(followAlignment))
        } catch {
            // Reader settings are convenience-only for guests.
        }
    }, [followAlignment, readerPreferencesReady, auth.authenticated, auth.loading])

    useEffect(() => {
        if (!readerPreferencesReady || auth.loading) return
        if (auth.authenticated) {
            void saveUserPreference('reader.mobilePaneLayout', mobilePaneLayout)
                .catch(caught => console.warn('Could not save account reader preference', caught))
            return
        }
        try {
            window.localStorage.setItem(MOBILE_PANE_LAYOUT_KEY, mobilePaneLayout)
        } catch {
            // Reader settings are convenience-only for guests.
        }
    }, [mobilePaneLayout, readerPreferencesReady, auth.authenticated, auth.loading])

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

    function hasActiveTextSelection() {
        const selection = window.getSelection()
        return !!selection && !selection.isCollapsed && selection.toString().trim().length > 0
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
        let currentSpeaker: string | null = null

        sentences.forEach(s => {
            const chapChanged = s.chapterId != null && s.chapterId !== state.current
            if (chapChanged) {
                state.current = s.chapterId as number
                if (s.chapterTitle) {
                    nodes.push(
                        <div key={`chap-${side}-${s.chapterId}`} className="chapter-label mt-2 mb-1 text-sm font-semibold">
                            {s.chapterTitle}
                        </div>
                    )
                }
            }

            if (s.speaker && s.speaker !== currentSpeaker) {
                currentSpeaker = s.speaker
                nodes.push(
                    <div key={`speaker-${side}-${s.id}`} className="mt-4 mb-1 text-xs font-semibold uppercase tracking-[0.14em] muted-copy">
                        {s.speaker}
                    </div>
                )
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
                        (side === 'left' ? 'left-sentence cursor-pointer ' : '') +
                        (highlightRightId === s.id && side === 'right' ? 'right-sentence-highlight' : '')
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
        if (hasActiveTextSelection()) return

        const rightId = alignMap.get(sentenceId)
        if (!rightId) {
            setNotice(
                'No aligned publisher translation for this sentence. Check unaligned text (if any) or use AI translation.'
            )
            return
        }

        setHighlightRightId(rightId)
        if (!followAlignment) return

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
        setExpandedDictionaryExamples(new Set())
        setShowGrammarForms(false)
        setLastSavedLookupKey(null)
        setPop({ kind: 'ready', pane: which, sentenceId, text, rect, sourceLang, targetLang })
    }

    async function doAction(action: PopAction) {
        if (pop.kind === 'hidden') return

        const canStart =
            pop.kind === 'ready' ||
            pop.kind === 'error' ||
            (pop.kind === 'result' && pop.action !== action)

        if (!canStart) return

        const { text, sourceLang, targetLang } = pop
        const isWord = isSingleWordSelection(text)

        if (action === 'translate' || action === 'literary') {
            setExpandedDictionaryExamples(new Set())
            setShowGrammarForms(false)
            setLastSavedLookupKey(null)
        }
        setPop({ ...pop, kind: 'loading', action })

        try {
            let resultText = ''
            let wordDetails: WordDetails | undefined
            const parseJson = async (r: Response) => {
                let body: unknown = null
                try {
                    body = await r.json()
                } catch {
                    if (!r.ok) {
                        throw new Error(`Request failed with status ${r.status}`)
                    }
                    throw new Error('Response was not valid JSON')
                }

                if (!r.ok) {
                    const message =
                        typeof body === 'object' &&
                        body !== null &&
                        'message' in body &&
                        typeof (body as { message?: unknown }).message === 'string'
                            ? (body as { message: string }).message
                            : typeof body === 'object' &&
                                body !== null &&
                                'error' in body &&
                                typeof (body as { error?: unknown }).error === 'string'
                                ? (body as { error: string }).error
                                : `Request failed with status ${r.status}`
                    throw new Error(message)
                }

                return body
            }

            const buildContext = () => {
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

                return [
                    `Work: ${data?.title || bookId}`,
                    data?.author ? `Author: ${data.author}` : '',
                    `Selected side: ${pop.pane === 'left' ? 'original' : 'translation'}`,
                    `Reader pair: ${src.toUpperCase()} → ${tgt.toUpperCase()}`,
                    `Current page: ${page + 1}`,
                    alignedContext,
                ].filter(Boolean).join(' | ')
            }

            if (action === 'translate') {
                if (isWord) {
                    const qs = new URLSearchParams({ text, source: sourceLang, target: targetLang })
                    const r = await fetch(`/api/translate/word?${qs}`)
                    const j = (await parseJson(r)) as WordTranslationResponse
                    resultText = j.translated ?? ''
                    if (j.dictionary) {
                        wordDetails = {
                            dictionary: true,
                            lemma: j.lemma,
                            partOfSpeech: j.partOfSpeech,
                            gender: j.gender,
                            baseSurface: j.baseSurface,
                            baseNormalized: j.baseNormalized,
                            definitions: j.definitions ?? [],
                            examples: j.examples ?? [],
                            translations: j.translations ?? [],
                            entries: j.entries ?? [],
                            sourceName: j.sourceName,
                            sourceUrl: j.sourceUrl,
                        }
                    }
                } else {
                    const r = await csrfFetch('/api/translate/sentence', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ text, source: sourceLang, target: targetLang })
                    })
                    const j = (await parseJson(r)) as { translated?: string }
                    resultText = j.translated ?? ''
                }
            } else if (action === 'literary') {
                const r = await csrfFetch('/api/translate/literary', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text,
                        source: sourceLang,
                        target: targetLang,
                        context: buildContext()
                    })
                })
                const j = (await parseJson(r)) as { translated?: string }
                resultText = j.translated ?? ''
            } else {
                const translation = pop.result ?? ''
                const r = await csrfFetch('/api/translate/explain', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text,
                        source: sourceLang,
                        target: targetLang,
                        context: buildContext(),
                        translation
                    })
                })
                const j = (await parseJson(r)) as { explanation?: string }
                resultText = j.explanation ?? ''
            }

            if (!resultText.trim()) {
                throw new Error('Backend returned an empty result')
            }

            setPop(p => (p.kind === 'loading'
                    ? { ...p, kind: 'result', result: resultText, action, wordDetails }
                    : p
            ))
        } catch (error) {
            const message = error instanceof Error ? error.message : 'Request failed'
            setPop(p => (p.kind === 'loading' ? { ...p, kind: 'error', action, errorMessage: message } : p))
        }
    }


    const ensureFrontMatterLoaded = useCallback(async () => {
        if (!hasReaderParams) return
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
    }, [frontMatter, bookId, src, tgt, hasReaderParams])

    // prefetch the front matter
    useEffect(() => {
        if (!hasReaderParams) return
        if (page !== 0) return
        void ensureFrontMatterLoaded()
    }, [page, ensureFrontMatterLoaded, hasReaderParams])

    useEffect(() => {
        rangeRef.current = null
        setPop({ kind: 'hidden' })
        setExpandedDictionaryExamples(new Set())
        setShowGrammarForms(false)
    }, [page, size, bookId, src, tgt])

    async function toggleFrontLeft() {
        await ensureFrontMatterLoaded()
        setShowFrontLeft(v => !v)
    }

    async function toggleFrontRight() {
        await ensureFrontMatterLoaded()
        setShowFrontRight(v => !v)
    }

    async function saveCurrentTranslation() {
        if (pop.kind !== 'result' || pop.action === 'explain' || !pop.result?.trim()) return
        const kind: SaveVocabularyInput['kind'] = pop.wordDetails?.dictionary || (pop.text.length <= 40 && !/\s/.test(pop.text))
            ? 'word'
            : 'sentence'
        const input: SaveVocabularyInput = {
            kind,
            text: pop.text,
            source: pop.sourceLang,
            target: pop.targetLang,
            result: pop.result,
            dictionary: !!pop.wordDetails?.dictionary,
            bookId,
            title: data?.title,
            author: data?.author,
            page,
            sentenceId: pop.sentenceId,
        }
        try {
            const saved = auth.authenticated
                ? await saveUserVocabularyItem(input)
                : saveVocabularyItem(input)
            setLastSavedLookupKey(saved.id)
            setNotice(`${kind === 'word' ? 'Word' : 'Sentence'} saved for review.`)
        } catch (caught) {
            setNotice(caught instanceof Error ? caught.message : 'Could not save for review.')
        }
    }

    function normalizeDictionarySurface(value: string | undefined) {
        return (value ?? '')
            .trim()
            .replace(/^[^\p{L}\p{M}]+|[^\p{L}\p{M}]+$/gu, '')
            .toLocaleLowerCase()
    }

    function looksLikeFormDefinition(definition: string) {
        const text = definition.toLocaleLowerCase()
        return text.includes(' des verbs ') ||
            text.includes(' des substantivs ') ||
            text.includes(' des adjektivs ') ||
            text.includes(' form of ') ||
            text.includes('plural of ') ||
            text.includes('singular of ')
    }

    function isDictionaryFormEntry(entry: DictionaryEntryDetails) {
        const surface = normalizeDictionarySurface(entry.surface)
        const base = normalizeDictionarySurface(entry.baseSurface)
        if (base && base !== surface) return true
        if (entry.formTags?.some(tag => tag.toLocaleLowerCase() === 'form-of')) return true
        if (entry.translations?.length) return false
        const definitions = entry.definitions ?? []
        return definitions.length > 0 && definitions.every(looksLikeFormDefinition)
    }

    function dictionaryEntries(details: WordDetails, selectedText: string): DictionaryEntryDetails[] {
        if (details.entries?.length) return details.entries
        return [{
            id: 0,
            surface: selectedText,
            lemma: details.lemma,
            partOfSpeech: details.partOfSpeech,
            gender: details.gender,
            baseSurface: details.baseSurface,
            baseNormalized: details.baseNormalized,
            definitions: details.definitions,
            examples: details.examples,
            translations: details.translations,
        }]
    }

    function uniqueDictionaryStrings(values: Array<string | undefined>) {
        const seen = new Set<string>()
        const result: string[] = []
        values.forEach(value => {
            const trimmed = value?.trim()
            if (!trimmed) return
            const key = trimmed.toLocaleLowerCase()
            if (seen.has(key)) return
            seen.add(key)
            result.push(trimmed)
        })
        return result
    }

    function dictionaryEntryKey(entry: DictionaryEntryDetails, index: number) {
        return `${entry.id}:${entry.surface}:${entry.partOfSpeech ?? ''}:${entry.baseSurface ?? ''}:${index}`
    }

    function renderDictionarySection(title: string, entries: DictionaryEntryDetails[]) {
        if (!entries.length) return null
        return (
            <div className="dictionary-section space-y-3">
                <div className="dictionary-section-title">{title}</div>
                <div className="space-y-4">
                    {entries.map(renderDictionaryEntry)}
                </div>
            </div>
        )
    }

    function renderDictionaryResult(details: WordDetails, selectedText: string) {
        const selected = normalizeDictionarySurface(selectedText)
        const entries = dictionaryEntries(details, selectedText)
        const main = entries.filter(entry =>
            !isDictionaryFormEntry(entry) &&
            normalizeDictionarySurface(entry.surface) === selected
        )
        const base = entries.filter(entry =>
            !isDictionaryFormEntry(entry) &&
            normalizeDictionarySurface(entry.surface) !== selected
        )
        const forms = entries.filter(isDictionaryFormEntry)
        const summaryTranslations = uniqueDictionaryStrings(
            [...main, ...base].flatMap(entry => entry.translations ?? [])
        )

        return (
            <div className="theme-copy mt-3 space-y-5 text-sm">
                {!!summaryTranslations.length && (
                    <div className="dictionary-summary">
                        <div className="dictionary-section-title">Translations</div>
                        <div className="mt-2 flex flex-wrap gap-2">
                            {summaryTranslations.slice(0, 8).map(translation => (
                                <span key={translation} className="dictionary-chip">
                                    {translation}
                                </span>
                            ))}
                        </div>
                    </div>
                )}

                {renderDictionarySection('Main meanings', main)}
                {renderDictionarySection('Base form', base)}

                {!!forms.length && (
                    <div className="dictionary-section space-y-3">
                        <button
                            type="button"
                            className="btn-secondary text-xs"
                            onClick={() => setShowGrammarForms(value => !value)}
                        >
                            {showGrammarForms
                                ? `Hide grammatical forms (${forms.length})`
                                : `Show grammatical forms (${forms.length})`}
                        </button>
                        {showGrammarForms && (
                            <div className="space-y-4">
                                {forms.map(renderDictionaryEntry)}
                            </div>
                        )}
                    </div>
                )}

                {details.sourceName && (
                    <div className="text-xs muted-copy">
                        Source: {details.sourceUrl ? (
                            <a
                                href={details.sourceUrl}
                                target="_blank"
                                rel="noreferrer"
                                className="underline"
                            >
                                {details.sourceName}
                            </a>
                        ) : details.sourceName}
                    </div>
                )}
            </div>
        )
    }

    function renderDictionaryEntry(entry: DictionaryEntryDetails, index: number) {
        const meta = [
            entry.partOfSpeech,
            entry.gender ? `gender: ${entry.gender}` : '',
            entry.lemma ? `lemma: ${entry.lemma}` : '',
        ].filter(Boolean)
        const formTags = entry.formTags?.filter(Boolean) ?? []
        const key = dictionaryEntryKey(entry, index)
        const examples = entry.examples ?? []
        const examplesExpanded = expandedDictionaryExamples.has(key)

        return (
            <section
                key={key}
                className="dictionary-entry border-t border-[color:var(--border)] pt-3 first:border-t-0 first:pt-0"
            >
                <div className="flex flex-wrap items-baseline gap-2">
                    <div className="theme-heading text-[15px] font-semibold">
                        {entry.surface}
                    </div>
                    {index > 0 && <span className="muted-copy text-xs">variant {index + 1}</span>}
                </div>

                {!!meta.length && (
                    <div className="mt-2 flex flex-wrap gap-2">
                        {meta.map(value => (
                            <span key={value} className="dictionary-meta-chip">
                                {value}
                            </span>
                        ))}
                    </div>
                )}

                {entry.baseSurface && entry.baseSurface.toLowerCase() !== entry.surface.toLowerCase() && (
                    <div className="dictionary-form-note mt-3 text-sm">
                        Form of <span className="font-medium">{entry.baseSurface}</span>
                        {!!formTags.length && (
                            <span className="muted-copy"> ({formTags.join(', ')})</span>
                        )}
                    </div>
                )}

                {!!entry.translations?.length && (
                    <div className="mt-3">
                        <div className="dictionary-section-title">Translations</div>
                        <div className="mt-2 flex flex-wrap gap-2">
                            {entry.translations.map(translation => (
                                <span key={translation} className="dictionary-chip">
                                    {translation}
                                </span>
                            ))}
                        </div>
                    </div>
                )}

                {!!entry.definitions?.length && (
                    <div className="mt-3">
                        <div className="dictionary-section-title">Definitions</div>
                        <ol className="theme-copy mt-2 list-decimal space-y-2 pl-5 text-sm leading-6">
                            {entry.definitions.map((definition, definitionIndex) => (
                                <li key={`${definition}-${definitionIndex}`}>{definition}</li>
                            ))}
                        </ol>
                    </div>
                )}

                {!!examples.length && (
                    <div className="mt-3">
                        <button
                            type="button"
                            className="dictionary-toggle"
                            onClick={() => {
                                setExpandedDictionaryExamples(previous => {
                                    const next = new Set(previous)
                                    if (next.has(key)) next.delete(key)
                                    else next.add(key)
                                    return next
                                })
                            }}
                        >
                            {examplesExpanded ? 'Hide examples' : `Show examples (${examples.length})`}
                        </button>
                        {examplesExpanded && (
                            <ul className="theme-copy mt-2 list-disc space-y-2 pl-5 text-sm leading-6">
                                {examples.map((example, exampleIndex) => (
                                    <li key={`${example}-${exampleIndex}`}>{example}</li>
                                ))}
                            </ul>
                        )}
                    </div>
                )}
            </section>
        )
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

    const paneGridClass = `reader-pane-grid ${
        mobilePaneLayout === 'split' ? 'reader-pane-grid-mobile-split' : 'reader-pane-grid-mobile-stack'
    }`

    return (
        <div>
            <section className="reader-top-panel surface-panel rounded-[24px] px-4 py-4 md:rounded-[28px] md:px-6 md:py-5">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
                    <div className="min-w-0">
                        <div className="mb-2 flex flex-wrap items-center gap-2">
                            <span className="soft-badge">
                                {src && tgt ? `${src.toUpperCase()} → ${tgt.toUpperCase()}` : 'Reader'}
                            </span>
                            <span className="soft-badge">
                                Page {page + 1}{data?.totalSrc ? ` / ${Math.max(1, Math.ceil(data.totalSrc / size))}` : ''}
                            </span>
                            {data?.author && <span className="soft-badge">{data.author}</span>}
                        </div>
                        <h3 className="theme-heading text-2xl font-semibold leading-tight md:text-[2rem]">
                            {data?.title || bookId || 'Reader'}
                        </h3>
                        <p className="mt-2 hidden max-w-3xl text-sm leading-6 muted-copy md:block md:text-[15px]">
                            Compare aligned passages, select words or sentences for literal translation,
                            and open short explanations without leaving the page.
                        </p>
                        {data?.description && (
                            <p className="theme-copy mt-2 hidden max-w-3xl text-sm leading-7 lg:block">
                                {data.description}
                            </p>
                        )}
                    </div>

                    <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                        <label className="reader-toggle-option">
                            <input
                                type="checkbox"
                                checked={followAlignment}
                                onChange={event => setFollowAlignment(event.target.checked)}
                            />
                            <span>Jump to match</span>
                        </label>
                        <div className="reader-segmented" role="group" aria-label="Small-screen pane layout">
                            <button
                                type="button"
                                className={mobilePaneLayout === 'stack' ? 'reader-segmented-active' : ''}
                                aria-pressed={mobilePaneLayout === 'stack'}
                                onClick={() => setMobilePaneLayout('stack')}
                            >
                                Stack
                            </button>
                            <button
                                type="button"
                                className={mobilePaneLayout === 'split' ? 'reader-segmented-active' : ''}
                                aria-pressed={mobilePaneLayout === 'split'}
                                onClick={() => setMobilePaneLayout('split')}
                            >
                                Split
                            </button>
                        </div>
                    </div>
                </div>
            </section>

            {status === 'error' && (
                <div className="theme-danger mt-4 rounded-[24px] border px-4 py-3 text-sm">
                    {errorMsg || 'Could not load this page. Check the language pair or try again.'}
                </div>
            )}
            {isEmptyPair && (
                <div className="theme-warn mt-4 rounded-[24px] border px-4 py-3 text-sm">
                    No aligned data for this book/language pair. Try another pair or ingest alignments.
                </div>
            )}

            <div className={paneGridClass}>
                <div
                    ref={leftRef}
                    className="reader-pane leading-7 md:leading-8"
                >
                    <div className="reader-pane-header">
                        <div className="select-none">
                            <div className="soft-badge">Original ({src.toUpperCase()})</div>
                            <div className="mt-2 hidden text-sm muted-copy sm:block">
                                {followAlignment
                                    ? 'Click highlights and follows the matched translation.'
                                    : 'Click highlights the matched translation.'}
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
                                <div className="frontmatter-block mb-4 rounded-[22px] border bg-[color:var(--surface-tint)] px-4 py-4 text-sm">
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
                    className="reader-pane leading-7 md:leading-8"
                >
                    <div className="reader-pane-header">
                        <div className="select-none">
                            <div className="soft-badge">
                                Translation ({tgt.toUpperCase()}{data?.targetAiGenerated ? ' - AI' : ''})
                            </div>
                            <div className="mt-2 hidden text-sm muted-copy sm:block">
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
                                <div className="frontmatter-block mb-4 rounded-[22px] border bg-[color:var(--surface-tint)] px-4 py-4 text-sm">
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
                                            <div className="left-rule mt-1 border-l pl-3">
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
                        <button className="btn" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={status === 'loading' || page <= 0}>
                            Prev
                        </button>
                        <div className="surface-panel-strong theme-heading rounded-full px-4 py-2 text-sm font-medium">
                            Page {page + 1}{data?.totalSrc ? ` / ${Math.max(1, Math.ceil(data.totalSrc / size))}` : ''}
                        </div>
                        <button className="btn" onClick={() => setPage(p => totalPages === null ? p : Math.min(totalPages - 1, p + 1))} disabled={status === 'loading' || totalPages === null || isLastPage}>
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
                            <button className="btn-secondary" onClick={handleJump} disabled={status === 'loading' || totalPages === null}>
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
                                    setJumpInput(String(newPage + 1))
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
                        {jumpError && <div className="theme-status">{jumpError}</div>}
                    </div>
                </div>
            </div>

            {pop.kind !== 'hidden' && (
                <div
                    ref={popRef}
                    className="fixed z-50"
                    style={{ top: `${coords.top}px`, left: `${coords.left}px` }}
                >
                    <div className="popover-surface relative w-[min(92vw,40rem)]">
                        <button
                            aria-label="Close"
                            onClick={() => setPop({ kind: 'hidden' })}
                            className="popover-close-btn absolute right-3 top-3 inline-flex h-8 w-8 items-center justify-center rounded-full transition"
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
                                    <div className="theme-heading max-w-[16rem] text-sm font-semibold">
                                        “{pop.text}”
                                    </div>
                                    <button className="btn" onClick={() => doAction('translate')}>
                                        {isSingleWordSelection(pop.text) ? 'Translate' : 'Literal'}
                                    </button>
                                    {!isSingleWordSelection(pop.text) && (
                                        <button className="btn-secondary" onClick={() => doAction('literary')}>
                                            Natural
                                        </button>
                                    )}
                                </div>
                            )}

                            {pop.kind === 'loading' && (
                                <div className="theme-copy mt-3 text-sm">
                                    {pop.action === 'explain'
                                        ? 'Explaining…'
                                        : pop.action === 'literary'
                                            ? 'Translating naturally…'
                                            : 'Translating…'}
                                </div>
                            )}

                            {pop.kind === 'result' && (
                                <div className="mt-3 max-h-[50vh] overflow-y-auto pr-1">
                                    <div className="text-xs uppercase tracking-[0.18em] muted-copy">
                                        {pop.action === 'explain'
                                            ? 'Explanation'
                                            : pop.action === 'literary'
                                                ? 'Natural translation'
                                                : pop.wordDetails?.dictionary
                                                    ? 'Dictionary'
                                                    : 'Literal translation'}
                                    </div>

                                    <div className="theme-heading mt-2 text-sm font-semibold">“{pop.text}”</div>
                                    {pop.wordDetails?.dictionary ? (
                                        renderDictionaryResult(pop.wordDetails, pop.text)
                                    ) : (
                                        <div className="theme-copy mt-2 whitespace-pre-wrap text-sm">
                                            {pop.result ?? '—'}
                                        </div>
                                    )}

                                    {pop.action !== 'explain' && (
                                        <div className="mt-4 flex flex-wrap items-center gap-2">
                                            <button
                                                className="btn"
                                                onClick={() => void saveCurrentTranslation()}
                                                disabled={!!lastSavedLookupKey}
                                            >
                                                {lastSavedLookupKey ? 'Saved' : 'Save'}
                                            </button>
                                            {!pop.wordDetails?.dictionary &&
                                                pop.action !== 'literary' &&
                                                !isSingleWordSelection(pop.text) && (
                                                    <button className="btn-secondary" onClick={() => doAction('literary')}>
                                                        Natural
                                                    </button>
                                                )}
                                            <button className="btn-secondary" onClick={() => doAction('explain')}>
                                                Explain
                                            </button>
                                        </div>
                                    )}
                                </div>
                            )}

                            {pop.kind === 'error' && (
                                <div className="theme-status mt-3 text-sm">
                                    {pop.errorMessage || 'Error. Please try again.'}
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            )}

            {notice && (
                <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50">
                    <div className="notice-banner surface-panel flex max-w-lg items-start gap-3 rounded-[24px] border px-4 py-3 shadow-lg">
                        <div className="notice-dot-wrap mt-1">
                            <span className="notice-dot inline-block h-2 w-2 rounded-full" />
                        </div>
                        <div className="notice-text text-sm">{notice}</div>
                        <button
                            type="button"
                            aria-label="Close"
                            onClick={() => setNotice(null)}
                            className="notice-dismiss ml-2 rounded-full px-2 py-1 text-xs transition"
                        >
                            ✕
                        </button>
                    </div>
                </div>
            )}

        </div>
    )
}

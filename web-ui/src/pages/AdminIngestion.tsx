import { type FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { csrfFetch } from '../csrfFetch'
import { type CatalogEntry } from '../readerNavigation'

const DEFAULT_GENERATED_TRANSLATOR = 'AI literary translation'
const DEFAULT_GENERATE_MAX_SOURCE_CHARS = '250000'
const LEGACY_GENERATE_MAX_SOURCE_CHARS = 120000
const DEFAULT_GENERATE_CHUNK_MAX_CHARS = '1800'
const DEFAULT_GENERATE_CONTEXT_CHARS = '600'
const GENERATED_FILE_SPEC_PREFIX = 'ai-literary://'

type WorkDraft = {
    title: string
    author: string
    originalLang: string
    year: string
    description: string
}

type EditionDraft = {
    id: string
    mode: 'upload' | 'generate'
    lang: string
    sourceLang: string
    publisher: string
    translator: string
    file: File | null
    maxSourceChars: string
    chunkMaxChars: string
    contextChars: string
    model: string
    replaceExisting: boolean
    contentType: 'prose' | 'play'
    dictionaryWarmup: boolean
}

type PreparedUploadEdition = Omit<EditionDraft, 'file' | 'mode'> & {
    mode: 'upload'
    lang: string
    file: File
}

type PreparedGeneratedEdition = Omit<EditionDraft, 'file' | 'mode'> & {
    mode: 'generate'
    lang: string
    sourceLang: string
    file: null
}

type PreparedEdition = PreparedUploadEdition | PreparedGeneratedEdition

type StartIngestionResponse = {
    jobId: number
    status: string
    statusUrl: string
}

type LiteraryEstimate = {
    sourceCharacters: number
    sourceWords: number
    sourceParagraphs: number
    translationBatches: number
    estimatedInputTokens: number
    estimatedOutputTokens: number
    estimatedMinutes: number
    withinSourceLimit: boolean
    summary: string
    warning: string
}

type LiteraryModelCatalog = {
    provider: string
    defaultModel?: string | null
    models: string[]
    live: boolean
    warning?: string | null
}

type IngestionJob = {
    id: number
    status: string
    step?: string | null
    message?: string | null
    error?: string | null
    workTitle: string
    lang: string
    fileSpec: string
    year?: number | null
    author?: string | null
    description?: string | null
    publisher?: string | null
    translator?: string | null
    originalLang?: string | null
    contentType?: 'prose' | 'play' | null
    bodyOnly?: boolean | null
    replaceExisting?: boolean | null
    maxSourceChars?: number | null
    chunkMaxChars?: number | null
    contextChars?: number | null
    model?: string | null
    workId?: number | null
    editionId?: number | null
    chapters: number
    paragraphs: number
    sentences: number
    createdAt?: string | null
    startedAt?: string | null
    finishedAt?: string | null
}

type SubmittedJob = {
    localId: string
    editionLang: string
    label: string
    mode: 'upload' | 'generate'
    jobId?: number
    status: string
    step?: string | null
    message?: string | null
    error?: string | null
    workId?: number | null
    editionId?: number | null
    chapters?: number
    paragraphs?: number
    sentences?: number
}

function createId() {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
        return crypto.randomUUID()
    }
    return `draft-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function createEditionDraft(): EditionDraft {
    return {
        id: createId(),
        mode: 'upload',
        lang: '',
        sourceLang: '',
        publisher: '',
        translator: '',
        file: null,
        maxSourceChars: DEFAULT_GENERATE_MAX_SOURCE_CHARS,
        chunkMaxChars: DEFAULT_GENERATE_CHUNK_MAX_CHARS,
        contextChars: DEFAULT_GENERATE_CONTEXT_CHARS,
        model: '',
        replaceExisting: true,
        contentType: 'prose',
        dictionaryWarmup: true,
    }
}

function normalizeLang(value: string) {
    return value.trim().toLowerCase()
}

function isSupportedBookFile(file: File) {
    const lowerName = file.name.toLowerCase()
    return lowerName.endsWith('.epub') || lowerName.endsWith('.txt')
}

function appendIfPresent(form: FormData, key: string, value: string) {
    const trimmed = value.trim()
    if (trimmed) {
        form.append(key, trimmed)
    }
}

function maybePositiveInteger(value: string, field: string) {
    if (!value.trim()) return null
    const parsed = Number(value.trim())
    if (!Number.isInteger(parsed) || parsed < 1) {
        return `${field} must be a whole number greater than zero.`
    }
    return null
}

function maybeNonNegativeInteger(value: string, field: string) {
    if (!value.trim()) return null
    const parsed = Number(value.trim())
    if (!Number.isInteger(parsed) || parsed < 0) {
        return `${field} must be zero or a whole number greater than zero.`
    }
    return null
}

function optionalNumber(value: string) {
    return value.trim() ? Number(value.trim()) : undefined
}

function optionalModelError(value: string, models: string[]) {
    const trimmed = value.trim()
    if (!trimmed) return null
    if (!models.includes(trimmed)) {
        return 'Choose a model verified for this API project or use the server default.'
    }
    return null
}

function isTerminalStatus(status: string) {
    return status === 'SUCCEEDED' || status === 'FAILED'
}

function statusTone(status: string) {
    if (status === 'SUCCEEDED') return 'theme-success'
    if (status === 'FAILED') return 'soft-badge theme-danger'
    return 'soft-badge'
}

function isGeneratedJob(job: IngestionJob) {
    return job.fileSpec.startsWith(GENERATED_FILE_SPEC_PREFIX)
}

function generatedSourceLang(job: IngestionJob) {
    if (job.originalLang?.trim()) return normalizeLang(job.originalLang)
    if (!isGeneratedJob(job)) return ''
    const parts = job.fileSpec.slice(GENERATED_FILE_SPEC_PREFIX.length).split('/')
    return normalizeLang(parts[0] ?? '')
}

function jobMode(job: IngestionJob): 'upload' | 'generate' {
    return isGeneratedJob(job) ? 'generate' : 'upload'
}

function hasLegacyGeneratedMaxSourceChars(job: IngestionJob) {
    return isGeneratedJob(job) && job.maxSourceChars === LEGACY_GENERATE_MAX_SOURCE_CHARS
}

function historyMaxSourceChars(job: IngestionJob) {
    if (hasLegacyGeneratedMaxSourceChars(job)) {
        return DEFAULT_GENERATE_MAX_SOURCE_CHARS
    }
    return job.maxSourceChars == null ? DEFAULT_GENERATE_MAX_SOURCE_CHARS : String(job.maxSourceChars)
}

function canRerunJob(job: IngestionJob) {
    return job.status === 'FAILED' || isGeneratedJob(job)
}

function rerunLabel(job: IngestionJob) {
    if (isGeneratedJob(job) && job.status !== 'FAILED') return 'Regenerate'
    return 'Retry'
}

function fileSpecLabel(job: IngestionJob) {
    if (isGeneratedJob(job)) {
        const sourceLang = generatedSourceLang(job)
        return sourceLang ? `AI from ${sourceLang.toUpperCase()}` : 'AI generated'
    }
    try {
        const decoded = decodeURIComponent(job.fileSpec)
        const lastSlash = decoded.lastIndexOf('/')
        return lastSlash >= 0 ? decoded.slice(lastSlash + 1) : decoded
    } catch {
        return job.fileSpec
    }
}

function formatJobTime(value?: string | null) {
    if (!value) return ''
    return new Date(value).toLocaleString()
}

async function responseError(response: Response) {
    if (response.status === 401) return 'Sign in with an admin account to continue.'
    if (response.status === 403) return 'Your account does not have admin access.'
    if (response.status === 404) return 'Admin ingestion is disabled or this job no longer exists.'

    try {
        const contentType = response.headers.get('content-type') ?? ''
        if (contentType.includes('application/json')) {
            const body = await response.json() as {error?: string; message?: string}
            return body.error ?? body.message ?? `${response.status} ${response.statusText}`
        }
        const text = await response.text()
        return text || `${response.status} ${response.statusText}`
    } catch {
        return `${response.status} ${response.statusText}`
    }
}

export default function AdminIngestionPage() {
    const [catalog, setCatalog] = useState<CatalogEntry[]>([])
    const [selectedWorkId, setSelectedWorkId] = useState('')
    const [work, setWork] = useState<WorkDraft>({
        title: '',
        author: '',
        originalLang: '',
        year: '',
        description: '',
    })
    const [editions, setEditions] = useState<EditionDraft[]>(() => [createEditionDraft()])
    const [jobs, setJobs] = useState<SubmittedJob[]>([])
    const [history, setHistory] = useState<IngestionJob[]>([])
    const [historyLoading, setHistoryLoading] = useState(false)
    const [historyError, setHistoryError] = useState<string | null>(null)
    const [retryingJobId, setRetryingJobId] = useState<number | null>(null)
    const [submitting, setSubmitting] = useState(false)
    const [formError, setFormError] = useState<string | null>(null)
    const [estimates, setEstimates] = useState<Record<string, LiteraryEstimate>>({})
    const [estimatingId, setEstimatingId] = useState<string | null>(null)
    const [ingestedDraftIds, setIngestedDraftIds] = useState<Set<string>>(() => new Set())
    const [modelCatalog, setModelCatalog] = useState<LiteraryModelCatalog | null>(null)
    const [modelsLoading, setModelsLoading] = useState(true)
    const formErrorRef = useRef<HTMLDivElement | null>(null)

    useEffect(() => {
        if (formError) {
            formErrorRef.current?.scrollIntoView?.({behavior: 'smooth', block: 'center'})
        }
    }, [formError])

    useEffect(() => {
        ;(async () => {
            try {
                const res = await fetch('/api/catalog/books')
                if (!res.ok) return
                setCatalog(await res.json() as CatalogEntry[])
            } catch {
                setCatalog([])
            }
        })()
        void loadHistory()
        void loadLiteraryModels()
    }, [])

    async function loadLiteraryModels() {
        setModelsLoading(true)
        try {
            const response = await fetch('/api/admin/ingest/literary-models')
            if (!response.ok) throw new Error(await responseError(response))
            setModelCatalog(await response.json() as LiteraryModelCatalog)
        } catch (error) {
            setModelCatalog({
                provider: 'unknown',
                models: [],
                live: false,
                warning: error instanceof Error ? error.message : 'Could not load available models.',
            })
        } finally {
            setModelsLoading(false)
        }
    }

    const selectedCatalogWork = useMemo(
        () => catalog.find(item => String(item.workId) === selectedWorkId) ?? null,
        [catalog, selectedWorkId]
    )

    const selectedCatalogLangs = useMemo(
        () => new Set((selectedCatalogWork?.langs ?? []).map(normalizeLang)),
        [selectedCatalogWork]
    )

    const languageOptions = useMemo(() => {
        const langs = new Set(['de', 'el', 'en', 'es', 'fr', 'ru'])
        catalog.forEach(book => book.langs.forEach(lang => langs.add(lang)))
        if (work.originalLang.trim()) {
            langs.add(normalizeLang(work.originalLang))
        }
        editions.forEach(edition => {
            if (edition.lang.trim()) langs.add(normalizeLang(edition.lang))
            if (edition.sourceLang.trim()) langs.add(normalizeLang(edition.sourceLang))
        })
        history.forEach(job => {
            if (job.lang.trim()) langs.add(normalizeLang(job.lang))
            if (job.originalLang?.trim()) langs.add(normalizeLang(job.originalLang))
            const sourceLang = generatedSourceLang(job)
            if (sourceLang) langs.add(sourceLang)
        })
        return Array.from(langs).filter(Boolean).sort()
    }, [catalog, editions, history, work.originalLang])

    const originalEditionMissing = work.originalLang.trim() &&
        !selectedCatalogLangs.has(normalizeLang(work.originalLang)) &&
        !editions.some(edition =>
            edition.mode === 'upload' && normalizeLang(edition.lang) === normalizeLang(work.originalLang)
        )

    function applyCatalogWork(workId: string) {
        setIngestedDraftIds(new Set())
        setEstimates({})
        setSelectedWorkId(workId)
        const book = catalog.find(item => String(item.workId) === workId)
        if (!book) return

        setWork({
            title: book.title,
            author: book.author ?? '',
            originalLang: book.originalLang ?? '',
            year: book.year == null ? '' : String(book.year),
            description: book.description ?? '',
        })
    }

    function updateEdition(id: string, patch: Partial<EditionDraft>) {
        setEditions(current => current.map(edition => edition.id === id ? {...edition, ...patch} : edition))
        setIngestedDraftIds(current => {
            if (!current.has(id)) return current
            const next = new Set(current)
            next.delete(id)
            return next
        })
        setEstimates(current => {
            if (!(id in current)) return current
            const next = {...current}
            delete next[id]
            return next
        })
    }

    function addEdition() {
        setEditions(current => [...current, createEditionDraft()])
    }

    function removeEdition(id: string) {
        setEditions(current => current.length === 1 ? current : current.filter(edition => edition.id !== id))
        setIngestedDraftIds(current => {
            const next = new Set(current)
            next.delete(id)
            return next
        })
    }

    function patchJob(localId: string, patch: Partial<SubmittedJob>) {
        setJobs(current => current.map(job => job.localId === localId ? {...job, ...patch} : job))
    }

    function applyJobMetadata(job: IngestionJob) {
        const mode = jobMode(job)
        const sourceLang = generatedSourceLang(job)
        const catalogWork = catalog.find(item =>
            (job.workId != null && item.workId === job.workId) || item.title === job.workTitle
        ) ?? null
        setSelectedWorkId(catalogWork ? String(catalogWork.workId) : (job.workId == null ? '' : String(job.workId)))
        setWork({
            title: catalogWork?.title ?? job.workTitle,
            author: job.author || catalogWork?.author || '',
            originalLang: job.originalLang || catalogWork?.originalLang || sourceLang || (mode === 'upload' ? job.lang : ''),
            year: job.year == null ? (catalogWork?.year == null ? '' : String(catalogWork.year)) : String(job.year),
            description: job.description || catalogWork?.description || '',
        })
        setEditions([{
            ...createEditionDraft(),
            mode,
            lang: normalizeLang(job.lang),
            sourceLang,
            publisher: job.publisher ?? '',
            translator: job.translator ?? (mode === 'generate' ? DEFAULT_GENERATED_TRANSLATOR : ''),
            file: null,
            maxSourceChars: historyMaxSourceChars(job),
            chunkMaxChars: job.chunkMaxChars == null ? DEFAULT_GENERATE_CHUNK_MAX_CHARS : String(job.chunkMaxChars),
            contextChars: job.contextChars == null ? DEFAULT_GENERATE_CONTEXT_CHARS : String(job.contextChars),
            model: job.model && modelCatalog?.models.includes(job.model) ? job.model : '',
            replaceExisting: job.replaceExisting ?? true,
            contentType: job.contentType === 'play' ? 'play' : 'prose',
            dictionaryWarmup: true,
        }])
        setIngestedDraftIds(new Set())
        setEstimates({})
        setFormError(null)
    }

    async function loadHistory() {
        setHistoryLoading(true)
        setHistoryError(null)
        try {
            const response = await fetch('/api/admin/ingest/jobs?limit=50')
            if (!response.ok) {
                throw new Error(await responseError(response))
            }
            setHistory(await response.json() as IngestionJob[])
        } catch (error) {
            setHistoryError(error instanceof Error ? error.message : 'Could not load ingestion history.')
        } finally {
            setHistoryLoading(false)
        }
    }

    async function rerunHistoryJob(job: IngestionJob) {
        setRetryingJobId(job.id)
        setHistoryError(null)
        try {
            const response = await csrfFetch(`/api/admin/ingest/jobs/${job.id}/retry`, {
                method: 'POST',
            })
            if (!response.ok) {
                throw new Error(await responseError(response))
            }
            const started = await response.json() as StartIngestionResponse
            const localId = createId()
            setJobs(current => [{
                localId,
                editionLang: job.lang,
                label: fileSpecLabel(job),
                mode: jobMode(job),
                jobId: started.jobId,
                status: started.status,
                message: 'Retry queued',
            }, ...current])
            await loadHistory()
            void pollJob(localId, started.jobId).then(() => loadHistory())
        } catch (error) {
            setHistoryError(error instanceof Error ? error.message : 'Could not rerun ingestion job.')
        } finally {
            setRetryingJobId(null)
        }
    }

    function validateForm(): string | null {
        if (!work.title.trim()) return 'Work title is required.'
        if (!work.originalLang.trim()) return 'Original language is required.'
        if (work.year.trim() && !Number.isInteger(Number(work.year.trim()))) {
            return 'Publication year must be a whole number.'
        }

        const preparedLangs = editions.map(edition => normalizeLang(edition.lang)).filter(Boolean)
        if (new Set(preparedLangs).size !== preparedLangs.length) {
            const duplicateLang = preparedLangs.find((lang, index) => preparedLangs.indexOf(lang) !== index) ?? ''
            const duplicateCards = editions
                .map((edition, index) => ({lang: normalizeLang(edition.lang), number: index + 1}))
                .filter(edition => edition.lang === duplicateLang)
                .map(edition => edition.number)
            return `${duplicateLang.toUpperCase()} is selected as the edition language on Editions ${duplicateCards.join(' and ')}. `
                + 'Each card needs a different edition language; AI cards may all use the same source language.'
        }

        const submittedUploadLangs = new Set(
            editions
                .filter(edition => edition.mode === 'upload')
                .map(edition => normalizeLang(edition.lang))
                .filter(Boolean)
        )

        for (const edition of editions) {
            if (!normalizeLang(edition.lang)) return 'Every edition needs a language.'
            if (edition.mode === 'upload') {
                if (!edition.file) return 'Every uploaded edition needs an EPUB or TXT file.'
                if (!isSupportedBookFile(edition.file)) {
                    return 'Only EPUB and TXT files are supported.'
                }
            } else {
                const sourceLang = normalizeLang(edition.sourceLang) || normalizeLang(work.originalLang)
                const targetLang = normalizeLang(edition.lang)
                if (!sourceLang) return 'Every AI-generated edition needs a source language.'
                if (sourceLang === targetLang) return 'AI-generated source and target languages must differ.'
                if (!selectedCatalogLangs.has(sourceLang) && !submittedUploadLangs.has(sourceLang)) {
                    return `AI-generated ${targetLang.toUpperCase()} needs an uploaded or existing ${sourceLang.toUpperCase()} source edition.`
                }
                const maxSourceError = maybePositiveInteger(edition.maxSourceChars, 'Maximum source characters')
                if (maxSourceError) return maxSourceError
                const chunkError = maybePositiveInteger(edition.chunkMaxChars, 'Chunk size')
                if (chunkError) return chunkError
                const contextError = maybeNonNegativeInteger(edition.contextChars, 'Context characters')
                if (contextError) return contextError
                const modelError = optionalModelError(edition.model, modelCatalog?.models ?? [])
                if (modelError) return modelError
            }
        }

        return null
    }

    async function submitEdition(edition: PreparedUploadEdition) {
        const form = new FormData()
        form.append('workTitle', work.title.trim())
        form.append('lang', edition.lang)
        form.append('file', edition.file)
        appendIfPresent(form, 'year', work.year)
        appendIfPresent(form, 'publisher', edition.publisher)
        appendIfPresent(form, 'translator', edition.translator)
        appendIfPresent(form, 'author', work.author)
        appendIfPresent(form, 'description', work.description)
        appendIfPresent(form, 'originalLang', work.originalLang)
        form.append('contentType', edition.contentType)
        const dictionaryTargets = editions
            .map(candidate => normalizeLang(candidate.lang))
            .filter(lang => lang && lang !== edition.lang)
            .filter((lang, index, values) => values.indexOf(lang) === index)
        if (edition.dictionaryWarmup && dictionaryTargets.length > 0) {
            form.append('dictionaryWarmup', 'true')
            dictionaryTargets.forEach(lang => form.append('dictionaryTargetLangs', lang))
        }

        const response = await csrfFetch('/api/admin/ingest/jobs/upload', {
            method: 'POST',
            body: form,
        })

        if (!response.ok) {
            throw new Error(await responseError(response))
        }

        return await response.json() as StartIngestionResponse
    }

    async function submitGeneratedEdition(edition: PreparedGeneratedEdition) {
        const payload: Record<string, unknown> = {
            workTitle: work.title.trim(),
            sourceLang: edition.sourceLang,
            targetLang: edition.lang,
            bodyOnly: true,
            replaceExisting: edition.replaceExisting,
        }
        if (work.year.trim()) payload.year = Number(work.year.trim())
        if (edition.publisher.trim()) payload.publisher = edition.publisher.trim()
        payload.translator = edition.translator.trim() || DEFAULT_GENERATED_TRANSLATOR
        const maxSourceChars = optionalNumber(edition.maxSourceChars)
        const chunkMaxChars = optionalNumber(edition.chunkMaxChars)
        const contextChars = optionalNumber(edition.contextChars)
        if (maxSourceChars !== undefined) payload.maxSourceChars = maxSourceChars
        if (chunkMaxChars !== undefined) payload.chunkMaxChars = chunkMaxChars
        if (contextChars !== undefined) payload.contextChars = contextChars
        if (edition.model.trim()) payload.model = edition.model.trim()

        const response = await csrfFetch('/api/admin/ingest/jobs/generate-literary', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(payload),
        })

        if (!response.ok) {
            throw new Error(await responseError(response))
        }

        return await response.json() as StartIngestionResponse
    }

    async function estimateGeneratedEdition(edition: EditionDraft) {
        const sourceLang = normalizeLang(edition.sourceLang) || normalizeLang(work.originalLang)
        if (!work.title.trim() || !sourceLang || !normalizeLang(edition.lang)) {
            setFormError('Title, source language, and target language are required for an estimate.')
            return
        }
        const modelError = optionalModelError(edition.model, modelCatalog?.models ?? [])
        if (modelError) {
            setFormError(modelError)
            return
        }
        setEstimatingId(edition.id)
        setFormError(null)
        try {
            const draftSource = editions.find(candidate =>
                candidate.mode === 'upload' && normalizeLang(candidate.lang) === sourceLang
            )
            if (!selectedCatalogLangs.has(sourceLang) && draftSource && !ingestedDraftIds.has(draftSource.id)) {
                if (!draftSource.file || !isSupportedBookFile(draftSource.file)) {
                    throw new Error(`Add a valid EPUB or TXT file for the ${sourceLang.toUpperCase()} source before estimating.`)
                }
                const localId = createId()
                setJobs(current => [{
                    localId,
                    editionLang: sourceLang,
                    label: draftSource.file?.name ?? `${sourceLang.toUpperCase()} source`,
                    mode: 'upload',
                    status: 'UPLOADING',
                    message: 'Ingesting source before estimate',
                }, ...current])
                const started = await submitEdition({
                    ...draftSource,
                    mode: 'upload',
                    lang: sourceLang,
                    file: draftSource.file,
                })
                patchJob(localId, {jobId: started.jobId, status: started.status, message: 'Source ingestion queued'})
                const terminalJob = await pollJob(localId, started.jobId)
                if (terminalJob?.status !== 'SUCCEEDED') {
                    throw new Error(`Could not estimate because ${sourceLang.toUpperCase()} source ingestion failed.`)
                }
                setIngestedDraftIds(current => new Set(current).add(draftSource.id))
            }

            const payload = {
                workTitle: work.title.trim(),
                sourceLang,
                targetLang: normalizeLang(edition.lang),
                bodyOnly: true,
                maxSourceChars: optionalNumber(edition.maxSourceChars),
                chunkMaxChars: optionalNumber(edition.chunkMaxChars),
                contextChars: optionalNumber(edition.contextChars),
                model: edition.model.trim() || undefined,
            }
            const response = await csrfFetch('/api/admin/ingest/jobs/generate-literary/estimate', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload),
            })
            if (!response.ok) throw new Error(await responseError(response))
            const estimate = await response.json() as LiteraryEstimate
            setEstimates(current => ({...current, [edition.id]: estimate}))
        } catch (error) {
            setFormError(error instanceof Error ? error.message : 'Could not estimate this edition.')
        } finally {
            setEstimatingId(null)
        }
    }

    async function refreshJob(jobId: number) {
        const response = await fetch(`/api/admin/ingest/jobs/${jobId}`)

        if (!response.ok) {
            throw new Error(await responseError(response))
        }

        return await response.json() as IngestionJob
    }

    async function pollJob(localId: string, jobId: number) {
        for (let attempt = 0; attempt < 240; attempt += 1) {
            await new Promise(resolve => window.setTimeout(resolve, attempt === 0 ? 600 : 2000))
            try {
                const job = await refreshJob(jobId)
                patchJob(localId, {
                    status: job.status,
                    step: job.step,
                    message: job.message,
                    error: job.error,
                    workId: job.workId,
                    editionId: job.editionId,
                    chapters: job.chapters,
                    paragraphs: job.paragraphs,
                    sentences: job.sentences,
                })
                if (isTerminalStatus(job.status)) {
                    void loadHistory()
                    return job
                }
            } catch (error) {
                patchJob(localId, {
                    status: 'FAILED',
                    error: error instanceof Error ? error.message : 'Could not refresh job status.',
                })
                return null
            }
        }

        patchJob(localId, {
            message: 'Status polling timed out. The job may still be running on the server.',
        })
        return null
    }

    async function submitAll(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        setFormError(null)

        const validationError = validateForm()
        if (validationError) {
            setFormError(validationError)
            return
        }

        const preparedEditions: PreparedEdition[] = editions.map(edition => {
            if (edition.mode === 'upload') {
                return {
                    ...edition,
                    mode: 'upload',
                    lang: normalizeLang(edition.lang),
                    file: edition.file as File,
                }
            }
            return {
                ...edition,
                mode: 'generate',
                lang: normalizeLang(edition.lang),
                sourceLang: normalizeLang(edition.sourceLang) || normalizeLang(work.originalLang),
                file: null,
            }
        })
        const uploadEditions = preparedEditions.filter(
            (edition): edition is PreparedUploadEdition => edition.mode === 'upload' && !ingestedDraftIds.has(edition.id)
        )
        const generatedEditions = preparedEditions.filter(
            (edition): edition is PreparedGeneratedEdition => edition.mode === 'generate'
        )
        const requiredUploadSources = new Set(generatedEditions.map(edition => edition.sourceLang))
        const failedUploadLangs = new Set<string>()

        setSubmitting(true)
        try {
            for (const edition of uploadEditions) {
                const localId = createId()
                setJobs(current => [{
                    localId,
                    editionLang: edition.lang,
                    label: edition.file.name,
                    mode: 'upload',
                    status: 'UPLOADING',
                    message: 'Uploading file',
                }, ...current])

                try {
                    const started = await submitEdition(edition)
                    patchJob(localId, {
                        jobId: started.jobId,
                        status: started.status,
                        message: 'Job queued',
                    })
                    if (requiredUploadSources.has(edition.lang)) {
                        const terminalJob = await pollJob(localId, started.jobId)
                        if (terminalJob?.status !== 'SUCCEEDED') {
                            failedUploadLangs.add(edition.lang)
                        }
                    } else {
                        void pollJob(localId, started.jobId)
                    }
                } catch (error) {
                    failedUploadLangs.add(edition.lang)
                    patchJob(localId, {
                        status: 'FAILED',
                        error: error instanceof Error ? error.message : 'Could not submit ingestion job.',
                    })
                }
            }

            for (const edition of generatedEditions) {
                const localId = createId()
                const label = `AI from ${edition.sourceLang.toUpperCase()}`
                setJobs(current => [{
                    localId,
                    editionLang: edition.lang,
                    label,
                    mode: 'generate',
                    status: 'QUEUING',
                    message: 'Preparing generated edition',
                }, ...current])

                if (failedUploadLangs.has(edition.sourceLang)) {
                    patchJob(localId, {
                        status: 'FAILED',
                        error: `Skipped because ${edition.sourceLang.toUpperCase()} source ingestion failed or timed out.`,
                    })
                    continue
                }

                try {
                    const started = await submitGeneratedEdition(edition)
                    patchJob(localId, {
                        jobId: started.jobId,
                        status: started.status,
                        message: 'Generation job queued',
                    })
                    void pollJob(localId, started.jobId)
                } catch (error) {
                    patchJob(localId, {
                        status: 'FAILED',
                        error: error instanceof Error ? error.message : 'Could not submit generation job.',
                    })
                }
            }
        } finally {
            setSubmitting(false)
            void loadHistory()
        }
    }

    return (
        <div className="space-y-5">
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
                    <div>
                        <div className="soft-badge mb-2">Admin only</div>
                        <h2 className="theme-heading text-2xl font-semibold md:text-[1.85rem]">
                            Book ingestion
                        </h2>
                    </div>
                    <button
                        type="button"
                        className="btn-secondary w-fit"
                        onClick={() => void loadHistory()}
                        disabled={historyLoading}
                    >
                        {historyLoading ? 'Loading...' : 'Load history'}
                    </button>
                </div>
            </section>

            <form className="space-y-5" onSubmit={submitAll} noValidate>
                <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                    <div className="flex flex-col gap-4">
                        <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
                            <div>
                                <div className="soft-badge mb-2">Work</div>
                                <h3 className="theme-heading text-xl font-semibold">Book details</h3>
                            </div>
                            <label className="flex flex-col gap-2 text-sm md:min-w-56">
                                <span className="text-xs uppercase tracking-[0.18em] muted-copy">
                                    Existing work
                                </span>
                                <select
                                    value={selectedWorkId}
                                    onChange={event => applyCatalogWork(event.target.value)}
                                    className="input"
                                >
                                    <option value="">New work</option>
                                    {catalog.map(book => (
                                        <option key={book.workId} value={book.workId}>
                                            {book.title}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        </div>

                        <div className="grid gap-3 md:grid-cols-2">
                            <label className="flex flex-col gap-2 text-sm">
                                <span className="text-xs uppercase tracking-[0.18em] muted-copy">Title</span>
                                <input
                                    value={work.title}
                                    onChange={event => {
                                        setWork(current => ({...current, title: event.target.value}))
                                        setIngestedDraftIds(new Set())
                                        setEstimates({})
                                    }}
                                    className="input"
                                    required
                                />
                            </label>
                            <label className="flex flex-col gap-2 text-sm">
                                <span className="text-xs uppercase tracking-[0.18em] muted-copy">Author</span>
                                <input
                                    value={work.author}
                                    onChange={event => setWork(current => ({...current, author: event.target.value}))}
                                    className="input"
                                />
                            </label>
                            <label className="flex flex-col gap-2 text-sm">
                                <span className="text-xs uppercase tracking-[0.18em] muted-copy">Original language</span>
                                <input
                                    list="admin-language-options"
                                    value={work.originalLang}
                                    onChange={event => {
                                        setWork(current => ({...current, originalLang: normalizeLang(event.target.value)}))
                                        setIngestedDraftIds(new Set())
                                        setEstimates({})
                                    }}
                                    className="input"
                                    required
                                    placeholder="de"
                                />
                            </label>
                            <label className="flex flex-col gap-2 text-sm">
                                <span className="text-xs uppercase tracking-[0.18em] muted-copy">Publication year</span>
                                <input
                                    type="number"
                                    value={work.year}
                                    onChange={event => setWork(current => ({...current, year: event.target.value}))}
                                    className="input"
                                    inputMode="numeric"
                                />
                            </label>
                        </div>

                        <label className="flex flex-col gap-2 text-sm">
                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">Description</span>
                            <textarea
                                value={work.description}
                                onChange={event => setWork(current => ({...current, description: event.target.value}))}
                                className="textarea-input min-h-28"
                            />
                        </label>
                    </div>
                </section>

                <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                        <div>
                            <div className="soft-badge mb-2">Editions</div>
                            <h3 className="theme-heading text-xl font-semibold">Files and metadata</h3>
                        </div>
                        <button type="button" className="btn-secondary w-fit" onClick={addEdition}>
                            Add edition
                        </button>
                    </div>

                    {originalEditionMissing && (
                        <div className="theme-warn mt-4 rounded-[20px] border px-4 py-3 text-sm">
                            No uploaded or selected existing edition matches the original language.
                        </div>
                    )}

                    <div className="mt-4 space-y-3">
                        {editions.map((edition, index) => (
                            <article key={edition.id} className="vocab-list-item">
                                <div className="min-w-0 flex-1 space-y-3">
                                    <div className="flex items-center justify-between gap-3">
                                        <div className="flex flex-wrap items-center gap-3">
                                            <div className="theme-heading font-medium">Edition {index + 1}</div>
                                            <div className="flex rounded-[12px] border border-[color:var(--border)] p-1">
                                                <button
                                                    type="button"
                                                    className={edition.mode === 'upload' ? 'btn px-3 py-1.5 text-xs' : 'btn-secondary px-3 py-1.5 text-xs'}
                                                    onClick={() => updateEdition(edition.id, {mode: 'upload'})}
                                                >
                                                    Upload
                                                </button>
                                                <button
                                                    type="button"
                                                    className={edition.mode === 'generate' ? 'btn px-3 py-1.5 text-xs' : 'btn-secondary px-3 py-1.5 text-xs'}
                                                    onClick={() => updateEdition(edition.id, {
                                                        mode: 'generate',
                                                        file: null,
                                                        sourceLang: edition.sourceLang || normalizeLang(work.originalLang),
                                                        translator: edition.translator || DEFAULT_GENERATED_TRANSLATOR,
                                                    })}
                                                >
                                                    AI
                                                </button>
                                            </div>
                                        </div>
                                        {editions.length > 1 && (
                                            <button
                                                type="button"
                                                className="btn-secondary text-xs"
                                                onClick={() => removeEdition(edition.id)}
                                            >
                                                Remove
                                            </button>
                                        )}
                                    </div>
                                    <div className="grid gap-3 md:grid-cols-3">
                                        <label className="flex flex-col gap-2 text-sm">
                                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">
                                                {edition.mode === 'generate' ? 'Target edition language' : 'Language'}
                                            </span>
                                            <input
                                                list="admin-language-options"
                                                value={edition.lang}
                                                onChange={event => updateEdition(edition.id, {lang: normalizeLang(event.target.value)})}
                                                className="input"
                                                required
                                                placeholder="en"
                                            />
                                        </label>
                                        <label className="flex flex-col gap-2 text-sm">
                                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">Publisher</span>
                                            <input
                                                value={edition.publisher}
                                                onChange={event => updateEdition(edition.id, {publisher: event.target.value})}
                                                className="input"
                                            />
                                        </label>
                                        <label className="flex flex-col gap-2 text-sm">
                                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">Translator</span>
                                            <input
                                                value={edition.translator}
                                                onChange={event => updateEdition(edition.id, {translator: event.target.value})}
                                                className="input"
                                            />
                                        </label>
                                    </div>
                                    {edition.mode === 'upload' ? (
                                        <div className="space-y-3">
                                            <label className="flex flex-col gap-2 text-sm">
                                                <span className="text-xs uppercase tracking-[0.18em] muted-copy">EPUB or TXT file</span>
                                                <input
                                                    type="file"
                                                    accept=".epub,.txt,application/epub+zip,text/plain"
                                                    onChange={event => updateEdition(edition.id, {file: event.target.files?.[0] ?? null})}
                                                    className="file-input"
                                                    required
                                                />
                                            </label>
                                            <label className="flex items-start gap-3 text-sm">
                                                <input
                                                    type="checkbox"
                                                    checked={edition.contentType === 'play'}
                                                    onChange={event => updateEdition(edition.id, {
                                                        contentType: event.target.checked ? 'play' : 'prose',
                                                    })}
                                                    className="mt-1"
                                                />
                                                <span>
                                                    <span className="theme-heading block font-medium">Play or verse dialogue</span>
                                                    <span className="muted-copy">Preserve line breaks and treat uppercase character cues as speakers.</span>
                                                </span>
                                            </label>
                                            <label className="flex items-start gap-3 text-sm">
                                                <input
                                                    type="checkbox"
                                                    checked={edition.dictionaryWarmup}
                                                    onChange={event => updateEdition(edition.id, {
                                                        dictionaryWarmup: event.target.checked,
                                                    })}
                                                    className="mt-1"
                                                />
                                                <span>
                                                    <span className="theme-heading block font-medium">Import dictionary entries</span>
                                                    <span className="muted-copy">After ingestion, import words used in this edition for the other selected languages.</span>
                                                </span>
                                            </label>
                                        </div>
                                    ) : (
                                        <div className="space-y-3">
                                            <div className="grid gap-3 md:grid-cols-2">
                                                <label className="flex flex-col gap-2 text-sm">
                                                    <span className="text-xs uppercase tracking-[0.18em] muted-copy">Source language</span>
                                                    <input
                                                        list="admin-language-options"
                                                        value={edition.sourceLang}
                                                        onChange={event => updateEdition(edition.id, {sourceLang: normalizeLang(event.target.value)})}
                                                        className="input"
                                                        placeholder={normalizeLang(work.originalLang) || 'de'}
                                                    />
                                                </label>
                                                <label className="flex flex-col gap-2 text-sm">
                                                    <span className="text-xs uppercase tracking-[0.18em] muted-copy">Model</span>
                                                    <select
                                                        value={edition.model}
                                                        onChange={event => updateEdition(edition.id, {model: event.target.value})}
                                                        className="input"
                                                        disabled={modelsLoading}
                                                    >
                                                        <option value="">
                                                            {modelsLoading
                                                                ? 'Loading models…'
                                                                : `Server default${modelCatalog?.defaultModel ? ` (${modelCatalog.defaultModel})` : ''}`}
                                                        </option>
                                                        {(modelCatalog?.models ?? []).map(model => (
                                                            <option key={model} value={model}>{model}</option>
                                                        ))}
                                                    </select>
                                                    {modelCatalog?.warning && (
                                                        <span className="text-xs theme-danger">{modelCatalog.warning}</span>
                                                    )}
                                                </label>
                                            </div>
                                            <div className="flex flex-wrap items-center gap-3">
                                                <button
                                                    type="button"
                                                    className="btn-secondary"
                                                    disabled={estimatingId === edition.id}
                                                    onClick={() => void estimateGeneratedEdition(edition)}
                                                >
                                                    {estimatingId === edition.id ? 'Preparing estimate…' : 'Estimate size and time'}
                                                </button>
                                                <span className="text-xs muted-copy">A new source file is ingested first; no AI call is made.</span>
                                            </div>
                                            {estimates[edition.id] && (
                                                <div className={`rounded-[20px] border px-4 py-3 text-sm ${estimates[edition.id].withinSourceLimit ? 'surface-panel-strong' : 'theme-danger'}`}>
                                                    <div className="theme-heading font-medium">{estimates[edition.id].summary}</div>
                                                    <div className="mt-1 muted-copy">
                                                        {estimates[edition.id].sourceParagraphs.toLocaleString()} source passages · approximately {estimates[edition.id].estimatedOutputTokens.toLocaleString()} output tokens
                                                    </div>
                                                    <div className="mt-2 text-xs muted-copy">{estimates[edition.id].warning}</div>
                                                </div>
                                            )}
                                            <details className="rounded-[18px] border border-[color:var(--border)] px-3 py-2">
                                                <summary className="cursor-pointer text-sm font-medium muted-copy">Advanced generation limits</summary>
                                                <div className="mt-3 grid gap-3 md:grid-cols-3">
                                                    <label className="flex flex-col gap-2 text-sm">
                                                        <span className="text-xs uppercase tracking-[0.18em] muted-copy">Maximum source characters</span>
                                                        <input type="number" min="1" value={edition.maxSourceChars} onChange={event => updateEdition(edition.id, {maxSourceChars: event.target.value})} className="input" inputMode="numeric" />
                                                    </label>
                                                    <label className="flex flex-col gap-2 text-sm">
                                                        <span className="text-xs uppercase tracking-[0.18em] muted-copy">Characters per AI request</span>
                                                        <input type="number" min="1" value={edition.chunkMaxChars} onChange={event => updateEdition(edition.id, {chunkMaxChars: event.target.value})} className="input" inputMode="numeric" />
                                                    </label>
                                                    <label className="flex flex-col gap-2 text-sm">
                                                        <span className="text-xs uppercase tracking-[0.18em] muted-copy">Previous-text context</span>
                                                        <input type="number" min="0" value={edition.contextChars} onChange={event => updateEdition(edition.id, {contextChars: event.target.value})} className="input" inputMode="numeric" />
                                                    </label>
                                                </div>
                                            </details>
                                            <label className="flex w-fit items-center gap-2 text-sm muted-copy">
                                                <input
                                                    type="checkbox"
                                                    checked={edition.replaceExisting}
                                                    onChange={event => updateEdition(edition.id, {replaceExisting: event.target.checked})}
                                                />
                                                Replace existing generated edition
                                            </label>
                                        </div>
                                    )}
                                </div>
                            </article>
                        ))}
                    </div>

                    {formError && (
                        <div
                            ref={formErrorRef}
                            role="alert"
                            aria-live="assertive"
                            className="theme-danger mt-4 rounded-[20px] border px-4 py-3 text-sm"
                        >
                            {formError}
                        </div>
                    )}

                    <div className="mt-5 flex flex-wrap items-center gap-3">
                        <button type="submit" className="btn" disabled={submitting}>
                            {submitting ? 'Submitting...' : 'Start ingestion'}
                        </button>
                        <button
                            type="button"
                            className="btn-secondary"
                            onClick={() => {
                                setSelectedWorkId('')
                                setWork({title: '', author: '', originalLang: '', year: '', description: ''})
                                setEditions([createEditionDraft()])
                                setIngestedDraftIds(new Set())
                                setEstimates({})
                                setFormError(null)
                            }}
                            disabled={submitting}
                        >
                            Reset form
                        </button>
                    </div>
                </section>
            </form>

            <datalist id="admin-language-options">
                {languageOptions.map(lang => (
                    <option key={lang} value={lang} />
                ))}
            </datalist>
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
                    <div>
                        <div className="soft-badge mb-2">History</div>
                        <h3 className="theme-heading text-xl font-semibold">Recent ingestion jobs</h3>
                    </div>
                    <button
                        type="button"
                        className="btn-secondary w-fit"
                        onClick={() => void loadHistory()}
                        disabled={historyLoading}
                    >
                        {historyLoading ? 'Refreshing...' : 'Refresh'}
                    </button>
                </div>

                {historyError && (
                    <div className="theme-danger mt-4 rounded-[20px] border px-4 py-3 text-sm">
                        {historyError}
                    </div>
                )}

                <div className="mt-4 space-y-3">
                    {history.length === 0 ? (
                        <div className="vocab-list-item text-sm muted-copy">
                            No ingestion history loaded.
                        </div>
                    ) : history.map(job => (
                        <article key={job.id} className="vocab-list-item items-start">
                            <div className="min-w-0 flex-1">
                                <div className="flex flex-wrap items-center gap-2">
                                    <span className={statusTone(job.status)}>{job.status}</span>
                                    <span className="soft-badge">{job.lang.toUpperCase()}</span>
                                    <span className="soft-badge">{jobMode(job) === 'generate' ? 'AI' : 'Upload'}</span>
                                    <span className="soft-badge">Job {job.id}</span>
                                    <span className="theme-heading font-medium">{job.workTitle}</span>
                                </div>
                                <div className="mt-2 text-sm muted-copy">
                                    {job.error ?? job.message ?? job.step ?? 'No status message'}
                                </div>
                                <div className="mt-2 flex flex-wrap gap-2 text-xs muted-copy">
                                    <span title={job.fileSpec}>{fileSpecLabel(job)}</span>
                                    {job.author && <span>{job.author}</span>}
                                    {job.publisher && <span>{job.publisher}</span>}
                                    {job.translator && <span>{job.translator}</span>}
                                    {job.originalLang && <span>original {job.originalLang.toUpperCase()}</span>}
                                    {job.year != null && <span>{job.year}</span>}
                                    {isGeneratedJob(job) && job.maxSourceChars != null && (
                                        <span>
                                            max {historyMaxSourceChars(job)} chars
                                            {hasLegacyGeneratedMaxSourceChars(job) ? ' (updated default)' : ''}
                                        </span>
                                    )}
                                    {isGeneratedJob(job) && job.chunkMaxChars != null && <span>chunk {job.chunkMaxChars}</span>}
                                    {isGeneratedJob(job) && job.contextChars != null && <span>context {job.contextChars}</span>}
                                    {isGeneratedJob(job) && job.model && <span>model {job.model}</span>}
                                    {isGeneratedJob(job) && job.replaceExisting != null && (
                                        <span>{job.replaceExisting ? 'replace existing' : 'keep existing'}</span>
                                    )}
                                    <span>{job.chapters} chapters</span>
                                    <span>{job.paragraphs} paragraphs</span>
                                    <span>{job.sentences} sentences</span>
                                    {job.workId && <span>work {job.workId}</span>}
                                    {job.editionId && <span>edition {job.editionId}</span>}
                                    {job.createdAt && <span>created {formatJobTime(job.createdAt)}</span>}
                                    {job.finishedAt && <span>finished {formatJobTime(job.finishedAt)}</span>}
                                </div>
                            </div>
                            <div className="flex shrink-0 flex-wrap gap-2">
                                <button
                                    type="button"
                                    className="btn-secondary text-xs"
                                    onClick={() => applyJobMetadata(job)}
                                >
                                    Use metadata
                                </button>
                                {canRerunJob(job) && (
                                    <button
                                        type="button"
                                        className="btn-secondary text-xs"
                                        onClick={() => void rerunHistoryJob(job)}
                                        disabled={retryingJobId === job.id}
                                    >
                                        {retryingJobId === job.id ? 'Queuing...' : rerunLabel(job)}
                                    </button>
                                )}
                            </div>
                        </article>
                    ))}
                </div>
            </section>

            {jobs.length > 0 && (
                <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                    <div className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
                        <div>
                            <div className="soft-badge mb-2">Jobs</div>
                            <h3 className="theme-heading text-xl font-semibold">Ingestion status</h3>
                        </div>
                    </div>

                    <div className="mt-4 space-y-3">
                        {jobs.map(job => (
                            <article key={job.localId} className="vocab-list-item">
                                <div className="min-w-0 flex-1">
                                    <div className="flex flex-wrap items-center gap-2">
                                        <span className={statusTone(job.status)}>{job.status}</span>
                                        <span className="soft-badge">{job.editionLang.toUpperCase()}</span>
                                        <span className="soft-badge">{job.mode === 'generate' ? 'AI' : 'Upload'}</span>
                                        {job.jobId && <span className="soft-badge">Job {job.jobId}</span>}
                                        <span className="theme-heading font-medium">{job.label}</span>
                                    </div>
                                    <div className="mt-2 text-sm muted-copy">
                                        {job.error ?? job.message ?? job.step ?? 'Waiting for server status'}
                                    </div>
                                    {(job.chapters != null || job.paragraphs != null || job.sentences != null) && (
                                        <div className="mt-2 flex flex-wrap gap-2 text-xs muted-copy">
                                            <span>{job.chapters ?? 0} chapters</span>
                                            <span>{job.paragraphs ?? 0} paragraphs</span>
                                            <span>{job.sentences ?? 0} sentences</span>
                                            {job.workId && <span>work {job.workId}</span>}
                                            {job.editionId && <span>edition {job.editionId}</span>}
                                        </div>
                                    )}
                                </div>
                                {job.jobId && !isTerminalStatus(job.status) && (
                                    <button
                                        type="button"
                                        className="btn-secondary text-xs"
                                        onClick={() => {
                                            void refreshJob(job.jobId as number)
                                                .then(serverJob => patchJob(job.localId, {
                                                    status: serverJob.status,
                                                    step: serverJob.step,
                                                    message: serverJob.message,
                                                    error: serverJob.error,
                                                    workId: serverJob.workId,
                                                    editionId: serverJob.editionId,
                                                    chapters: serverJob.chapters,
                                                    paragraphs: serverJob.paragraphs,
                                                    sentences: serverJob.sentences,
                                                }))
                                                .catch(error => patchJob(job.localId, {
                                                    error: error instanceof Error ? error.message : 'Could not refresh job status.',
                                                }))
                                        }}
                                    >
                                        Refresh
                                    </button>
                                )}
                            </article>
                        ))}
                    </div>
                </section>
            )}
        </div>
    )
}

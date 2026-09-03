import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { type CatalogEntry } from '../readerNavigation'

const ADMIN_TOKEN_KEY = 'mlr:admin-token'
const DEFAULT_GENERATED_TRANSLATOR = 'AI literary translation'
const DEFAULT_GENERATE_MAX_SOURCE_CHARS = '120000'
const DEFAULT_GENERATE_CHUNK_MAX_CHARS = '1800'
const DEFAULT_GENERATE_CONTEXT_CHARS = '600'

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
    replaceExisting: boolean
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

type IngestionJob = {
    id: number
    status: string
    step?: string | null
    message?: string | null
    error?: string | null
    workTitle: string
    lang: string
    fileSpec: string
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
        replaceExisting: true,
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

function isTerminalStatus(status: string) {
    return status === 'SUCCEEDED' || status === 'FAILED'
}

function statusTone(status: string) {
    if (status === 'SUCCEEDED') return 'theme-success'
    if (status === 'FAILED') return 'soft-badge theme-danger'
    return 'soft-badge'
}

async function responseError(response: Response) {
    if (response.status === 401) return 'Missing or invalid admin token.'
    if (response.status === 403) return 'Admin API token is not configured on the server.'
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
    const [adminToken, setAdminToken] = useState(() => window.sessionStorage.getItem(ADMIN_TOKEN_KEY) ?? '')
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
    const [submitting, setSubmitting] = useState(false)
    const [formError, setFormError] = useState<string | null>(null)

    useEffect(() => {
        if (adminToken.trim()) {
            window.sessionStorage.setItem(ADMIN_TOKEN_KEY, adminToken)
        } else {
            window.sessionStorage.removeItem(ADMIN_TOKEN_KEY)
        }
    }, [adminToken])

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
    }, [])

    const selectedCatalogWork = useMemo(
        () => catalog.find(item => String(item.workId) === selectedWorkId) ?? null,
        [catalog, selectedWorkId]
    )

    const selectedCatalogLangs = useMemo(
        () => new Set((selectedCatalogWork?.langs ?? []).map(normalizeLang)),
        [selectedCatalogWork]
    )

    const languageOptions = useMemo(() => {
        const langs = new Set(['de', 'en', 'es', 'fr', 'ru'])
        catalog.forEach(book => book.langs.forEach(lang => langs.add(lang)))
        if (work.originalLang.trim()) {
            langs.add(normalizeLang(work.originalLang))
        }
        editions.forEach(edition => {
            if (edition.lang.trim()) langs.add(normalizeLang(edition.lang))
            if (edition.sourceLang.trim()) langs.add(normalizeLang(edition.sourceLang))
        })
        return Array.from(langs).filter(Boolean).sort()
    }, [catalog, editions, work.originalLang])

    const originalEditionMissing = work.originalLang.trim() &&
        !selectedCatalogLangs.has(normalizeLang(work.originalLang)) &&
        !editions.some(edition =>
            edition.mode === 'upload' && normalizeLang(edition.lang) === normalizeLang(work.originalLang)
        )

    function applyCatalogWork(workId: string) {
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
    }

    function addEdition() {
        setEditions(current => [...current, createEditionDraft()])
    }

    function removeEdition(id: string) {
        setEditions(current => current.length === 1 ? current : current.filter(edition => edition.id !== id))
    }

    function patchJob(localId: string, patch: Partial<SubmittedJob>) {
        setJobs(current => current.map(job => job.localId === localId ? {...job, ...patch} : job))
    }

    function validateForm(): string | null {
        if (!adminToken.trim()) return 'Admin token is required.'
        if (!work.title.trim()) return 'Work title is required.'
        if (!work.originalLang.trim()) return 'Original language is required.'
        if (work.year.trim() && !Number.isInteger(Number(work.year.trim()))) {
            return 'Publication year must be a whole number.'
        }

        const preparedLangs = editions.map(edition => normalizeLang(edition.lang)).filter(Boolean)
        if (new Set(preparedLangs).size !== preparedLangs.length) {
            return 'Use one edition per language in a single submission.'
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
            }
        }

        return null
    }

    async function submitEdition(edition: PreparedUploadEdition, token: string) {
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

        const response = await fetch('/api/admin/ingest/jobs/upload', {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${token}`,
            },
            body: form,
        })

        if (!response.ok) {
            throw new Error(await responseError(response))
        }

        return await response.json() as StartIngestionResponse
    }

    async function submitGeneratedEdition(edition: PreparedGeneratedEdition, token: string) {
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

        const response = await fetch('/api/admin/ingest/jobs/generate-literary', {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${token}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(payload),
        })

        if (!response.ok) {
            throw new Error(await responseError(response))
        }

        return await response.json() as StartIngestionResponse
    }

    async function refreshJob(jobId: number, token: string) {
        const response = await fetch(`/api/admin/ingest/jobs/${jobId}`, {
            headers: {
                Authorization: `Bearer ${token}`,
            },
        })

        if (!response.ok) {
            throw new Error(await responseError(response))
        }

        return await response.json() as IngestionJob
    }

    async function pollJob(localId: string, jobId: number, token: string) {
        for (let attempt = 0; attempt < 240; attempt += 1) {
            await new Promise(resolve => window.setTimeout(resolve, attempt === 0 ? 600 : 2000))
            try {
                const job = await refreshJob(jobId, token)
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
                if (isTerminalStatus(job.status)) return job
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
            (edition): edition is PreparedUploadEdition => edition.mode === 'upload'
        )
        const generatedEditions = preparedEditions.filter(
            (edition): edition is PreparedGeneratedEdition => edition.mode === 'generate'
        )
        const requiredUploadSources = new Set(generatedEditions.map(edition => edition.sourceLang))
        const failedUploadLangs = new Set<string>()

        setSubmitting(true)
        const token = adminToken.trim()
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
                    const started = await submitEdition(edition, token)
                    patchJob(localId, {
                        jobId: started.jobId,
                        status: started.status,
                        message: 'Job queued',
                    })
                    if (requiredUploadSources.has(edition.lang)) {
                        const terminalJob = await pollJob(localId, started.jobId, token)
                        if (terminalJob?.status !== 'SUCCEEDED') {
                            failedUploadLangs.add(edition.lang)
                        }
                    } else {
                        void pollJob(localId, started.jobId, token)
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
                    const started = await submitGeneratedEdition(edition, token)
                    patchJob(localId, {
                        jobId: started.jobId,
                        status: started.status,
                        message: 'Generation job queued',
                    })
                    void pollJob(localId, started.jobId, token)
                } catch (error) {
                    patchJob(localId, {
                        status: 'FAILED',
                        error: error instanceof Error ? error.message : 'Could not submit generation job.',
                    })
                }
            }
        } finally {
            setSubmitting(false)
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
                    <label className="flex min-w-0 flex-col gap-2 text-sm md:min-w-56">
                        <span className="text-xs uppercase tracking-[0.18em] muted-copy">
                            Admin token
                        </span>
                        <input
                            type="password"
                            value={adminToken}
                            onChange={event => setAdminToken(event.target.value)}
                            className="input"
                            autoComplete="off"
                        />
                    </label>
                </div>
            </section>

            <form className="space-y-5" onSubmit={submitAll}>
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
                                    onChange={event => setWork(current => ({...current, title: event.target.value}))}
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
                                    onChange={event => setWork(current => ({...current, originalLang: normalizeLang(event.target.value)}))}
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
                                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">Language</span>
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
                                    ) : (
                                        <div className="space-y-3">
                                            <div className="grid gap-3 md:grid-cols-4">
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
                                                    <span className="text-xs uppercase tracking-[0.18em] muted-copy">Max source chars</span>
                                                    <input
                                                        type="number"
                                                        min="1"
                                                        value={edition.maxSourceChars}
                                                        onChange={event => updateEdition(edition.id, {maxSourceChars: event.target.value})}
                                                        className="input"
                                                        inputMode="numeric"
                                                    />
                                                </label>
                                                <label className="flex flex-col gap-2 text-sm">
                                                    <span className="text-xs uppercase tracking-[0.18em] muted-copy">Chunk chars</span>
                                                    <input
                                                        type="number"
                                                        min="1"
                                                        value={edition.chunkMaxChars}
                                                        onChange={event => updateEdition(edition.id, {chunkMaxChars: event.target.value})}
                                                        className="input"
                                                        inputMode="numeric"
                                                    />
                                                </label>
                                                <label className="flex flex-col gap-2 text-sm">
                                                    <span className="text-xs uppercase tracking-[0.18em] muted-copy">Context chars</span>
                                                    <input
                                                        type="number"
                                                        min="0"
                                                        value={edition.contextChars}
                                                        onChange={event => updateEdition(edition.id, {contextChars: event.target.value})}
                                                        className="input"
                                                        inputMode="numeric"
                                                    />
                                                </label>
                                            </div>
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
                        <div className="theme-danger mt-4 rounded-[20px] border px-4 py-3 text-sm">
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
                                            void refreshJob(job.jobId as number, adminToken.trim())
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

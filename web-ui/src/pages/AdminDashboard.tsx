import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { csrfFetch } from '../csrfFetch'

type Overview = {
    registeredUsers?: number
    activeUsersToday?: number
    activeUsers7d?: number
    translationCallsToday?: number
    translationFailuresToday?: number
    translationFailureRateToday?: number
    cacheHitRateToday?: number
    ingestionRunning?: number
    ingestionFailedToday?: number
    translationP95LatencyMs?: number
    topLanguagePair?: string
}

type MetricRow = {
    day?: string
    label?: string
    operation?: string
    value?: number
    calls?: number
    failures?: number
    p95_latency_ms?: number | null
}

type IngestionJob = {
    id: number
    status: string
    workTitle: string
    lang: string
    error?: string | null
    createdAt?: string | null
    finishedAt?: string | null
}

type IngestionMetrics = {
    byStatus?: MetricRow[]
    averageDurationSeconds?: number
    recent?: IngestionJob[]
}

type ApiToken = {
    id: number
    name: string
    scopes: string[]
    createdAt?: string
    expiresAt?: string | null
    lastUsedAt?: string | null
    revokedAt?: string | null
}

type CreatedApiToken = {
    token: ApiToken
    secret: string
}

type DashboardData = {
    overview: Overview
    usersDaily: MetricRow[]
    readerDaily: MetricRow[]
    translationDaily: MetricRow[]
    vocabularyDaily: MetricRow[]
    translationOperations: MetricRow[]
    translationProviders: MetricRow[]
    books: MetricRow[]
    languagePairs: MetricRow[]
    reviews: MetricRow[]
    ingestion: IngestionMetrics
    errors: MetricRow[]
    system: Record<string, unknown>
    tokens: ApiToken[]
}

function numberValue(value: unknown) {
    return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function formatNumber(value: unknown) {
    return new Intl.NumberFormat().format(numberValue(value))
}

function formatPercent(value: unknown) {
    return `${numberValue(value).toFixed(1)}%`
}

function formatDuration(value: unknown) {
    const seconds = numberValue(value)
    if (seconds <= 0) return '0s'
    if (seconds < 60) return `${Math.round(seconds)}s`
    return `${Math.round(seconds / 60)}m`
}

function formatTime(value?: string | null) {
    if (!value) return ''
    return new Date(value).toLocaleString()
}

async function fetchJson<T>(url: string): Promise<T> {
    const response = await fetch(url)
    if (!response.ok) {
        throw new Error(await response.text() || `${response.status} ${response.statusText}`)
    }
    return await response.json() as T
}

export default function AdminDashboard() {
    const [data, setData] = useState<DashboardData | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(true)
    const [tokenName, setTokenName] = useState('Deploy ingestion')
    const [tokenScope, setTokenScope] = useState('admin:ingest')
    const [createdSecret, setCreatedSecret] = useState<string | null>(null)
    const [tokenError, setTokenError] = useState<string | null>(null)
    const [tokenBusy, setTokenBusy] = useState(false)

    useEffect(() => {
        let alive = true
        setLoading(true)
        setError(null)
        ;(async () => {
            try {
                const [
                    overview,
                    usersDaily,
                    readerDaily,
                    translationDaily,
                    vocabularyDaily,
                    translationOperations,
                    translationProviders,
                    books,
                    languagePairs,
                    reviews,
                    ingestion,
                    errors,
                    system,
                    tokens,
                ] = await Promise.all([
                    fetchJson<Overview>('/api/admin/metrics/overview'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/users'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/usage/daily?eventType=reader_page_viewed'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/translations/daily'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/usage/daily?eventType=vocabulary_saved'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/translations/operations'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/translations/providers'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/books'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/language-pairs'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/reviews'),
                    fetchJson<IngestionMetrics>('/api/admin/metrics/ingestion'),
                    fetchJson<MetricRow[]>('/api/admin/metrics/errors'),
                    fetchJson<Record<string, unknown>>('/api/admin/metrics/system'),
                    fetchJson<ApiToken[]>('/api/admin/tokens'),
                ])
                if (!alive) return
                setData({
                    overview,
                    usersDaily,
                    readerDaily,
                    translationDaily,
                    vocabularyDaily,
                    translationOperations,
                    translationProviders,
                    books,
                    languagePairs,
                    reviews,
                    ingestion,
                    errors,
                    system,
                    tokens,
                })
            } catch (caught) {
                if (!alive) return
                setError(caught instanceof Error ? caught.message : 'Could not load admin metrics.')
            } finally {
                if (alive) setLoading(false)
            }
        })()
        return () => {
            alive = false
        }
    }, [])

    async function refreshTokens() {
        const tokens = await fetchJson<ApiToken[]>('/api/admin/tokens')
        setData(current => current ? { ...current, tokens } : current)
    }

    async function createToken() {
        setTokenBusy(true)
        setTokenError(null)
        setCreatedSecret(null)
        try {
            const response = await csrfFetch('/api/admin/tokens', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    name: tokenName,
                    scopes: [tokenScope],
                }),
            })
            if (!response.ok) {
                throw new Error(await response.text() || `${response.status} ${response.statusText}`)
            }
            const created = await response.json() as CreatedApiToken
            setCreatedSecret(created.secret)
            setData(current => current ? { ...current, tokens: [created.token, ...current.tokens] } : current)
        } catch (caught) {
            setTokenError(caught instanceof Error ? caught.message : 'Could not create token.')
        } finally {
            setTokenBusy(false)
        }
    }

    async function revokeToken(id: number) {
        setTokenBusy(true)
        setTokenError(null)
        try {
            const response = await csrfFetch(`/api/admin/tokens/${id}/revoke`, { method: 'POST' })
            if (!response.ok) {
                throw new Error(await response.text() || `${response.status} ${response.statusText}`)
            }
            await refreshTokens()
        } catch (caught) {
            setTokenError(caught instanceof Error ? caught.message : 'Could not revoke token.')
        } finally {
            setTokenBusy(false)
        }
    }

    const overviewCards = useMemo(() => {
        const overview = data?.overview ?? {}
        return [
            ['Active today', formatNumber(overview.activeUsersToday)],
            ['Active 7d', formatNumber(overview.activeUsers7d)],
            ['Registered', formatNumber(overview.registeredUsers)],
            ['Translations today', formatNumber(overview.translationCallsToday)],
            ['Translation failures', formatPercent(overview.translationFailureRateToday)],
            ['Cache hit rate', formatPercent(overview.cacheHitRateToday)],
            ['Ingestion running', formatNumber(overview.ingestionRunning)],
            ['Failed ingestion today', formatNumber(overview.ingestionFailedToday)],
            ['P95 translation', `${formatNumber(overview.translationP95LatencyMs)} ms`],
            ['Top pair', overview.topLanguagePair || 'None'],
        ]
    }, [data])

    return (
        <div className="space-y-5">
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
                    <div>
                        <div className="soft-badge mb-2">Admin</div>
                        <h2 className="theme-heading text-2xl font-semibold md:text-[1.85rem]">
                            Metrics dashboard
                        </h2>
                    </div>
                    <Link className="btn" to="/admin/ingest">Open ingestion</Link>
                </div>
            </section>

            {(loading || error) && (
                <section className="surface-panel rounded-[28px] px-5 py-4 md:px-6">
                    <div className={error ? 'theme-danger text-sm' : 'muted-copy text-sm'}>
                        {error ?? 'Loading metrics...'}
                    </div>
                </section>
            )}

            {data && (
                <>
                    <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
                        {overviewCards.map(([label, value]) => (
                            <div key={label} className="surface-panel rounded-[22px] px-4 py-4">
                                <div className="muted-copy text-xs uppercase tracking-[0.16em]">{label}</div>
                                <div className="theme-heading mt-2 text-2xl font-semibold">{value}</div>
                            </div>
                        ))}
                    </section>

                    <section className="grid gap-5 lg:grid-cols-2">
                        <MetricPanel title="Active users" rows={data.usersDaily} labelKey="day" />
                        <MetricPanel title="Reader page views" rows={data.readerDaily} labelKey="day" />
                        <MetricPanel title="Translations" rows={data.translationDaily} labelKey="day" />
                        <MetricPanel title="Vocabulary saves" rows={data.vocabularyDaily} labelKey="day" />
                    </section>

                    <section className="grid gap-5 lg:grid-cols-2">
                        <TablePanel
                            title="Translation operations"
                            rows={data.translationOperations}
                            columns={[
                                ['Operation', row => row.operation ?? 'unknown'],
                                ['Calls', row => formatNumber(row.calls)],
                                ['Failures', row => formatNumber(row.failures)],
                                ['P95', row => `${formatNumber(row.p95_latency_ms)} ms`],
                            ]}
                        />
                        <TablePanel
                            title="Translation providers"
                            rows={data.translationProviders}
                            columns={[
                                ['Provider', row => row.label ?? 'unknown'],
                                ['Calls', row => formatNumber(row.calls)],
                                ['Failures', row => formatNumber(row.failures)],
                                ['P95', row => `${formatNumber(row.p95_latency_ms)} ms`],
                            ]}
                        />
                        <TablePanel
                            title="Language pairs"
                            rows={data.languagePairs}
                            columns={[
                                ['Pair', row => row.label ?? 'Unknown'],
                                ['Events', row => formatNumber(row.value)],
                            ]}
                        />
                        <TablePanel
                            title="Review grades"
                            rows={data.reviews}
                            columns={[
                                ['Grade', row => row.label ?? 'Unknown'],
                                ['Reviews', row => formatNumber(row.value)],
                            ]}
                        />
                    </section>

                    <section className="grid gap-5 lg:grid-cols-2">
                        <TablePanel
                            title="Top books"
                            rows={data.books}
                            columns={[
                                ['Book', row => row.label ?? 'Unknown'],
                                ['Views', row => formatNumber(row.value)],
                            ]}
                        />
                        <TablePanel
                            title={`Ingestion (${formatDuration(data.ingestion.averageDurationSeconds)} avg)`}
                            rows={data.ingestion.byStatus ?? []}
                            columns={[
                                ['Status', row => row.label ?? 'Unknown'],
                                ['Jobs', row => formatNumber(row.value)],
                            ]}
                        />
                    </section>

                    <section className="grid gap-5 lg:grid-cols-2">
                        <TablePanel
                            title="Recent ingestion"
                            rows={data.ingestion.recent ?? []}
                            columns={[
                                ['Job', row => `#${row.id}`],
                                ['Work', row => row.workTitle],
                                ['Status', row => row.status],
                                ['Created', row => formatTime(row.createdAt)],
                            ]}
                        />
                        <TablePanel
                            title="System health"
                            rows={[
                                { label: 'Database', value: String(data.system.database ?? 'unknown') },
                                { label: 'Provider failures', value: numberValue(data.system.providerFailures) },
                                { label: 'Raw event retention', value: `${numberValue(data.system.rawEventRetentionDays)} days` },
                                { label: 'Daily aggregate retention', value: `${numberValue(data.system.dailyAggregateRetentionDays)} days` },
                            ] as Array<{label: string; value: string | number}>}
                            columns={[
                                ['Check', row => row.label],
                                ['Value', row => String(row.value)],
                            ]}
                        />
                        <TablePanel
                            title="Errors"
                            rows={data.errors}
                            columns={[
                                ['Error', row => row.label ?? 'None'],
                                ['Count', row => formatNumber(row.value)],
                            ]}
                            emptyText="No errors in this range."
                        />
                    </section>

                    <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
                            <div>
                                <h3 className="theme-heading text-lg font-semibold">Automation tokens</h3>
                            </div>
                            <div className="grid gap-2 sm:grid-cols-[minmax(12rem,1fr)_10rem_auto]">
                                <input
                                    className="input"
                                    value={tokenName}
                                    onChange={event => setTokenName(event.target.value)}
                                    aria-label="Token name"
                                />
                                <select
                                    className="input"
                                    value={tokenScope}
                                    onChange={event => setTokenScope(event.target.value)}
                                    aria-label="Token scope"
                                >
                                    <option value="admin:ingest">admin:ingest</option>
                                    <option value="admin:metrics">admin:metrics</option>
                                    <option value="admin:*">admin:*</option>
                                </select>
                                <button
                                    type="button"
                                    className="btn"
                                    onClick={() => void createToken()}
                                    disabled={tokenBusy}
                                >
                                    Create
                                </button>
                            </div>
                        </div>
                        {createdSecret && (
                            <div className="mt-4 rounded-[20px] border border-[color:var(--border)] px-4 py-3">
                                <div className="muted-copy text-xs uppercase tracking-[0.14em]">Secret</div>
                                <div className="theme-heading mt-2 break-all font-mono text-sm">{createdSecret}</div>
                            </div>
                        )}
                        {tokenError && <div className="theme-status mt-3 text-sm">{tokenError}</div>}
                        <div className="mt-4 space-y-3">
                            {(data.tokens ?? []).map(token => (
                                <article key={token.id} className="vocab-list-item">
                                    <div className="min-w-0">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <span className="theme-heading font-medium">{token.name}</span>
                                            {token.scopes.map(scope => <span key={scope} className="soft-badge">{scope}</span>)}
                                            {token.revokedAt && <span className="soft-badge theme-danger">revoked</span>}
                                        </div>
                                        <div className="mt-1 text-xs muted-copy">
                                            Created {formatTime(token.createdAt)}{token.lastUsedAt ? ` · used ${formatTime(token.lastUsedAt)}` : ''}
                                        </div>
                                    </div>
                                    {!token.revokedAt && (
                                        <button
                                            type="button"
                                            className="btn-secondary text-xs"
                                            onClick={() => void revokeToken(token.id)}
                                            disabled={tokenBusy}
                                        >
                                            Revoke
                                        </button>
                                    )}
                                </article>
                            ))}
                            {(data.tokens ?? []).length === 0 && (
                                <div className="muted-copy rounded-[20px] border border-[color:var(--border)] px-4 py-4 text-sm">
                                    No tokens.
                                </div>
                            )}
                        </div>
                    </section>
                </>
            )}
        </div>
    )
}

function MetricPanel({ title, rows, labelKey }: {title: string; rows: MetricRow[]; labelKey: 'day' | 'label'}) {
    const max = rows.reduce((current, row) => Math.max(current, numberValue(row.value)), 0)
    return (
        <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
            <h3 className="theme-heading text-lg font-semibold">{title}</h3>
            <div className="mt-4 space-y-2">
                {rows.map(row => {
                    const value = numberValue(row.value)
                    const width = max > 0 ? Math.max(4, Math.round((value / max) * 100)) : 0
                    return (
                        <div key={row[labelKey] ?? 'unknown'} className="grid grid-cols-[6.5rem_minmax(0,1fr)_3rem] items-center gap-3 text-sm">
                            <div className="muted-copy truncate">{row[labelKey] ?? 'Unknown'}</div>
                            <div className="h-2 rounded-full bg-[color:var(--surface-muted)]">
                                <div className="h-2 rounded-full bg-[color:var(--accent-strong)]" style={{ width: `${width}%` }} />
                            </div>
                            <div className="theme-heading text-right font-medium">{formatNumber(value)}</div>
                        </div>
                    )
                })}
                {rows.length === 0 && <div className="muted-copy text-sm">No data in this range.</div>}
            </div>
        </section>
    )
}

function TablePanel<T>({
    title,
    rows,
    columns,
    emptyText = 'No data in this range.',
}: {
    title: string
    rows: T[]
    columns: Array<[string, (row: T) => string]>
    emptyText?: string
}) {
    return (
        <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
            <h3 className="theme-heading text-lg font-semibold">{title}</h3>
            <div className="mt-4 overflow-x-auto">
                <table className="w-full min-w-[28rem] text-left text-sm">
                    <thead className="muted-copy text-xs uppercase tracking-[0.14em]">
                    <tr>
                        {columns.map(([label]) => <th key={label} className="pb-2 pr-3 font-medium">{label}</th>)}
                    </tr>
                    </thead>
                    <tbody>
                    {rows.map((row, index) => (
                        <tr key={index} className="border-t border-[color:var(--border)]">
                            {columns.map(([label, render]) => (
                                <td key={label} className="theme-copy py-2 pr-3 align-top">{render(row)}</td>
                            ))}
                        </tr>
                    ))}
                    </tbody>
                </table>
                {rows.length === 0 && <div className="muted-copy text-sm">{emptyText}</div>}
            </div>
        </section>
    )
}

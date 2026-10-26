import { useEffect, useMemo, useState } from 'react'
import { useAuth } from './authContext'
import { importLocalStateToAccount, type LocalStateImportInput } from './localStateImportApi'
import { readLastReaderSession } from './readerNavigation'
import {
    type ReaderProgressInput,
    type UserPreferences,
} from './userReaderStateApi'
import { readVocabularyItems } from './vocabularyStorage'

const READER_PROGRESS_PREFIX = 'mlr:reader-progress:'
const FOLLOW_ALIGNMENT_KEY = 'mlr:reader-follow-alignment'
const VISUAL_ALIGNMENT_KEY = 'mlr:reader-visual-alignment'
const MOBILE_PANE_LAYOUT_KEY = 'mlr:reader-mobile-pane-layout'

type LocalStateSnapshot = LocalStateImportInput

type ImportStatus =
    | { kind: 'idle' }
    | { kind: 'importing' }
    | { kind: 'error'; message: string }

export default function LocalStateImportBanner() {
    const auth = useAuth()
    const [snapshot, setSnapshot] = useState<LocalStateSnapshot | null>(null)
    const [dismissed, setDismissed] = useState(false)
    const [status, setStatus] = useState<ImportStatus>({ kind: 'idle' })

    const completeKey = auth.user ? `mlr:account-import-complete:${auth.user.id}` : ''
    const dismissedKey = auth.user ? `mlr:account-import-dismissed:${auth.user.id}` : ''

    useEffect(() => {
        if (auth.loading || !auth.authenticated || !auth.user) {
            setSnapshot(null)
            setDismissed(false)
            setStatus({ kind: 'idle' })
            return
        }
        try {
            if (
                window.localStorage.getItem(completeKey) === 'true' ||
                window.localStorage.getItem(dismissedKey) === 'true'
            ) {
                setSnapshot(null)
                setDismissed(true)
                return
            }
        } catch {
            setDismissed(false)
        }
        const next = readLocalStateSnapshot()
        setSnapshot(hasLocalState(next) ? next : null)
        setStatus({ kind: 'idle' })
        setDismissed(false)
    }, [auth.authenticated, auth.loading, auth.user, completeKey, dismissedKey])

    const totalItems = useMemo(() => {
        if (!snapshot) return 0
        return snapshot.vocabulary.length +
            snapshot.progress.length +
            Object.keys(snapshot.preferences).length
    }, [snapshot])

    if (!auth.authenticated || auth.loading || dismissed || !snapshot || totalItems === 0) {
        return null
    }

    async function importLocalState() {
        if (!snapshot || !completeKey) return
        setStatus({ kind: 'importing' })
        try {
            const result = await importLocalStateToAccount(snapshot)
            if (result.failed > 0) {
                throw new Error(`Imported ${importedCount(result)} item${importedCount(result) === 1 ? '' : 's'}, but ${result.failed} failed.`)
            }
            window.localStorage.setItem(completeKey, 'true')
            setSnapshot(null)
        } catch (caught) {
            setStatus({
                kind: 'error',
                message: caught instanceof Error ? caught.message : 'Could not import local data.',
            })
        }
    }

    function dismiss() {
        if (dismissedKey) {
            try {
                window.localStorage.setItem(dismissedKey, 'true')
            } catch {
                // Dismissal is convenience-only.
            }
        }
        setDismissed(true)
    }

    return (
        <section className="surface-panel mb-5 rounded-[22px] px-4 py-4">
            <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                <div>
                    <div className="theme-heading text-sm font-semibold">Import local reading data</div>
                    <p className="mt-1 text-sm muted-copy">
                        This browser has {totalItems} local item{totalItems === 1 ? '' : 's'} that can be copied to your account.
                    </p>
                    {status.kind === 'error' && (
                        <p className="theme-status mt-2 text-sm">{status.message}</p>
                    )}
                </div>
                <div className="flex flex-wrap gap-2">
                    <button
                        type="button"
                        className="btn"
                        onClick={() => void importLocalState()}
                        disabled={status.kind === 'importing'}
                    >
                        {status.kind === 'importing' ? 'Importing...' : 'Import'}
                    </button>
                    <button type="button" className="btn-secondary" onClick={dismiss}>
                        Not now
                    </button>
                </div>
            </div>
        </section>
    )
}

function importedCount(result: {vocabularyImported: number; progressImported: number; preferencesImported: number}) {
    return result.vocabularyImported + result.progressImported + result.preferencesImported
}

function readLocalStateSnapshot(): LocalStateSnapshot {
    return {
        vocabulary: readVocabularyItems(),
        progress: readLocalReaderProgress(),
        preferences: readLocalPreferences(),
    }
}

function hasLocalState(snapshot: LocalStateSnapshot) {
    return snapshot.vocabulary.length > 0 ||
        snapshot.progress.length > 0 ||
        Object.keys(snapshot.preferences).length > 0
}

function readLocalReaderProgress() {
    const byKey = new Map<string, ReaderProgressInput>()
    try {
        for (let index = 0; index < window.localStorage.length; index += 1) {
            const key = window.localStorage.key(index)
            if (!key?.startsWith(READER_PROGRESS_PREFIX)) continue
            const [bookId, source, target] = key.slice(READER_PROGRESS_PREFIX.length).split(':')
            if (!bookId || !source || !target) continue
            const raw = window.localStorage.getItem(key)
            if (!raw) continue
            const parsed = JSON.parse(raw) as { page?: unknown; size?: unknown }
            if (typeof parsed.page !== 'number' || typeof parsed.size !== 'number') continue
            addProgress(byKey, {
                bookId,
                source,
                target,
                page: Math.max(0, Math.floor(parsed.page)),
                size: Math.max(1, Math.floor(parsed.size)),
            })
        }

        const lastSession = readLastReaderSession()
        if (lastSession) {
            addProgress(byKey, {
                bookId: lastSession.bookId,
                source: lastSession.src,
                target: lastSession.tgt,
                page: Math.max(0, Math.floor(lastSession.page)),
                size: Math.max(1, Math.floor(lastSession.size)),
            })
        }
    } catch {
        // Ignore corrupt local state; import should remain best-effort.
    }
    return Array.from(byKey.values())
}

function addProgress(target: Map<string, ReaderProgressInput>, progress: ReaderProgressInput) {
    target.set(`${progress.bookId}:${progress.source}:${progress.target}`, progress)
}

function readLocalPreferences(): UserPreferences {
    const preferences: UserPreferences = {}
    try {
        const followAlignment = window.localStorage.getItem(FOLLOW_ALIGNMENT_KEY)
        if (followAlignment === 'true' || followAlignment === 'false') {
            preferences['reader.followAlignment'] = followAlignment === 'true'
        }
        const visualAlignment = window.localStorage.getItem(VISUAL_ALIGNMENT_KEY)
        if (visualAlignment === 'true' || visualAlignment === 'false') {
            preferences['reader.visualAlignment'] = visualAlignment === 'true'
        }
        const mobilePaneLayout = window.localStorage.getItem(MOBILE_PANE_LAYOUT_KEY)
        if (mobilePaneLayout === 'stack' || mobilePaneLayout === 'split') {
            preferences['reader.mobilePaneLayout'] = mobilePaneLayout
        }
    } catch {
        // Ignore localStorage access failures.
    }
    return preferences
}

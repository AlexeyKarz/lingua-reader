import { csrfFetch } from './csrfFetch'
import type { ReaderProgressInput, UserPreferences } from './userReaderStateApi'
import type { SavedVocabularyItem } from './vocabularyStorage'

export type LocalStateImportInput = {
    vocabulary: SavedVocabularyItem[]
    progress: ReaderProgressInput[]
    preferences: UserPreferences
}

export type LocalStateImportResponse = {
    vocabularyImported: number
    progressImported: number
    preferencesImported: number
    failed: number
    failures: Array<{
        section: string
        index: number
        message: string
    }>
}

export async function importLocalStateToAccount(input: LocalStateImportInput) {
    let vocabularyImported = 0
    let failed = 0
    const failures: LocalStateImportResponse['failures'] = []

    if (input.vocabulary.length > 0) {
        const vocabularyResponse = await csrfFetch('/api/user/vocabulary/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ items: input.vocabulary }),
        })
        if (!vocabularyResponse.ok) {
            throw new Error(await responseError(vocabularyResponse, 'Could not import saved vocabulary.'))
        }
        const vocabularyResult = await vocabularyResponse.json() as {
            created: number
            updated: number
            skipped: number
            failed: number
            failures: LocalStateImportResponse['failures']
        }
        vocabularyImported = vocabularyResult.created + vocabularyResult.updated + vocabularyResult.skipped
        failed += vocabularyResult.failed
        failures.push(...vocabularyResult.failures.map(item => ({
            section: 'vocabulary',
            index: item.index,
            message: item.message,
        })))
    }

    const response = await csrfFetch('/api/user/import-local-state', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            vocabulary: [],
            progress: input.progress,
            preferences: input.preferences,
        }),
    })
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not import local data.'))
    }
    const stateResult = await response.json() as LocalStateImportResponse
    return {
        vocabularyImported,
        progressImported: stateResult.progressImported,
        preferencesImported: stateResult.preferencesImported,
        failed: failed + stateResult.failed,
        failures: failures.concat(stateResult.failures),
    } satisfies LocalStateImportResponse
}

async function responseError(response: Response, fallback: string) {
    try {
        const contentType = response.headers.get('content-type') ?? ''
        if (contentType.includes('application/json')) {
            const body = await response.json() as {error?: string; message?: string}
            return body.error ?? body.message ?? fallback
        }
        return await response.text() || fallback
    } catch {
        return fallback
    }
}

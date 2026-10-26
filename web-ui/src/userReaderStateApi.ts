import { csrfFetch } from './csrfFetch'

export type ReaderProgressInput = {
    bookId: string
    source: string
    target: string
    page: number
    size: number
}

export type SavedReaderState = {
    page: number
    size: number
    savedAt: number
}

export type UserReaderProgress = ReaderProgressInput & {
    savedAt: number
}

export type UserPreferences = {
    'reader.followAlignment'?: boolean
    'reader.visualAlignment'?: boolean
    'reader.mobilePaneLayout'?: 'stack' | 'split'
}

type ReaderProgressResponse = {
    bookId: string
    source: string
    target: string
    page: number
    size: number
    updatedAt?: number
}

export async function fetchUserReaderProgress(bookId: string, source: string, target: string) {
    const query = new URLSearchParams({ bookId, source, target })
    const response = await fetch(`/api/user/reader-progress?${query}`)
    if (response.status === 404) {
        return null
    }
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not load reader progress.'))
    }
    const progress = await response.json() as ReaderProgressResponse
    return {
        page: progress.page,
        size: progress.size,
        savedAt: progress.updatedAt ?? Date.now(),
    } satisfies SavedReaderState
}

export async function fetchRecentUserReaderProgress(limit = 5) {
    const query = new URLSearchParams({ limit: String(limit) })
    const response = await fetch(`/api/user/reader-progress/recent?${query}`)
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not load recent reader progress.'))
    }
    const progress = await response.json() as ReaderProgressResponse[]
    return progress.map(item => ({
        bookId: item.bookId,
        source: item.source,
        target: item.target,
        page: item.page,
        size: item.size,
        savedAt: item.updatedAt ?? Date.now(),
    })) satisfies UserReaderProgress[]
}

export async function saveUserReaderProgress(input: ReaderProgressInput) {
    const response = await csrfFetch('/api/user/reader-progress', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
    })
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not save reader progress.'))
    }
}

export async function fetchUserPreferences() {
    const response = await fetch('/api/user/preferences')
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not load preferences.'))
    }
    return await response.json() as UserPreferences
}

export async function saveUserPreference<K extends keyof UserPreferences>(
    key: K,
    value: NonNullable<UserPreferences[K]>
) {
    const response = await csrfFetch(`/api/user/preferences/${encodeURIComponent(key)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value }),
    })
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not save preference.'))
    }
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

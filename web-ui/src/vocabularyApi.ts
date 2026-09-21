import {
    type ReviewGrade,
    type SaveVocabularyInput,
    type SavedVocabularyItem,
} from './vocabularyStorage'
import { csrfFetch } from './csrfFetch'

export async function fetchUserVocabularyItems() {
    const response = await fetch('/api/user/vocabulary')
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not load saved vocabulary.'))
    }
    return await response.json() as SavedVocabularyItem[]
}

export async function saveUserVocabularyItem(input: SaveVocabularyInput) {
    const response = await csrfFetch('/api/user/vocabulary', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
    })
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not save vocabulary item.'))
    }
    return await response.json() as SavedVocabularyItem
}

export async function reviewUserVocabularyItem(item: SavedVocabularyItem, grade: ReviewGrade) {
    const response = await csrfFetch(`/api/user/vocabulary/${encodeURIComponent(item.id)}/review`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ grade }),
    })
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not review vocabulary item.'))
    }
    return await response.json() as SavedVocabularyItem
}

export async function deleteUserVocabularyItem(id: string) {
    const response = await csrfFetch(`/api/user/vocabulary/${encodeURIComponent(id)}`, {
        method: 'DELETE',
    })
    if (!response.ok) {
        throw new Error(await responseError(response, 'Could not delete vocabulary item.'))
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

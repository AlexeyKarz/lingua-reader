type CsrfResponse = {
    headerName: string
    token: string
}

let csrfToken: CsrfResponse | null = null
let csrfPromise: Promise<CsrfResponse> | null = null

function isUnsafe(method: string) {
    return !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.toUpperCase())
}

async function loadCsrfToken(refresh = false) {
    if (!refresh && csrfToken) return csrfToken
    if (!refresh && csrfPromise) return await csrfPromise

    csrfPromise = fetch('/api/auth/csrf')
        .then(async response => {
            if (!response.ok) {
                throw new Error('Could not load CSRF token.')
            }
            csrfToken = await response.json() as CsrfResponse
            return csrfToken
        })
        .finally(() => {
            csrfPromise = null
        })
    return await csrfPromise
}

export async function csrfFetch(input: RequestInfo | URL, init: RequestInit = {}) {
    const method = init.method ?? (input instanceof Request ? input.method : 'GET')
    if (!isUnsafe(method)) {
        return await fetch(input, init)
    }

    const response = await fetchWithCsrf(input, init)
    if (response.status !== 403) {
        return response
    }

    csrfToken = null
    return await fetchWithCsrf(input, init, true)
}

async function fetchWithCsrf(input: RequestInfo | URL, init: RequestInit, refresh = false) {
    const token = await loadCsrfToken(refresh)
    const headers = new Headers(init.headers ?? (input instanceof Request ? input.headers : undefined))
    headers.set(token.headerName, token.token)
    return await fetch(input, { ...init, headers })
}

import {
    useCallback,
    useEffect,
    useMemo,
    useState,
    type ReactNode,
} from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import {
    AuthContext,
    anonymousState,
    useAuth,
    type AuthContextValue,
    type AuthState,
} from './authContext'
import { csrfFetch } from './csrfFetch'

export function AuthProvider({ children }: {children: ReactNode}) {
    const [state, setState] = useState<AuthState>(anonymousState)
    const [loading, setLoading] = useState(true)

    const refresh = useCallback(async () => {
        try {
            const response = await fetch('/api/me')
            if (!response.ok) {
                setState(anonymousState)
                return
            }
            setState(await response.json() as AuthState)
        } catch {
            setState(anonymousState)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        void refresh()
    }, [refresh])

    const login = useCallback(async (email: string, password: string) => {
        const response = await csrfFetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password }),
        })
        if (!response.ok) {
            throw new Error(await responseError(response, 'Could not sign in.'))
        }
        setState(await response.json() as AuthState)
    }, [])

    const logout = useCallback(async () => {
        try {
            await csrfFetch('/api/auth/logout', { method: 'POST' })
        } finally {
            setState(anonymousState)
        }
    }, [])

    const hasRole = useCallback((role: string) => {
        const normalized = role.trim().toUpperCase()
        return state.user?.roles.some(value => value.toUpperCase() === normalized) === true
    }, [state.user?.roles])

    const value = useMemo<AuthContextValue>(() => ({
        ...state,
        loading,
        refresh,
        login,
        logout,
        hasRole,
    }), [hasRole, loading, login, logout, refresh, state])

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function RequireAdmin({ children }: {children: ReactNode}) {
    const auth = useAuth()
    const location = useLocation()
    const next = `${location.pathname}${location.search}`

    if (auth.loading) {
        return (
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="theme-heading text-lg font-semibold">Loading</div>
            </section>
        )
    }

    if (!auth.authenticated) {
        return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />
    }

    if (!auth.hasRole('ADMIN')) {
        return (
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="theme-heading text-lg font-semibold">Admin access required</div>
            </section>
        )
    }

    return children
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

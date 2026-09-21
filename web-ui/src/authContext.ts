import { createContext, useContext } from 'react'

export type AuthUser = {
    id: number
    email: string
    displayName?: string | null
    roles: string[]
}

export type AuthState = {
    authenticated: boolean
    user: AuthUser | null
}

export type AuthContextValue = AuthState & {
    loading: boolean
    refresh: () => Promise<void>
    login: (email: string, password: string) => Promise<void>
    logout: () => Promise<void>
    hasRole: (role: string) => boolean
}

export const anonymousState: AuthState = {
    authenticated: false,
    user: null,
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth() {
    const context = useContext(AuthContext)
    if (!context) {
        throw new Error('useAuth must be used inside AuthProvider')
    }
    return context
}

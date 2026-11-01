import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../authContext'

type AuthProviders = { google: boolean }

export default function LoginPage() {
    const auth = useAuth()
    const navigate = useNavigate()
    const [params] = useSearchParams()
    const next = useMemo(() => {
        const raw = params.get('next')
        return raw && raw.startsWith('/') ? raw : '/'
    }, [params])
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [error, setError] = useState<string | null>(null)
    const [submitting, setSubmitting] = useState(false)
    const [googleEnabled, setGoogleEnabled] = useState(false)

    useEffect(() => {
        let active = true
        void fetch('/api/auth/providers')
            .then(async response => response.ok ? await response.json() as AuthProviders : { google: false })
            .then(providers => {
                if (active) setGoogleEnabled(providers.google)
            })
            .catch(() => {
                if (active) setGoogleEnabled(false)
            })
        return () => {
            active = false
        }
    }, [])

    useEffect(() => {
        if (params.get('oauthError') === 'true') {
            setError('Google sign-in could not be completed. Please try again.')
        }
    }, [params])

    useEffect(() => {
        if (auth.authenticated) {
            navigate(next, { replace: true })
        }
    }, [auth.authenticated, navigate, next])

    async function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        setError(null)
        setSubmitting(true)
        try {
            await auth.login(email, password)
            navigate(next, { replace: true })
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : 'Could not sign in.')
        } finally {
            setSubmitting(false)
        }
    }

    return (
        <div className="mx-auto max-w-xl">
            <section className="surface-panel rounded-[28px] px-5 py-5 md:px-6">
                <div className="soft-badge mb-2">Account</div>
                <h2 className="theme-heading text-2xl font-semibold md:text-[1.85rem]">Sign in</h2>
                <p className="mt-2 text-sm leading-6 muted-copy">
                    An account is optional for reading. Sign in to keep your reader progress and vocabulary across devices.
                </p>

                {googleEnabled && (
                    <>
                        <a
                            className="btn-secondary mt-5 w-full"
                            href={`/api/auth/oauth2/google?next=${encodeURIComponent(next)}`}
                        >
                            <span aria-hidden="true" className="font-semibold">G</span>
                            Continue with Google
                        </a>
                        <div className="my-5 flex items-center gap-3" aria-hidden="true">
                            <span className="h-px flex-1 bg-[color:var(--border)]" />
                            <span className="text-xs uppercase tracking-[0.18em] muted-copy">or use a local account</span>
                            <span className="h-px flex-1 bg-[color:var(--border)]" />
                        </div>
                    </>
                )}

                <form className={googleEnabled ? 'space-y-4' : 'mt-5 space-y-4'} onSubmit={submit}>
                    <label className="flex flex-col gap-2 text-sm">
                        <span className="text-xs uppercase tracking-[0.18em] muted-copy">Email</span>
                        <input
                            type="email"
                            value={email}
                            onChange={event => setEmail(event.target.value)}
                            className="input"
                            autoComplete="email"
                            required
                        />
                    </label>
                    <label className="flex flex-col gap-2 text-sm">
                        <span className="text-xs uppercase tracking-[0.18em] muted-copy">Password</span>
                        <input
                            type="password"
                            value={password}
                            onChange={event => setPassword(event.target.value)}
                            className="input"
                            autoComplete="current-password"
                            required
                        />
                    </label>

                    {error && (
                        <div className="theme-danger rounded-[20px] border px-4 py-3 text-sm">
                            {error}
                        </div>
                    )}

                    <button type="submit" className="btn" disabled={submitting}>
                        {submitting ? 'Signing in...' : 'Sign in'}
                    </button>
                </form>

                <a href="/" className="mt-5 inline-flex text-sm font-medium muted-copy hover:underline">
                    Continue without an account
                </a>
            </section>
        </div>
    )
}

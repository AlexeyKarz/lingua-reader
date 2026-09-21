import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../authContext'

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

                <form className="mt-5 space-y-4" onSubmit={submit}>
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
            </section>
        </div>
    )
}

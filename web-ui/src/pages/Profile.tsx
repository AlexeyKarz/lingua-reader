import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../authContext'

export default function ProfilePage() {
    const auth = useAuth()
    const navigate = useNavigate()
    const [loggingOut, setLoggingOut] = useState(false)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        document.title = 'Profile — Multilang Reader'
        return () => {
            document.title = 'Multilang Reader'
        }
    }, [])

    async function logout() {
        setLoggingOut(true)
        setError(null)
        try {
            await auth.logout()
            navigate('/', { replace: true })
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : 'Could not sign out.')
        } finally {
            setLoggingOut(false)
        }
    }

    if (auth.loading) {
        return (
            <section className="surface-panel rounded-[28px] px-5 py-6 md:px-7">
                <div className="soft-badge mb-2">Profile</div>
                <h2 className="theme-heading text-2xl font-semibold">Loading your profile…</h2>
            </section>
        )
    }

    if (!auth.authenticated || !auth.user) {
        return (
            <div className="mx-auto max-w-3xl">
                <section className="surface-panel profile-hero rounded-[28px] px-5 py-6 md:px-8 md:py-8">
                    <div className="soft-badge mb-3">Profile</div>
                    <h2 className="theme-heading text-3xl font-semibold">Reading works without an account</h2>
                    <p className="mt-3 max-w-2xl text-sm leading-7 muted-copy">
                        Sign in only if you want your vocabulary, reading position, and reader preferences to follow you across devices.
                    </p>
                    <div className="mt-6 flex flex-wrap gap-3">
                        <Link className="btn" to="/login?next=%2Fprofile">Sign in</Link>
                        <Link className="btn-secondary" to="/">Browse the catalog</Link>
                    </div>
                </section>

                <section className="profile-note mt-5">
                    <div>
                        <h3 className="theme-heading text-lg font-semibold">Curious how it works?</h3>
                        <p className="mt-1 text-sm leading-6 muted-copy">
                            See the reading features, ingestion pipeline, and application architecture.
                        </p>
                    </div>
                    <Link className="btn-secondary" to="/about">About the project</Link>
                </section>
            </div>
        )
    }

    const isAdmin = auth.hasRole('ADMIN')
    const displayName = auth.user.displayName?.trim() || auth.user.email

    return (
        <div className="mx-auto max-w-5xl">
            <section className="surface-panel profile-hero rounded-[28px] px-5 py-6 md:px-8 md:py-8">
                <div className="flex flex-col justify-between gap-5 sm:flex-row sm:items-center">
                    <div>
                        <div className="soft-badge mb-3">{isAdmin ? 'Administrator' : 'Profile'}</div>
                        <h2 className="theme-heading text-3xl font-semibold">{displayName}</h2>
                        <p className="mt-2 text-sm muted-copy">{auth.user.email}</p>
                    </div>
                    <div className="profile-avatar" aria-hidden="true">
                        {displayName.slice(0, 1).toUpperCase()}
                    </div>
                </div>
            </section>

            <section className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-label="Profile actions">
                <ProfileAction
                    to="/vocabulary"
                    eyebrow="Your library"
                    title="Vocabulary"
                    copy="Review saved words and passages, and continue from their original context."
                />
                {isAdmin && (
                    <ProfileAction
                        to="/admin"
                        eyebrow="Administration"
                        title="Admin panel"
                        copy="Inspect usage, system health, ingestion jobs, and automation access."
                    />
                )}
                {isAdmin && (
                    <ProfileAction
                        to="/admin/ingest"
                        eyebrow="Content"
                        title="Ingestion"
                        copy="Add editions, create AI translations, and monitor processing jobs."
                    />
                )}
            </section>

            <section className="profile-note mt-5">
                <div>
                    <h3 className="theme-heading text-lg font-semibold">Account</h3>
                    <p className="mt-1 text-sm leading-6 muted-copy">
                        Signing out does not remove books or locally saved guest data from this browser.
                    </p>
                    {error && <p className="theme-danger mt-2 text-sm">{error}</p>}
                </div>
                <button className="btn-secondary" type="button" onClick={() => void logout()} disabled={loggingOut}>
                    {loggingOut ? 'Signing out…' : 'Log out'}
                </button>
            </section>
        </div>
    )
}

function ProfileAction({ to, eyebrow, title, copy }: {to: string; eyebrow: string; title: string; copy: string}) {
    return (
        <Link className="card profile-action" to={to}>
            <span className="text-xs uppercase tracking-[0.18em] muted-copy">{eyebrow}</span>
            <span className="theme-heading mt-3 text-xl font-semibold">{title}</span>
            <span className="mt-2 text-sm leading-6 muted-copy">{copy}</span>
            <span className="profile-action-link mt-5">Open <span aria-hidden="true">→</span></span>
        </Link>
    )
}

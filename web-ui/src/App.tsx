import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './authContext'
import LocalStateImportBanner from './LocalStateImportBanner'
import {
    buildReaderPath,
    readLastReaderSession,
    resolveReaderPath,
    resolveReaderPathForSession,
    type CatalogEntry,
} from './readerNavigation'
import { fetchRecentUserReaderProgress } from './userReaderStateApi'

const THEME_KEY = 'mlr:theme'

export default function App() {
    const { pathname, search } = useLocation()
    const auth = useAuth()
    const [theme, setTheme] = useState<'light' | 'dark'>(() => {
        const stored = window.localStorage.getItem(THEME_KEY)
        return stored === 'dark' ? 'dark' : 'light'
    })
    const [readerPath, setReaderPath] = useState(() => {
        const lastSession = readLastReaderSession()
        return lastSession ? buildReaderPath(lastSession) : '/'
    })

    useEffect(() => {
        window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
    }, [pathname, search])

    useEffect(() => {
        document.documentElement.dataset.theme = theme
        window.localStorage.setItem(THEME_KEY, theme)
    }, [theme])

    useEffect(() => {
        let cancelled = false
        ;(async () => {
            let accountProgressPath: string | null = null
            if (!auth.loading && auth.authenticated) {
                try {
                    const recent = await fetchRecentUserReaderProgress(1)
                    const lastProgress = recent[0]
                    if (lastProgress) {
                        accountProgressPath = buildReaderPath({
                            bookId: lastProgress.bookId,
                            src: lastProgress.source,
                            tgt: lastProgress.target,
                        })
                    }
                } catch {
                    // Fall back to local navigation state and catalog defaults.
                }
            }

            try {
                const res = await fetch('/api/catalog/books')
                if (!res.ok) return
                const books = (await res.json()) as CatalogEntry[]
                if (!cancelled) {
                    const accountSessionPath = accountProgressPath
                        ? resolveAccountProgressPath(books, accountProgressPath)
                        : null
                    setReaderPath(accountSessionPath ?? resolveReaderPath(books) ?? '/')
                }
            } catch {
                if (!cancelled && accountProgressPath) {
                    setReaderPath(accountProgressPath)
                }
                // Otherwise keep the saved-session fallback if the catalog cannot be loaded.
            }
        })()
        return () => {
            cancelled = true
        }
    }, [auth.authenticated, auth.loading, auth.user?.id, pathname])

    const NavLink = ({ to, label, activePath }: {to:string; label:string; activePath?: string}) => (
        <Link
            to={to}
            className={`nav-pill ${pathname === (activePath ?? to) ? 'nav-pill-active' : ''}`}
        >
            {label}
        </Link>
    )
    const isReaderRoute = pathname === '/reader'
    const headerPositionClass = isReaderRoute ? '' : 'md:sticky md:top-3'

    return (
        <div className="page-shell">
            <div className="mx-auto max-w-7xl px-4 py-4 md:px-6 md:py-6">
                <header className={`surface-panel z-40 mb-5 rounded-[22px] px-3 py-3 md:px-4 ${headerPositionClass} ${isReaderRoute ? 'reader-app-header' : ''}`}>
                    <div className={isReaderRoute ? 'reader-app-header-inner' : 'flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between'}>
                        <Link to="/" className="min-w-0">
                            <h1 className={isReaderRoute
                                ? 'reader-app-title theme-heading text-xl font-semibold tracking-tight md:text-2xl'
                                : 'theme-heading text-2xl font-semibold tracking-tight md:text-[1.65rem]'}
                            >
                                Multilang Reader
                            </h1>
                            <p className={isReaderRoute
                                ? 'hidden'
                                : 'mt-0.5 hidden max-w-xl text-xs leading-5 muted-copy sm:block'}
                            >
                                Read originals and translations side by side, then save lookups for review.
                            </p>
                        </Link>
                        <div className={isReaderRoute
                            ? 'reader-app-header-actions'
                            : 'flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center sm:justify-end'}
                        >
                            <nav className="nav-shell min-w-0 sm:w-auto">
                                <NavLink to="/" label="Catalog" />
                                <NavLink to={readerPath} activePath="/reader" label="Reader" />
                                <NavLink to="/vocabulary" label="Vocabulary" />
                                {auth.hasRole('ADMIN') && <NavLink to="/admin" label="Admin" />}
                                {auth.authenticated ? (
                                    <button
                                        type="button"
                                        className="nav-pill"
                                        onClick={() => void auth.logout()}
                                    >
                                        Logout
                                    </button>
                                ) : (
                                    <NavLink to="/login" label="Login" />
                                )}
                            </nav>
                            <button
                                type="button"
                                className={isReaderRoute ? 'theme-toggle' : 'theme-toggle self-start sm:self-auto'}
                                onClick={() => setTheme(current => current === 'light' ? 'dark' : 'light')}
                                aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                                title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                            >
                                <span aria-hidden="true">{theme === 'light' ? '☾' : '☀'}</span>
                            </button>
                        </div>
                    </div>
                </header>
                <LocalStateImportBanner />
                <main>
                    <Outlet />
                </main>
            </div>
        </div>
    )
}

function resolveAccountProgressPath(books: CatalogEntry[], readerPath: string) {
    const url = new URL(readerPath, window.location.origin)
    const bookId = url.searchParams.get('bookId')
    const src = url.searchParams.get('src')
    const tgt = url.searchParams.get('tgt')
    if (!bookId || !src || !tgt) return null
    return resolveReaderPathForSession(books, { bookId, src, tgt })
}

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

    const NavLink = ({ to, label, activePath, active }: {to:string; label:string; activePath?: string; active?: boolean}) => (
        <Link
            to={to}
            className={(active ?? pathname === (activePath ?? to)) ? 'active' : ''}
            aria-current={(active ?? pathname === (activePath ?? to)) ? 'page' : undefined}
        >
            {label}
        </Link>
    )
    return (
        <div className="jackets-shell">
            <header className="jackets-header">
                <Link to="/" className="jackets-brand">
                    <span className="publisher-mark" aria-hidden="true">m/r</span>
                    Multilang Reader
                </Link>
                <nav aria-label="Main navigation">
                    <NavLink to="/" label="Catalog" />
                    <NavLink to={readerPath} activePath="/reader" label="Reader" />
                    <NavLink
                        to="/profile"
                        label="Profile"
                        active={['/profile', '/login', '/vocabulary'].includes(pathname) || pathname.startsWith('/admin')}
                    />
                </nav>
                <button
                    type="button"
                    className="appearance"
                    onClick={() => setTheme(current => current === 'light' ? 'dark' : 'light')}
                    aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                    title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                >
                    {theme === 'light' ? 'Dark mode' : 'Light mode'}
                </button>
            </header>
            <main className={`jackets-main route-${pathname.split('/')[1] || 'catalog'}`}>
                <LocalStateImportBanner />
                <Outlet />
            </main>
            <footer className="jackets-footer">
                <span>Multilang Reader</span>
                <Link to="/about">About the project</Link>
                <span className="footer-end">Literature, across languages.</span>
            </footer>
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

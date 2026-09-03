import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { buildReaderPath, readLastReaderSession, resolveReaderPath, type CatalogEntry } from './readerNavigation'

const THEME_KEY = 'mlr:theme'

export default function App() {
    const { pathname, search } = useLocation()
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
        ;(async () => {
            try {
                const res = await fetch('/api/catalog/books')
                if (!res.ok) return
                const books = (await res.json()) as CatalogEntry[]
                setReaderPath(resolveReaderPath(books) ?? '/')
            } catch {
                // Keep the saved-session fallback if the catalog cannot be loaded.
            }
        })()
    }, [pathname])

    const NavLink = ({ to, label, activePath }: {to:string; label:string; activePath?: string}) => (
        <Link
            to={to}
            className={`nav-pill ${pathname === (activePath ?? to) ? 'nav-pill-active' : ''}`}
        >
            {label}
        </Link>
    )

    return (
        <div className="page-shell">
            <div className="mx-auto max-w-7xl px-4 py-4 md:px-6 md:py-6">
                <header className="surface-panel sticky top-4 z-40 mb-6 rounded-[24px] px-4 py-3 md:px-5">
                    <div className="flex flex-col gap-3">
                        <div className="flex items-start justify-between gap-3">
                            <div className="min-w-0">
                                <h1 className="theme-heading text-3xl font-semibold tracking-tight md:text-4xl">
                                    Multilang Reader
                                </h1>
                                <p className="mt-1 max-w-2xl text-sm leading-6 muted-copy">
                                    Read originals and translations side by side, then save lookups for review.
                                </p>
                            </div>
                            <button
                                type="button"
                                className="theme-toggle"
                                onClick={() => setTheme(current => current === 'light' ? 'dark' : 'light')}
                                aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                                title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                            >
                                <span aria-hidden="true">{theme === 'light' ? '☾' : '☀'}</span>
                            </button>
                        </div>
                        <nav className="nav-shell">
                            <NavLink to="/" label="Catalog" />
                            <NavLink to={readerPath} activePath="/reader" label="Reader" />
                            <NavLink to="/vocabulary" label="Vocabulary" />
                        </nav>
                    </div>
                </header>

                <main>
                    <Outlet />
                </main>
            </div>
        </div>
    )
}

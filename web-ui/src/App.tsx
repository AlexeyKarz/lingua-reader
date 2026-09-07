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
                <main>
                    <Outlet />
                </main>
            </div>
        </div>
    )
}

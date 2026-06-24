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
                <header className="surface-panel sticky top-4 z-40 mb-6 rounded-[32px] px-5 py-4 md:px-6">
                    <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                        <div>
                            <h1 className="theme-heading text-3xl font-semibold tracking-tight md:text-4xl">
                                Multilang Reader
                            </h1>
                            <p className="mt-1 max-w-2xl text-sm leading-6 muted-copy md:text-[15px]">
                                Read original and translated editions side by side, inspect aligned passages,
                                and ask for literal translations or context when you need them.
                            </p>
                        </div>

                        <div className="flex flex-col items-start gap-3 md:items-end">
                            <div className="flex flex-wrap items-center gap-2">
                                <nav className="surface-panel-strong flex flex-wrap gap-2 rounded-full p-1.5">
                                    <NavLink to="/" label="Catalog" />
                                    <NavLink to={readerPath} activePath="/reader" label="Reader" />
                                </nav>
                                <button
                                    type="button"
                                    className="theme-toggle"
                                    onClick={() => setTheme(current => current === 'light' ? 'dark' : 'light')}
                                    aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                                    title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
                                >
                                    <span aria-hidden="true">{theme === 'light' ? '☾' : '☀'}</span>
                                    <span>{theme === 'light' ? 'Dark' : 'Light'}</span>
                                </button>
                            </div>
                            <div className="text-xs uppercase tracking-[0.18em] muted-copy">
                                {pathname === '/reader' ? 'Reading view' : 'Library view'}
                            </div>
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

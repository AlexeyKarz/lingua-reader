import { useEffect } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'

export default function App() {
    const { pathname, search } = useLocation()

    useEffect(() => {
        window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
    }, [pathname, search])

    const NavLink = ({ to, label }: {to:string; label:string}) => (
        <Link
            to={to}
            className={`nav-pill ${pathname === to ? 'nav-pill-active' : ''}`}
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
                            <h1 className="text-3xl font-semibold tracking-tight text-slate-900 md:text-4xl">
                                Multilang Reader
                            </h1>
                            <p className="mt-1 max-w-2xl text-sm leading-6 muted-copy md:text-[15px]">
                                Read original and translated editions side by side, inspect aligned passages,
                                and ask for literal translations or context when you need them.
                            </p>
                        </div>

                        <div className="flex flex-col items-start gap-3 md:items-end">
                            <nav className="surface-panel-strong flex flex-wrap gap-2 rounded-full p-1.5">
                                <NavLink to="/" label="Catalog" />
                                <NavLink to="/reader" label="Reader" />
                            </nav>
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

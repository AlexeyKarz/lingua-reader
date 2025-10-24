import { Link, Outlet, useLocation } from 'react-router-dom'

export default function App() {
    const { pathname } = useLocation()
    const NavLink = ({ to, label }: {to:string; label:string}) => (
        <Link to={to}
              className={`px-3 py-1.5 rounded ${pathname===to ? 'bg-black text-white' : 'hover:bg-black/10'}`}>
            {label}
        </Link>
    )

    return (
        <div className="mx-auto max-w-6xl p-6">
            <header className="mb-4 flex items-end justify-between">
                <div>
                    <h1 className="text-2xl font-semibold">Multilang Reader — MVP</h1>
                    <p className="text-sm opacity-70">Catalog → Reader with selection popovers</p>
                </div>
                <nav className="flex gap-2">
                    <NavLink to="/" label="Catalog" />
                    <NavLink to="/reader" label="Reader" />
                </nav>
            </header>
            <Outlet />
        </div>
    )
}

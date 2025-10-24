import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'

type Book = { id: string; title: string; langs: string[] }

export default function Catalog() {
    const nav = useNavigate()
    const { data, isLoading, error } = useQuery({
        queryKey: ['books'],
        queryFn: async () => {
            const r = await fetch('/api/catalog/books')
            if (!r.ok) throw new Error('Failed to load')
            return (await r.json()) as Book[]
        }
    })

    if (isLoading) return <p>Loading…</p>
    if (error)     return <p className="text-red-600">Failed to load catalog.</p>

    return (
        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {data!.map(b => (
                <button key={b.id} className="card text-left p-3"
                        onClick={() => nav(`/reader?bookId=${b.id}&src=${b.langs[0]}&tgt=${b.langs[1] ?? 'en'}`)}>
                    <div className="text-lg font-semibold">{b.title}</div>
                    <div className="text-sm opacity-70">{b.langs.join(' · ')}</div>
                    <div className="mt-2"><span className="btn">Open</span></div>
                </button>
            ))}
        </div>
    )
}

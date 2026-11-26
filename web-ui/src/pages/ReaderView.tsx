import { type CSSProperties, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import BookJacket from '../BookJacket'
import { languageLabel } from '../languageLabel'
import { buildReaderPath, getSourceLang, type CatalogEntry } from '../readerNavigation'
import Reader from './Reader'

const FONT_KEY = 'mlr:reader-typeface'
const SIZE_KEY = 'mlr:reader-font-size'

/** Presentation controls only; Reader owns pagination, selection, alignment and account state. */
export default function ReaderView() {
    const [books, setBooks] = useState<CatalogEntry[]>([])
    const [params] = useSearchParams()
    const navigate = useNavigate()
    const [settings, setSettings] = useState(false)
    const [font, setFont] = useState(() => {
        const saved = localStorage.getItem(FONT_KEY)
        return saved === 'classic' || saved === 'sans' ? saved : 'literary'
    })
    const [fontSize, setFontSize] = useState(() => {
        const saved = Number(localStorage.getItem(SIZE_KEY))
        return Number.isFinite(saved) && saved >= 14 && saved <= 25 ? saved : 20
    })
    useEffect(() => {
        const controller = new AbortController()
        void fetch('/api/catalog/books', { signal: controller.signal })
            .then(async response => { if (response.ok) setBooks(await response.json() as CatalogEntry[]) })
            .catch(() => { /* Reader displays its own loading/error state. */ })
        return () => controller.abort()
    }, [])
    const book = books.find(entry => String(entry.workId) === params.get('bookId'))
    const src = params.get('src') || (book ? getSourceLang(book) : '')
    const tgt = params.get('tgt') || ''

    function chooseBook(id: string) {
        const next = books.find(entry => String(entry.workId) === id)
        if (!next) return
        const source = getSourceLang(next)
        const target = next.langs.find(lang => lang === tgt && lang !== source) || next.langs.find(lang => lang !== source)
        if (target) navigate(buildReaderPath({ bookId: id, src: source, tgt: target }))
    }

    return (
        <div className={`reading-${font}`} style={{ '--reading-size': `${fontSize}px` } as CSSProperties}>
            {book && (
                <section className="reader-context" aria-label="Reading editions">
                    <BookJacket book={book} small />
                    <Link to="/">← Library</Link>
                    <label>Work
                        <select aria-label="Reading work" value={book.workId} onChange={e => chooseBook(e.target.value)}>
                            {books.filter(entry => entry.langs.length > 1).map(entry => <option key={entry.workId} value={entry.workId}>{entry.title}</option>)}
                        </select>
                    </label>
                    <label>Alongside
                        <select aria-label="Reading translation" value={tgt} onChange={e => navigate(buildReaderPath({ bookId: String(book.workId), src, tgt: e.target.value }))}>
                            {book.langs.filter(lang => lang !== src).map(lang => <option key={lang} value={lang}>{languageLabel(lang)}</option>)}
                        </select>
                    </label>
                    <button className="reading-appearance" type="button" aria-expanded={settings} aria-controls="reading-type-settings" onClick={() => setSettings(value => !value)}>Aa · Reading type</button>
                </section>
            )}
            {settings && (
                <section className="type-settings" id="reading-type-settings" aria-label="Reading type">
                    <label>Typeface
                        <select aria-label="Typeface" value={font} onChange={e => { setFont(e.target.value); localStorage.setItem(FONT_KEY, e.target.value) }}>
                            <option value="literary">Literary · Charter</option>
                            <option value="classic">Classic · Georgia</option>
                            <option value="sans">Clean · System sans</option>
                        </select>
                    </label>
                    <label>Size
                        <input aria-label="Reading text size" type="range" min="14" max="25" value={fontSize} onChange={e => { setFontSize(Number(e.target.value)); localStorage.setItem(SIZE_KEY, e.target.value) }} />
                        <output>{fontSize}px</output>
                    </label>
                    <span className="muted-copy">Saved on this device.</span>
                </section>
            )}
            <Reader typographyKey={`${font}:${fontSize}`} />
        </div>
    )
}

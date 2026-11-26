import { getSourceLang, type CatalogEntry } from './readerNavigation'

export default function BookJacket({ book, small = false }: { book: CatalogEntry; small?: boolean }) {
    return (
        <div className={`jacket-cover jacket-${book.workId % 3} ${small ? 'jacket-small' : ''}`} aria-hidden="true">
            <span className="jacket-author">{book.author}</span>
            <span className="jacket-name">{book.title}</span>
            <span className="jacket-glyph">{Array.from(book.title)[0]}</span>
            <span className="jacket-footer">{getSourceLang(book).toUpperCase()}<span>{book.year}</span></span>
        </div>
    )
}

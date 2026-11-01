import { useEffect } from 'react'
import { Link } from 'react-router-dom'

const readerFeatures = [
    ['Parallel reading', 'Read an original and a translation side by side, with sentence-level links between editions.'],
    ['Reading assistance', 'Select a word or passage for dictionary details, literal translation, literary translation, or a short explanation.'],
    ['Built for literature', 'Keep chapters, paragraphs, verse sections, and speaker cues meaningful across prose, poetry, and plays.'],
    ['A reader that remembers', 'Save vocabulary and reading progress locally as a guest, or sign in to carry them across devices.'],
]

const ingestionSteps = [
    ['01', 'Import', 'EPUB, TXT, or PDF'],
    ['02', 'Extract', 'Text layer or Tesseract OCR'],
    ['03', 'Structure', 'Chapters, paragraphs, lines, roles'],
    ['04', 'Enrich', 'Dictionary forms and base lemmas'],
    ['05', 'Embed', 'Multilingual sentence vectors'],
    ['06', 'Align', 'Source to every target edition'],
    ['07', 'Read', 'Paginated parallel editions'],
]

export default function AboutPage() {
    useEffect(() => {
        document.title = 'About — Multilang Reader'
        return () => {
            document.title = 'Multilang Reader'
        }
    }, [])

    return (
        <div className="about-page space-y-6">
            <section className="surface-panel about-hero overflow-hidden rounded-[30px] px-5 py-8 md:px-10 md:py-12">
                <div className="about-hero-copy">
                    <div className="soft-badge mb-4">About Multilang Reader</div>
                    <h2 className="theme-heading max-w-3xl text-4xl font-semibold leading-tight md:text-5xl">
                        Read across languages without losing your place.
                    </h2>
                    <p className="mt-5 max-w-2xl text-base leading-8 muted-copy">
                        Multilang Reader is a parallel literature reader for exploring an original beside one or more translations. Public reading stays open; accounts add synchronisation, while administrative tools stay separate.
                    </p>
                    <div className="mt-7 flex flex-wrap gap-3">
                        <Link className="btn" to="/">Browse books</Link>
                        <Link className="btn-secondary" to="/reader">Open the reader</Link>
                    </div>
                </div>
                <div className="about-book-visual" aria-hidden="true">
                    <div className="about-page-sheet about-page-sheet-left">
                        <span>Original</span>
                        <i /><i /><i /><i />
                    </div>
                    <div className="about-page-sheet about-page-sheet-right">
                        <span>Translation</span>
                        <i /><i /><i /><i />
                    </div>
                </div>
            </section>

            <section className="about-section">
                <div className="about-section-heading">
                    <div className="text-xs uppercase tracking-[0.2em] muted-copy">For readers</div>
                    <h2 className="theme-heading mt-2 text-3xl font-semibold">One text, several ways in</h2>
                    <p className="mt-3 max-w-2xl text-sm leading-7 muted-copy">
                        The interface is designed to help when you need it and stay out of the way when you do not.
                    </p>
                </div>
                <div className="mt-6 grid gap-4 md:grid-cols-2">
                    {readerFeatures.map(([title, copy], index) => (
                        <article className="card about-feature" key={title}>
                            <span className="about-feature-number">{String(index + 1).padStart(2, '0')}</span>
                            <div>
                                <h3 className="theme-heading text-xl font-semibold">{title}</h3>
                                <p className="mt-2 text-sm leading-7 muted-copy">{copy}</p>
                            </div>
                        </article>
                    ))}
                </div>
            </section>

            <section className="about-section surface-panel rounded-[30px] px-5 py-7 md:px-8 md:py-9">
                <div className="about-section-heading">
                    <div className="text-xs uppercase tracking-[0.2em] muted-copy">Behind the reader</div>
                    <h2 className="theme-heading mt-2 text-3xl font-semibold">From a book file to aligned editions</h2>
                    <p className="mt-3 max-w-3xl text-sm leading-7 muted-copy">
                        Ingestion is a repeatable pipeline rather than a single file upload. Expensive stages are persisted, so alignment can be corrected later without extracting or embedding the whole book again.
                    </p>
                </div>

                <ol className="about-flow mt-7" aria-label="Book ingestion pipeline">
                    {ingestionSteps.map(([number, title, copy]) => (
                        <li className="about-flow-step" key={number}>
                            <span className="about-flow-number">{number}</span>
                            <strong>{title}</strong>
                            <span>{copy}</span>
                        </li>
                    ))}
                </ol>

                <div className="mt-7 grid gap-4 lg:grid-cols-3">
                    <article className="subtle-card rounded-[24px] p-5">
                        <h3 className="theme-heading text-lg font-semibold">Extraction that fits the source</h3>
                        <p className="mt-2 text-sm leading-7 muted-copy">
                            Apache Tika and PDFBox read EPUB and text-bearing PDF files. Image pages can use open-source Tesseract OCR, followed by optional cleanup for noisy scans.
                        </p>
                    </article>
                    <article className="subtle-card rounded-[24px] p-5">
                        <h3 className="theme-heading text-lg font-semibold">Literary structure</h3>
                        <p className="mt-2 text-sm leading-7 muted-copy">
                            OpenNLP sentence models handle prose, while play-aware parsing preserves speakers and verse-like lines. AI translation is optional and records generated editions as such.
                        </p>
                    </article>
                    <article className="subtle-card rounded-[24px] p-5">
                        <h3 className="theme-heading text-lg font-semibold">Reusable alignment</h3>
                        <p className="mt-2 text-sm leading-7 muted-copy">
                            Multilingual sentence embeddings and dynamic programming support missing, split, and merged passages—not only one-to-one sentence pairs.
                        </p>
                    </article>
                </div>
            </section>

            <section className="about-section">
                <div className="about-section-heading">
                    <div className="text-xs uppercase tracking-[0.2em] muted-copy">Application architecture</div>
                    <h2 className="theme-heading mt-2 text-3xl font-semibold">A modular full-stack pet project</h2>
                    <p className="mt-3 max-w-3xl text-sm leading-7 muted-copy">
                        The production application is deliberately compact: one deployable backend with clear modules, a separate browser client, and offline workers for the computationally heavier book pipeline.
                    </p>
                </div>

                <div className="architecture-map mt-7" role="img" aria-label="React browser application connects to a Spring Boot API, which connects to PostgreSQL and background ingestion workers, with optional external language providers">
                    <ArchitectureNode eyebrow="Browser" title="React + Vite" copy="Catalog · Reader · Profile · Admin" tone="blue" />
                    <span className="architecture-arrow" aria-hidden="true">→</span>
                    <ArchitectureNode eyebrow="Application" title="Spring Boot" copy="Catalog · Reader · Auth · Translation · Ingestion" tone="violet" />
                    <span className="architecture-arrow" aria-hidden="true">→</span>
                    <div className="architecture-stack">
                        <ArchitectureNode eyebrow="Data" title="PostgreSQL + pgvector" copy="Books · users · vectors · alignments" tone="green" />
                        <ArchitectureNode eyebrow="Offline workers" title="Python pipeline" copy="sentence-transformers · alignment" tone="amber" />
                    </div>
                </div>

                <div className="architecture-satellite mt-4">
                    <span className="text-xs uppercase tracking-[0.18em] muted-copy">Optional providers</span>
                    <strong>OpenAI · Google Translate · Ollama</strong>
                    <span className="text-sm muted-copy">translation and explanation behind caching and rate limits</span>
                </div>

                <div className="mt-7 grid gap-4 md:grid-cols-3">
                    <TechNote title="Safe public access" copy="Guests can read and use limited assistance. Google OIDC identifies users; roles protect ingestion, metrics, and automation tokens." />
                    <TechNote title="Observable operations" copy="Ingestion jobs expose progress and errors, while administrative metrics track usage, latency, failures, and system health." />
                    <TechNote title="Production-shaped" copy="Docker, Nginx, CI deployment, CSRF protection, scoped tokens, and database migrations keep the project close to a real service." />
                </div>
            </section>
        </div>
    )
}

function ArchitectureNode({ eyebrow, title, copy, tone }: {eyebrow: string; title: string; copy: string; tone: string}) {
    return (
        <div className={`architecture-node architecture-node-${tone}`}>
            <span>{eyebrow}</span>
            <strong>{title}</strong>
            <small>{copy}</small>
        </div>
    )
}

function TechNote({ title, copy }: {title: string; copy: string}) {
    return (
        <article className="about-tech-note">
            <h3 className="theme-heading text-lg font-semibold">{title}</h3>
            <p className="mt-2 text-sm leading-7 muted-copy">{copy}</p>
        </article>
    )
}

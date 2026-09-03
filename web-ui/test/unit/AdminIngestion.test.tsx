import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import AdminIngestionPage from '../../src/pages/AdminIngestion'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function installFetchMock() {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/catalog/books') {
      return jsonResponse([
        {
          workId: 4,
          title: 'Existing Book',
          author: 'Existing Author',
          description: null,
          year: 1901,
          langs: ['en', 'de'],
          originalLang: 'en',
        },
      ])
    }
    if (url === '/api/admin/ingest/jobs/upload') {
      return jsonResponse({ jobId: 7, status: 'PENDING', statusUrl: '/api/admin/ingest/jobs/7' }, 202)
    }
    if (url === '/api/admin/ingest/jobs/generate-literary') {
      return jsonResponse({ jobId: 9, status: 'PENDING', statusUrl: '/api/admin/ingest/jobs/9' }, 202)
    }
    if (url === '/api/admin/ingest/jobs/7') {
      return jsonResponse({
        id: 7,
        status: 'SUCCEEDED',
        step: 'done',
        message: 'Imported',
        error: null,
        workTitle: 'Plain Book',
        lang: 'de',
        fileSpec: 'file:/tmp/plain-book.epub',
        workId: 4,
        editionId: 8,
        chapters: 1,
        paragraphs: 2,
        sentences: 3,
      })
    }
    if (url === '/api/admin/ingest/jobs/9') {
      return jsonResponse({
        id: 9,
        status: 'SUCCEEDED',
        step: 'done',
        message: 'Generated',
        error: null,
        workTitle: 'Existing Book',
        lang: 'fr',
        fileSpec: 'ai-literary://en/fr/Existing Book',
        workId: 4,
        editionId: 11,
        chapters: 1,
        paragraphs: 2,
        sentences: 4,
      })
    }
    throw new Error(`Unexpected fetch: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function submitForm() {
  const submit = screen.getByRole('button', { name: /^Start ingestion$/i })
  const form = submit.closest('form')
  if (!form) throw new Error('Start ingestion button is not inside a form')
  fireEvent.submit(form)
}

describe('AdminIngestionPage', () => {
  it('submits txt uploads with normalized metadata and bearer token', async () => {
    const fetchMock = installFetchMock()
    render(<AdminIngestionPage />)

    fireEvent.change(screen.getByLabelText(/^Admin token$/i), { target: { value: 'secret' } })
    fireEvent.change(screen.getByLabelText(/^Title$/i), { target: { value: 'Plain Book' } })
    fireEvent.change(screen.getByLabelText(/^Author$/i), { target: { value: 'Writer' } })
    fireEvent.change(screen.getByLabelText(/^Original language$/i), { target: { value: 'EN' } })
    fireEvent.change(screen.getByLabelText(/^Publication year$/i), { target: { value: '2026' } })
    fireEvent.change(screen.getByLabelText(/^Description$/i), { target: { value: 'Short description' } })
    fireEvent.change(screen.getByLabelText(/^Language$/i), { target: { value: 'DE' } })
    fireEvent.change(screen.getByLabelText(/^Publisher$/i), { target: { value: 'Press' } })
    fireEvent.change(screen.getByLabelText(/^Translator$/i), { target: { value: 'Translator' } })

    const file = new File(['Chapter 1\n\nHello world.'], 'plain-book.txt', { type: 'text/plain' })
    fireEvent.change(screen.getByLabelText(/^EPUB or TXT file$/i), { target: { files: [file] } })
    submitForm()

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/admin/ingest/jobs/upload',
        expect.objectContaining({ method: 'POST' })
      )
    })

    const uploadCall = fetchMock.mock.calls.find(([input]) => String(input) === '/api/admin/ingest/jobs/upload')
    expect(uploadCall).toBeDefined()
    const init = uploadCall?.[1] as RequestInit
    expect(init.headers).toEqual({ Authorization: 'Bearer secret' })

    const form = init.body as FormData
    expect(form.get('workTitle')).toBe('Plain Book')
    expect(form.get('author')).toBe('Writer')
    expect(form.get('originalLang')).toBe('en')
    expect(form.get('year')).toBe('2026')
    expect(form.get('lang')).toBe('de')
    expect(form.get('publisher')).toBe('Press')
    expect(form.get('translator')).toBe('Translator')
    expect(form.get('description')).toBe('Short description')
    expect(form.get('file')).toBe(file)

    expect(await screen.findByText('SUCCEEDED')).toBeTruthy()
    expect(screen.getByText('3 sentences')).toBeTruthy()
  })

  it('rejects unsupported file extensions before upload', async () => {
    const fetchMock = installFetchMock()
    render(<AdminIngestionPage />)

    fireEvent.change(screen.getByLabelText(/^Admin token$/i), { target: { value: 'secret' } })
    fireEvent.change(screen.getByLabelText(/^Title$/i), { target: { value: 'Plain Book' } })
    fireEvent.change(screen.getByLabelText(/^Original language$/i), { target: { value: 'en' } })
    fireEvent.change(screen.getByLabelText(/^Language$/i), { target: { value: 'de' } })
    fireEvent.change(screen.getByLabelText(/^EPUB or TXT file$/i), {
      target: { files: [new File(['bad'], 'plain-book.pdf', { type: 'application/pdf' })] },
    })
    submitForm()

    expect(await screen.findByText('Only EPUB and TXT files are supported.')).toBeTruthy()
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/admin/ingest/jobs/upload')).toHaveLength(0)
  })

  it('submits generated literary editions against an existing source language', async () => {
    const fetchMock = installFetchMock()
    render(<AdminIngestionPage />)

    await screen.findByText('Existing Book')
    fireEvent.change(screen.getByLabelText(/^Admin token$/i), { target: { value: 'secret' } })
    fireEvent.change(screen.getByLabelText(/^Existing work$/i), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'AI' }))
    fireEvent.change(screen.getByLabelText(/^Language$/i), { target: { value: 'FR' } })
    fireEvent.change(screen.getByLabelText(/^Publisher$/i), { target: { value: 'Generated Press' } })
    fireEvent.change(screen.getByLabelText(/^Max source chars$/i), { target: { value: '25000' } })
    fireEvent.change(screen.getByLabelText(/^Chunk chars$/i), { target: { value: '1200' } })
    fireEvent.change(screen.getByLabelText(/^Context chars$/i), { target: { value: '300' } })
    submitForm()

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/admin/ingest/jobs/generate-literary',
        expect.objectContaining({ method: 'POST' })
      )
    })

    const generatedCall = fetchMock.mock.calls.find(([input]) => String(input) === '/api/admin/ingest/jobs/generate-literary')
    expect(generatedCall).toBeDefined()
    const init = generatedCall?.[1] as RequestInit
    expect(init.headers).toEqual({
      Authorization: 'Bearer secret',
      'Content-Type': 'application/json',
    })
    expect(JSON.parse(String(init.body))).toEqual({
      workTitle: 'Existing Book',
      sourceLang: 'en',
      targetLang: 'fr',
      year: 1901,
      publisher: 'Generated Press',
      translator: 'AI literary translation',
      bodyOnly: true,
      replaceExisting: true,
      maxSourceChars: 25000,
      chunkMaxChars: 1200,
      contextChars: 300,
    })
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/admin/ingest/jobs/upload')).toHaveLength(0)
    expect(await screen.findByText('Generated')).toBeTruthy()
    expect(screen.getByText('4 sentences')).toBeTruthy()
  })
})

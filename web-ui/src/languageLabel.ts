const names = new Intl.DisplayNames(['en'], { type: 'language' })

export function languageLabel(code: string): string {
    try {
        return names.of(code) || code.toUpperCase()
    } catch {
        return code.toUpperCase()
    }
}

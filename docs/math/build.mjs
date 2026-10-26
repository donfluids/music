// Builds KeyCatch-math.pdf from keycatch-math.html.
//
//   cd docs/math && npm install && npm run build
//
// Equations are written in the HTML as $$…$$ (display) and \(…\) (inline). KaTeX turns them
// into HTML here, before the browser sees the page, so nothing runs in the page itself; then
// headless Chromium prints it to A4. Set CHROMIUM to use a particular browser binary.

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)
const katex = require('katex')
const { chromium } = require('playwright')

const macros = {
  '\\clamp': '\\operatorname{clamp}',
  '\\argmax': '\\operatorname*{arg\\,max}',
  '\\median': '\\operatorname{median}',
  '\\round': '\\operatorname{round}',
  '\\wrap': '\\operatorname{wrap}',
  '\\pc': '\\operatorname{pc}',
}

const render = (tex, displayMode) =>
  katex.renderToString(tex, { displayMode, throwOnError: true, strict: 'ignore', macros, trust: false })

let html = readFileSync(join(here, 'keycatch-math.html'), 'utf8')
// Figures are kept as separate SVG files and inlined here.
html = html.replace(/<!--include:(.+?)-->/g, (_, file) => readFileSync(join(here, file.trim()), 'utf8'))
// Headings use the app's own typeface.
const fontDir = join(here, '..', '..', 'app', 'src', 'main', 'res', 'font')
html = html.replaceAll('{{FONT_DIR}}', pathToFileURL(fontDir).href)
let count = 0
html = html.replace(/\$\$([\s\S]+?)\$\$/g, (_, tex) => { count++; return render(tex.trim(), true) })
html = html.replace(/\\\(([\s\S]+?)\\\)/g, (_, tex) => { count++; return render(tex.trim(), false) })

// KaTeX's stylesheet, with its font URLs pointed at the installed package.
const katexDir = dirname(require.resolve('katex/package.json'))
const css = readFileSync(join(katexDir, 'dist', 'katex.min.css'), 'utf8')
  .replace(/url\(fonts\//g, `url(${pathToFileURL(join(katexDir, 'dist', 'fonts')).href}/`)
html = html.replace('</head>', `<style>${css}</style>\n</head>`)

const out = join(here, 'build')
mkdirSync(out, { recursive: true })
const rendered = join(out, 'rendered.html')
writeFileSync(rendered, html)

const executablePath = process.env.CHROMIUM ??
  ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome'].find(existsSync)
const browser = await chromium.launch(executablePath ? { executablePath } : {})
const page = await browser.newPage()
await page.goto(pathToFileURL(rendered).href, { waitUntil: 'networkidle' })
await page.evaluate(() => document.fonts.ready)
await page.pdf({
  path: join(here, 'KeyCatch-math.pdf'),
  format: 'A4',
  printBackground: true,
  margin: { top: '20mm', bottom: '20mm', left: '18mm', right: '18mm' },
  displayHeaderFooter: true,
  headerTemplate: '<div></div>',
  footerTemplate:
    '<div style="font: 8px sans-serif; color: #888; width: 100%; padding: 0 18mm; display: flex; justify-content: space-between;">' +
    '<span>The mathematics of KeyCatch</span><span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>',
})
await browser.close()
console.log(`Rendered ${count} equations into KeyCatch-math.pdf`)

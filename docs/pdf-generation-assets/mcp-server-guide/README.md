# MCP Server Guide — PDF generation assets

Source material for **`docs/MCP_SERVER_GUIDE.pdf`**. The PDF is a build output:
edit the files here and regenerate it. Do not edit the PDF directly.

## Regenerating

```bash
cd docs/pdf-generation-assets/mcp-server-guide
npm install        # first time only -- pulls puppeteer-core, no bundled browser
npm run build      # writes ../../MCP_SERVER_GUIDE.pdf
```

`build-pdf.mjs` drives whatever Chrome, Chromium or Edge is already installed;
it looks in the usual per-platform locations. Override with:

```bash
CHROME_PATH=/path/to/chrome npm run build
```

Chrome is used rather than a dedicated PDF toolchain because it is already on
every machine this project is developed on, it supports the paged-media CSS the
guide is written against, and it needs no native build step.

## Files

| File | Purpose |
|---|---|
| `guide.html` | The document itself — all the prose, tables and code samples. Open it directly in a browser to preview. |
| `guide.css` | Print stylesheet: page geometry, headings, tables, callouts, tool cards, risk badges. Authored for paged media, not screen. |
| `build-pdf.mjs` | Renders `guide.html` to the PDF: Letter, 16 mm margins, running footer with page numbers. |
| `diagrams/*.svg` | Hand-authored figures. Inlined into the DOM at print time so they reach the PDF as vector art rather than a bitmap — see below. |
| `assets/logo.png` | Cover mark, downscaled from `src/main/resources/micaminecraftlauncher.png`. |
| `package.json` | One dependency: `puppeteer-core`. |

## Conventions worth keeping

- **Diagrams stay as separate SVG files.** `guide.html` references them with
  `<img class="diagram" src="diagrams/…">` so the document previews correctly in
  a browser; `build-pdf.mjs` swaps each one for its inline markup just before
  printing. An `<img src="*.svg">` is rasterised by Chrome — the file roughly
  triples in size and the text inside a diagram stops being selectable and
  searchable.
- **Chapters start on a new page** (`section.chapter`). After an edit, check that
  a chapter's last section did not spill a line or two onto an otherwise empty
  page; trimming a sentence earlier in the chapter is usually the fix.
- **Tool entries are `div.tool` cards.** Add `class="tool tall"` when a card is
  long enough that it must be allowed to break across pages.
- **Risk badges** are `<span class="badge read|mutate|destroy|exec|off">`, matching
  the four `McpRiskClass` values.
- **Callouts** are `div.note` with an optional `warn`, `danger` or `ok` modifier
  and a `<span class="label">` heading.

## Checking the output

There is no PDF viewer in CI. To eyeball pages on macOS, render them with
CoreGraphics — e.g. a small `swiftc` utility calling `CGPDFDocument` and
`CGContext.drawPDFPage` — or simply open the PDF. Watch for: overflowing text in
a diagram (SVG text does not wrap), tables broken mid-row, and near-empty pages
at chapter ends.

## Keeping it accurate

The guide documents behaviour, not intent. When the MCP implementation changes,
the sections most likely to go stale are §5 (tool reference), §9 (config keys and
constants) and §11 (limitations). Appendix C maps each claim back to the file that
decides it.

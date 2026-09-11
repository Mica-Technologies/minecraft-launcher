/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Renders guide.html to ../../MCP_SERVER_GUIDE.pdf through headless Chrome.
 *
 * Chrome is used rather than a dedicated PDF toolchain because it is already installed on
 * every platform this project is developed on, it supports the paged-media CSS the guide is
 * written against, and it needs no native build step. puppeteer-core (no bundled browser) is
 * the only dependency; it drives whatever Chrome or Edge is already on the machine.
 *
 * Usage:  npm install  &&  npm run build
 *
 * Override the browser with CHROME_PATH=/path/to/chrome when auto-detection picks the wrong
 * one, or when running on a machine whose Chrome lives somewhere unusual.
 */

import { access, mkdir, readFile, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import puppeteer from 'puppeteer-core';

const HERE = dirname( fileURLToPath( import.meta.url ) );
const SOURCE = resolve( HERE, 'guide.html' );
const OUTPUT = resolve( HERE, '..', '..', 'MCP_SERVER_GUIDE.pdf' );

/** Where Chrome (or an equivalent Chromium build) usually lives, per platform. */
const CANDIDATES = {
  darwin: [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    `${process.env.HOME}/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`
  ],
  win32: [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
  ],
  linux: [
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/snap/bin/chromium'
  ]
};

/**
 * Resolves the browser executable, preferring an explicit CHROME_PATH.
 *
 * @returns {Promise<string>} the executable path
 */
async function findBrowser()
{
  const explicit = process.env.CHROME_PATH || process.env.PUPPETEER_EXECUTABLE_PATH;
  if ( explicit ) {
    return explicit;
  }
  for ( const candidate of CANDIDATES[ process.platform ] ?? [] ) {
    try {
      await access( candidate, constants.X_OK );
      return candidate;
    }
    catch {
      // Try the next one. A missing candidate is the normal case, not an error.
    }
  }
  throw new Error(
    'No Chrome/Chromium/Edge build was found. Install Google Chrome, or set CHROME_PATH to '
    + 'an existing Chromium-family executable.' );
}

/**
 * Renders the running footer. Chrome injects the page numbers into the marked spans; the
 * template is otherwise plain HTML with inline styles, since it is laid out in its own
 * isolated document that does not see the page stylesheet.
 *
 * @returns {string} the footer template
 */
function footerTemplate()
{
  return `
    <div style="width:100%;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;
                font-size:7.5pt;color:#8a8f98;padding:0 14mm;display:flex;
                justify-content:space-between;align-items:center;">
      <span>Mica Minecraft Launcher &middot; MCP Server Guide</span>
      <span><span class="pageNumber"></span> / <span class="totalPages"></span></span>
    </div>`;
}


/**
 * Replaces every <img class="diagram" src="*.svg"> with the SVG's own markup, so the diagram
 * reaches the PDF as vector art rather than a bitmap.
 *
 * @param {import('puppeteer-core').Page} page the loaded page
 *
 * @returns {Promise<void>}
 */
async function inlineDiagrams( page )
{
  const sources = await page.$$eval( 'img.diagram', images => images.map( i => i.getAttribute( 'src' ) ) );
  for ( const source of sources ) {
    const markup = await readFile( resolve( HERE, source ), 'utf8' );
    await page.evaluate( ( src, svg ) => {
      const image = document.querySelector( `img.diagram[src="${src}"]` );
      if ( !image ) {
        return;
      }
      const holder = document.createElement( 'div' );
      holder.className = 'diagram';
      holder.innerHTML = svg;
      const node = holder.querySelector( 'svg' );
      // Let the stylesheet size it; the authored width/height are only there so the file is
      // sensible to open on its own.
      node.removeAttribute( 'width' );
      node.removeAttribute( 'height' );
      image.replaceWith( holder );
    }, source, markup );
  }
}

/**
 * Builds the PDF.
 *
 * @returns {Promise<void>}
 */
async function main()
{
  const executablePath = await findBrowser();
  const browser = await puppeteer.launch( {
    executablePath,
    headless: true,
    args: [ '--allow-file-access-from-files', '--font-render-hinting=none' ]
  } );

  try {
    const page = await browser.newPage();
    // The guide is entirely local -- stylesheet, diagrams and logo all sit next to it -- so
    // networkidle0 settles immediately and never waits on anything remote.
    await page.goto( pathToFileURL( SOURCE ).href, { waitUntil: 'networkidle0' } );
    // Paged media, not screen: without this the print stylesheet's @media print rules and the
    // page-break directives are ignored and the output is one long scroll.
    await page.emulateMediaType( 'print' );

    // Diagrams are authored as standalone SVG files so they can be edited on their own, but
    // Chrome rasterises an <img src="*.svg"> into the PDF -- the output triples in size and the
    // text inside a diagram stops being selectable or searchable. Swapping each one for its
    // inline markup right before printing keeps both properties: files on disk, vectors in the
    // PDF.
    await inlineDiagrams( page );

    await mkdir( dirname( OUTPUT ), { recursive: true } );
    const pdf = await page.pdf( {
      format: 'Letter',
      printBackground: true,
      displayHeaderFooter: true,
      headerTemplate: '<span></span>',
      footerTemplate: footerTemplate(),
      margin: { top: '16mm', bottom: '16mm', left: '16mm', right: '16mm' },
      preferCSSPageSize: false
    } );
    await writeFile( OUTPUT, pdf );
    console.log( `Wrote ${OUTPUT} (${( pdf.length / 1024 ).toFixed( 0 )} KB) using ${executablePath}` );
  }
  finally {
    await browser.close();
  }
}

main().catch( error => {
  console.error( error.message );
  process.exitCode = 1;
} );

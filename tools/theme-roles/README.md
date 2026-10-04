# Theme roles

Derives Material 3 colour roles (`-md-*`) from each theme's own `-color-*` palette and writes them
into the theme's token sheet in `src/main/resources/ui/`, between the `GENERATED` markers at the end
of its `.root` block.

```bash
npm install
npm run generate   # rewrite the generated block in every token sheet
npm run check      # exit 1 if a sheet's block is out of date
```

Run `generate` after changing any `-color-*` value, and commit the token sheets with the change.
`ThemeTokensTest` (in the normal build) then checks that every theme defines the same tokens and
that each text colour meets WCAG AA (4.5:1) against what it's drawn on.

## How roles are derived

The themes keep their colours: Google's `@material/material-color-utilities` only fills in the
tones they don't have.

| Role | From |
|---|---|
| `primary`, `secondary`, `error`, `success`, `warning` | the theme's own `-color-primary`, `-secondary`, `-danger`, `-success`, `-warning` |
| `on-X` | the theme's own choice if it reaches 4.5:1 (`-color-text-on-primary`), else the better of the colour's tone 10 and white, else black |
| `X-container`, `on-X-container` | the colour's hue and chroma at tone 30 / 90 (dark) or 90 / 10 (light) |
| `tertiary` group | the primary's hue turned 60°, chroma 24 (Material's tonal-spot rule) |
| `surface` | the theme's `-color-bg` |
| `surface-container-lowest` … `-highest` | the hue and tint of `-color-surface`, stepped from the background's tone the way Material's schemes step (dark −2/+4/+6/+11/+16, light +2/−2/−4/−6/−8). The Native themes use translucent white or black overlays instead, so the OS backdrop still shows through |
| `on-surface`, `on-surface-variant` | `-color-text`, `-color-text-muted` |
| `outline`, `outline-variant` | the surface palette at tone 60 / 30 (dark) or 50 / 80 (light) |
| `inverse-*`, `scrim` | Material's inverse tones; black |
| `state-hover-*`, `state-pressed-*` | the content colour at 8% and 10%, Material's state-layer opacities |

Version 0.4.0 of the library can't be imported under Node (an extensionless relative import), so it
is pinned to 0.3.0.

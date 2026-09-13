# 0002-FEAT - Replace the day reader with a month reader

## Status

Implemented for review

## Controlling proposal

`change-control/correspondence/2026-09-02 design proposal/diaries-web-month-reader-view-design-proposal.md`

## Delivered behaviour

- Adds `/diaries/{diaryId}/{year}/{month}` as the primary reader route.
- Groups the month's sanitized transcription by date, sequence and ID.
- Displays the selected fragment's source page and highlights its marquee.
- Shows the other linked marquees on that source page as pointer- and
  keyboard-selectable regions which select the associated transcript.
- Defaults the source viewer to the focus presentation, leaving the selected
  marquee unchanged while dimming the surrounding page.
- Supplies read-only mouse, pointer and touch pan/zoom controls in browser
  JavaScript, with Fit page, Fit selection and Reset actions.
- Keeps the image panel sticky on desktop and provides a compact expandable
  image panel on narrow displays.
- Adds previous/next published month, dropdown month selection, All diaries,
  and previous/next fragment navigation.
- Gives every explicit fragment selection a bookmarkable URL and browser
  history entry.
- Redirects legacy day and fragment routes to the canonical month selection.
- Retains source-page routes, including pages without linked transcription.
- Preserves server rendering and normal link navigation without JavaScript.
- Preserves read-only MQTT projection, sanitization and GET/HEAD-only
  architectural boundaries.

## Main implementation areas

- `ProjectionSnapshot` now builds immutable month indexes.
- `WebServer` renders month models and canonical redirects.
- `month-reader.peb` provides the semantic reader and initial viewer state.
- `diaries.js` provides progressive selection, history, pan, zoom and mobile
  expansion.
- `diaries.css` provides responsive reader/viewer layout and accessible focus
  and selection states.
- Projection and HTTP tests cover ordering, routes, rendering, compatibility,
  content safety and read-only behaviour.

## Verification

Run from the parent `diaries` directory:

```powershell
.\gradlew.bat :diaries-web:test
.\gradlew.bat :diaries-web:build
```

For a visual smoke test:

```powershell
.\gradlew.bat :diaries-web:runSyntheticSite
```

Then open `http://127.0.0.1:18082` and verify desktop, keyboard and narrow
viewport interactions.

## Zoom shading correction — 2026-09-13

The focus-style shading could stop short of the source image's right and
bottom edges after zooming. The mask used `userSpaceOnUse` with unspecified
bounds: its default percentage bounds changed with the SVG viewport, clipping
the full-page dimming rectangle as the viewBox became smaller.

`month-reader.peb` now explicitly bounds the mask to the dimming rectangle's
whole object bounding box (`x=0`, `y=0`, `width=1`, `height=1`). Mask contents
remain in page coordinates (`maskContentUnits=userSpaceOnUse`), preserving the
selected marquee cutout. The existing JavaScript already updates the dimming
rectangle and mask contents on page changes; no zoom or selection logic changes
are required. See the [SVG mask coordinate definitions](https://www.w3.org/TR/css-masking-1/#elementdef-mask).

Validation: all 49 web tests passed with zero failures/errors/skips, and
`:diaries-web:build` succeeded. Browser checks reproduced the old clipping on
the reported June 1829 page and verified the changed template mask in an
isolated HTML preview using that page's rendered DOM and current source assets.
Zooming, panning, Fit selection, focus/highlight toggling and switching source
pages preserved full-image shading outside the selected cutout. The live web
service was not restarted; stop it and run `run-web.bat` to install and load the
rebuilt template, then reload the browser page. No responder, MQTT or database
changes were needed.

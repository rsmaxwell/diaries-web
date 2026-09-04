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

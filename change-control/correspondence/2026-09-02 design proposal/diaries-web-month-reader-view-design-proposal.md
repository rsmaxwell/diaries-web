# Diaries Web Month Reader View – Design Proposal

**Status:** Discussion document  
**Date:** 2 September 2026  
**Target project:** `diaries/diaries-web`  
**Intended location:** `change-control/correspondence/diaries-web-month-reader-view-design-proposal.md`

## Purpose

This document considers a significant change to the reader-facing
`diaries-web` user interface. It is an initial design proposal intended to be
developed into a viable implementation specification. It does not authorise or
describe completed source changes.

The central proposal is that readers are more interested in reading the
transcription in context than in navigating individual fragments or scanned
source pages. The transcription should therefore become the primary navigation
mechanism, while the original image provides supporting evidence for the
currently selected fragment.

## Executive Assessment

This is a better reader-oriented direction for `diaries-web`.

The recommended replacement is a **month reader view** which presents all
transcribed fragments for one month. Selecting a fragment changes a read-only
image viewer to show the source page containing that fragment, together with
only that fragment's marquee.

This combines the strongest aspects of the current chronological day view and
source-page view while avoiding editor-oriented fragment navigation.

The design should retain stable legacy URLs as redirects rather than simply
deleting them. This protects existing bookmarks and provides useful canonical
links to fragments.

## Current `diaries-web` Routes

The current source does not contain a page that displays only one fragment.
The relevant routes are:

| Current route | Current purpose |
| --- | --- |
| `/diaries/{diaryId}` | Diary landing page listing dates and source pages |
| `/diaries/{diaryId}/{year}/{month}/{day}` | All fragments for one day |
| `/diaries/{diaryId}/pages/{pageId}` | One scanned page, all its marquees and linked transcriptions |
| `/fragments/{fragmentId}` | Redirect to the appropriate day and fragment anchor |

The proposed view primarily replaces the current day view and incorporates
most of the reader-facing functionality of the source-page view.

To avoid ambiguity in future discussion, this document uses the following
terms:

- **Diary selection page** – the page on which the reader chooses a diary.
- **Month reader view** – the proposed primary reading interface.
- **Source page** – one original scanned diary page.
- **Image panel** – the read-only zoomable display of the selected source page.
- **Fragment** – one section of transcribed text linked to a marquee.

## Proposed Reader Journey

### 1. Select a diary

The diary selection page remains the starting point.

Selecting a diary should take the reader directly to its earliest published
month, or to a configured starting month if such configuration is introduced.
The earliest published month is the preferred default because a diary is
normally read chronologically.

### 2. Read a month

A new route should represent the month:

```text
/diaries/{diaryId}/{year}/{month}
```

On a desktop display:

- the original image panel is displayed on the left;
- the month's transcription is displayed on the right;
- the image panel remains visible while the transcription scrolls;
- fragments are grouped under day headings;
- selecting a fragment updates the image panel;
- the image changes when the selected fragment belongs to a different source
  page;
- only the selected fragment's marquee is shown; and
- the selected transcription fragment is clearly highlighted.

The transcription controls the image. Merely scrolling the transcription
should not automatically change the selection. Scroll-driven selection can
feel unstable and creates accessibility and focus-management problems.

### 3. Navigate between months

The view should provide:

- **Previous published month**;
- **Next published month**;
- **Choose month**; and
- **All diaries**.

Previous and next navigation should skip months containing no published
fragments. For example, if January is followed by March, Next month from
January should go directly to March.

Navigation labels should name the destination rather than merely saying
Previous or Next:

```text
← July 1828       September 1828       November 1828 →
```

A month chooser is also needed because repeatedly stepping through a long
diary would be inconvenient.

## Month Transcription Presentation

Fragments should be ordered by:

1. fragment date;
2. fragment sequence; and
3. fragment ID as a deterministic final tie-breaker.

They should be grouped by full date rather than presented as one
undifferentiated list:

```text
Monday, 3 September 1828
    first fragment
    second fragment

Tuesday, 4 September 1828
    first fragment
```

Each fragment should be represented by a real link or button, not just a
clickable `div`. This supports keyboard use and preserves navigation without
JavaScript.

The selected fragment should have:

- a clearly visible border or background;
- selection semantics such as `aria-current`;
- a persistent fragment anchor; and
- a focus indicator which is distinct from the selected state.

Internal fragment IDs and sequence values should not be displayed to ordinary
readers.

## Fragment Selection and URLs

The selected fragment should be represented in the URL so that a particular
passage can be bookmarked, shared and restored after a reload. For example:

```text
/diaries/11/1828/09?fragment=33#fragment-33
```

This permits:

- reload to restore the same selection;
- browser Back and Forward navigation to restore earlier selections;
- existing fragment links to redirect to the new view; and
- navigation to continue working without JavaScript.

When no fragment is nominated, the first fragment in the month should be
selected.

The existing `/fragments/{fragmentId}` route should remain as a compatibility
redirect. It is a useful stable fragment URL and does not itself present a
single-fragment page.

An implementation specification will need to decide whether changing
selection adds a browser-history entry or replaces the current one. A likely
compromise is:

- direct clicks add an entry, allowing Back to revisit a selection; and
- automatic initial selection replaces the current entry.

## Read-Only Image Viewer

The existing `diaries-client` image viewer demonstrates the required pan and
zoom mechanics, but it should not be reused directly.

That component is tightly coupled to:

- Angular;
- `ModelContext`;
- MQTT RPC;
- fragment locking;
- marquee selection and editing;
- fragment and marquee creation or deletion; and
- editor routing.

Copying it into `diaries-web` would bring substantial editor behaviour into a
read-only application.

### Viewer implementation architecture

The image viewer shall be server-rendered by Java/Pebble and progressively
enhanced by a purpose-built, framework-independent browser JavaScript module.
Pan, zoom and touch gestures shall execute entirely in the browser. Angular
shall not be introduced into `diaries-web`, and the existing Angular editor
viewer shall be used only as a behavioural reference.

`diaries-web` should instead have a much smaller read-only viewer supporting:

- mouse-wheel zoom centred on the pointer;
- drag-to-pan;
- touch pinch-to-zoom;
- touch drag-to-pan;
- Zoom in and Zoom out buttons;
- Fit whole page;
- Fit selected fragment;
- Reset view;
- a single non-editable marquee;
- sensible minimum and maximum zoom limits; and
- prevention of panning the image completely out of view.

There must be no marquee resize handles, editing cursor, locking, MQTT RPC
calls or mutation controls.

### Behaviour when selection changes

The recommended behaviour is:

- If the new fragment belongs to a different source page, load that image and
  initially fit the selected fragment with some surrounding context.
- If it belongs to the same source page, retain the current zoom where
  practical, but pan enough to bring the new marquee into view.
- Fit page displays the complete scan.
- Fit selection centres and enlarges the selected fragment.
- Changing month selects the first fragment and resets the viewer.

Automatically cropping exactly to the marquee would remove useful handwriting
context. The fitted selection should include approximately 15–25% surrounding
margin. The final amount should be validated against representative diary
pages rather than fixed solely from this proposal.

## Mobile and Narrow-Screen Behaviour

A permanently sticky two-column view will not work well on a phone.

For narrow displays the recommended arrangement is:

- month heading and navigation first;
- a compact image panel above the transcription;
- an Expand image control for a larger or full-screen viewer;
- the transcription below; and
- no permanently sticky image consuming most of the viewport.

Selecting a fragment should update the image without forcibly scrolling the
reader back to the top. The reader can expand the image when they want to
examine it.

## Proposed Treatment of Existing Views

| Existing view | Proposed treatment |
| --- | --- |
| Diary index | Keep |
| Diary landing page | Simplify to a diary introduction and month selection, or redirect to the first published month |
| Day view | Redirect to the containing month and first fragment for that day |
| Fragment route | Redirect to the containing month with that fragment selected |
| Source-page view | Remove from normal navigation, but consider retaining it for untranscribed pages and old bookmarks |

The source-page route requires particular care. A source page can potentially:

- contain fragments from different months;
- contain no fragments; or
- contain fragments with incomplete relationship data.

A bare source-page URL therefore cannot always redirect naturally to one
month. Retaining a simple legacy source view may be safer even if ordinary
readers never encounter it.

## Server Rendering and JavaScript Enhancement

The existing `diaries-web` deliberately produces semantic pages which remain
usable without JavaScript. The enhanced viewer should preserve this property.

Without JavaScript:

- the complete monthly transcription remains readable;
- selecting a fragment performs a normal request using the fragment selection
  parameter; and
- the server renders the appropriate static image and marquee.

With JavaScript:

- fragment selection updates the image without a full page reload;
- the selected fragment and URL are updated;
- zoom and pan are enabled; and
- browser-history behaviour is preserved.

This makes JavaScript a progressive enhancement rather than a requirement for
reading the transcription.

## Data and Projection Considerations

### Transcription-first versus relationship completeness

The current projection includes a fragment in diary/date navigation only after
resolving the complete relationship:

```text
fragment → marquee → page → diary
```

If a fragment has a broken or temporarily incomplete relationship, it
disappears from the chronological reader view. This conflicts with a strictly
transcription-driven design.

Ideally:

- readable transcription remains visible when its source image is unavailable;
- the image panel reports that the source image is unavailable; and
- relationship diagnostics remain available to administrators.

However, a fragment does not itself contain a diary ID. If its marquee or page
relationship is missing, `diaries-web` may not be able to determine which
diary it belongs to. This is a genuine data-model constraint rather than only
a UI problem.

The implementation specification must decide whether unresolved fragments:

- remain excluded, with diagnostics treated as a data-quality safeguard; or
- require a data-contract change which gives a fragment an independently
  resolvable diary relationship.

No data-contract change is proposed at this stage.

### Month indexing

The projection currently indexes fragments by diary and date. The new view
will require a month-oriented index or an efficient month query over the
existing resolved-fragment collection.

The available months should be derived from published fragment dates, not from
source-page sequence or calendar gaps.

## Performance Considerations

A month is likely to be a sensible unit, but unusually busy months could
contain many formatted fragments.

The initial design should:

- server-render all transcription text for the month;
- load only the selected source image initially;
- avoid embedding duplicate images;
- lazy-load or optionally preload only likely subsequent images; and
- sanitize fragment HTML using the existing explicit allowlist.

Pagination should be introduced only if measurements against real diary data
show that a month is too large. Pagination would otherwise undermine the
simple month-reading experience.

Selection metadata supplied to JavaScript should contain only what the viewer
needs: fragment identity, page image URL, page dimensions and marquee
rectangle.

## Live Update Behaviour

`diaries-web` maintains a live server-side MQTT projection, but an already-open
browser page does not currently update itself when MQTT data changes.

For the initial reader view, the recommended behaviour is:

- new HTTP requests use the current projection;
- refreshing an open month obtains current content; and
- no browser-side MQTT, WebSocket or server-push channel is introduced.

Real-time browser updates would significantly expand the feature and are
unlikely to provide much benefit to ordinary readers.

## Failure and Edge Cases

The viable specification must explicitly define behaviour for:

- a missing or failed source image request;
- absent, zero or incorrect page dimensions;
- a marquee outside the recorded page bounds;
- a fragment with no usable source relationship;
- an empty diary;
- a diary with only one published month;
- a requested month containing no fragments;
- a requested fragment which is not part of the requested diary or month;
- the selected fragment being removed between requests;
- multiple fragments using the same source page;
- a source page containing fragments from multiple months; and
- a source page containing no fragments.

None of these conditions should prevent otherwise valid transcription text
from being read.

For an invalid month or fragment combination, the server should not silently
select unrelated content. It should either redirect to the fragment's
canonical month or return an appropriate not-found response. Redirecting to
the canonical month is preferable for valid fragments.

## Accessibility Requirements

The eventual specification should require:

- complete keyboard access to fragment selection and viewer controls;
- visible focus indicators;
- selected-state semantics which do not depend on colour alone;
- useful alternative text identifying the diary and source page;
- descriptive labels for zoom, fit and navigation controls;
- no essential information conveyed only by the marquee;
- support for reduced-motion preferences;
- logical focus behaviour after month navigation; and
- readable transcription without JavaScript or image availability.

The image viewer should not intercept ordinary browser keyboard shortcuts or
page scrolling unless it has explicit focus.

## Security and Architectural Boundaries

The proposed change must preserve the existing `diaries-web` boundaries:

- it remains read-only;
- the responder remains authoritative;
- only canonical retained Diary, Page, Fragment and Marquee topics are
  consumed;
- content routes remain GET/HEAD-only;
- stored fragment HTML continues to be sanitized before rendering;
- no MQTT publishing or RPC is introduced;
- no database or JPA access is introduced;
- no filesystem mutation is introduced;
- no locking is introduced; and
- no editing controls or editable marquee behaviour are introduced.

Viewer metadata embedded in the page must be safely encoded rather than
constructed by concatenating untrusted fragment content into JavaScript.

## Suggested First Viable Feature Scope

The first implementation feature should:

1. Introduce a server-rendered month reader route.
2. Group all resolved fragments for the month by date and sequence.
3. Select the URL-nominated fragment, or the first fragment by default.
4. Display one source image and one marquee for the selected fragment.
5. Enhance the image with read-only zoom, pan, Fit page and Fit selection
   controls.
6. Update the image and selected state in place when JavaScript is available.
7. Preserve normal link navigation when JavaScript is unavailable.
8. Provide Previous published month, Next published month, Choose month and All
   diaries navigation.
9. Redirect legacy day and fragment URLs into the month reader.
10. Retain the source-page route temporarily for compatibility and
    untranscribed pages.
11. Preserve existing read-only, GET/HEAD-only and sanitized-content
    boundaries.
12. Avoid authentication, editor controls, MQTT publishing, locking and
    database access in `diaries-web`.

## Decisions Still Required

The following decisions should be resolved before this proposal becomes an
implementation specification:

1. Does the diary landing page remain as a month index, or redirect directly to
   the earliest published month?

   - The diary landing page should remain as a month index

2. Should selecting a fragment on a different source page initially fit the
   whole page or fit the selected region with context?

   - Selecting a fragment on a different source page should initially fit the
   whole page

3. Should each explicit fragment selection create a browser-history entry?

   - Each explicit fragment selection should create a browser-history entry.

4. Must source pages with no transcription remain accessible to readers?

   - A source page with no transcription should remain accessible to readers, 
     albeit with no transcription

5. For how long should the legacy source-page route be retained?

   - The legacy source-page route should be retained for now.

6. Should unresolved fragments remain excluded, or is a future data-contract
   change required?

   - Unresolved fragments should remain excluded for now

7. What precise mobile image expansion behaviour is preferred?

   - on a mobile device, use the recommended behavour described above under "narrow displays"

8. Should the month chooser be a dropdown, a diary contents page or both?

   - the month chooser should be a dropdown

9. Should Previous and Next fragment controls be included as well as direct
   transcript selection?

   - Previous and Next fragment controls should be be included as well as direct
   transcript selection

10. What representative diaries and devices will be used for usability and
    performance acceptance testing?

   - The primary device will be a PC with a resolution of 1920x1080 


## Recommended Next Step

Review the decisions above using representative diary data and desktop,
tablet and mobile layouts. The agreed decisions can then be incorporated into
a formal change-control feature containing:

- functional requirements;
- route and redirect rules;
- interaction-state rules;
- accessibility acceptance criteria;
- failure behaviour;
- test scenarios; and
- an explicit list of source components expected to change.

Only after that specification is agreed should implementation begin.

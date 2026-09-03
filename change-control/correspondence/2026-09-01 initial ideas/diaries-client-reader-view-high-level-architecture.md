# Diaries Client Reader View – Alternative High-Level Architecture Proposal

## Overview

This document proposes an alternative to the separate server-side
`diaries-web` application described in
`diaries-web-high-level-architecture.md`.

Instead of introducing a Java service which subscribes to retained MQTT state
and renders HTML over HTTP, this alternative extends the existing Angular
`diaries-client` application. An authenticated user with `READER` privileges
would enter a dedicated read-only route tree and see diary content presented as
a simple website. Users with `EDITOR` or `ADMIN` privileges would retain the
existing editing application.

The responder remains authoritative in either design:

- PostgreSQL/JPA remains the durable source of truth;
- `diaries-responder` continues to validate and authorise MQTT RPC requests;
- the responder continues to publish the canonical retained MQTT model;
- `diaries-client` continues to derive live state from retained topics;
- browser-side role checks control presentation and navigation, but never
  replace responder-side authorisation.

The proposed architecture is:

```text
                         PostgreSQL
                             ▲
                             │
                     diaries-responder
                    persistence and RBAC
                             │
                    retained MQTT model
                             ▼
                        Mosquitto
                             │
                    MQTT over WebSockets
                             ▼
                       diaries-client
                    Angular application
                      /              \
                     / role routing   \
                    ▼                  ▼
             reader route tree    editor route tree
             simple diary view    existing editor UI
```

## Relationship to the Existing `diaries-web` Proposal

This is an architectural alternative, not an amendment to the existing
server-side proposal. The two proposals solve a similar presentation problem
with different boundaries:

```text
Existing proposal:
Browser -> HTTP -> diaries-web Java projection -> MQTT

This alternative:
Browser -> diaries-client Angular reader view -> MQTT over WebSockets
```

Selecting this proposal for the initial reader experience means implementing
the feature primarily in `diaries-client`, with limited authentication-contract
and authorisation-test work in `diaries-responder`. It does not require a new
Java application in the `diaries-web` Git/Gradle subproject.

The location of this document under `diaries-web/change-control/correspondence`
does not transfer implementation ownership to the `diaries-web` project.

The two approaches could coexist later, but doing both initially would duplicate
presentation work. A formal architecture decision should select one initial
approach before an implementation feature is made controlling.

## Executive Assessment

This alternative is technically viable and should require less new deployment
infrastructure than a separate application. It reuses the existing Angular,
MQTT, authentication, retained-model and image-viewing code.

It can provide a convincing straightforward diary view for a signed-in human
reader by using separate reader components, semantic content, restrained
navigation and no visible editing controls.

It cannot provide all the properties of an ordinary server-rendered website:

- JavaScript remains required;
- the initial HTTP response is the Angular application shell, not the diary
  content;
- diary content arrives asynchronously over MQTT;
- browsers must be permitted to connect to Mosquitto over WebSockets;
- subscribed payloads and topic names are inspectable in browser developer
  tools even when fields are hidden from the page;
- search crawlers, link-preview agents and no-JavaScript clients will not see a
  complete diary page without additional rendering work;
- authentication is visible unless a separate anonymous access model is added.

This is therefore strongest as an authenticated reader portal. The separate
server-side proposal remains stronger for a public website, conventional HTTP
delivery, crawlability, link previews, no-JavaScript access and keeping MQTT
away from public browsers.

## Relevant Current Behaviour

The current code already provides several foundations for this proposal:

- `diaries-responder` defines hierarchical `READER`, `EDITOR` and `ADMIN`
  roles;
- sign-in replies and access-token claims already contain account status and
  role;
- refreshed access tokens are rebuilt from the user's current database status
  and role;
- mutation handlers currently require an appropriate role, normally at least
  `EDITOR`;
- the Angular client already subscribes to retained Diary, Page, Fragment,
  Marquee and date-topic objects;
- the client already has a day view which assembles and displays fragment HTML;
- nginx already falls back to the Angular `index.html` for deep client routes.

Important gaps also exist:

- the TypeScript `SigninReply` contract does not currently declare the returned
  `status` or `role` fields;
- `AccessTokenService` stores identity but not role/status;
- `AuthGuard` checks only whether a token exists;
- startup routing always sends an authenticated user to the editor-oriented
  diaries route;
- diary and page lists support drag-and-drop mutation;
- the current day view supports fragment reordering and navigation into the
  editor;
- the page view combines Golden Layout, the image annotation canvas, Quill,
  locks, file operations and mutation controls;
- the current `SafeHtmlPipe` bypasses Angular sanitisation for stored fragment
  HTML.

The reader design should not attempt to make those editor components safe by
only disabling a few buttons.

## Architectural Principles

The implementation should follow these principles:

```text
RPC requests express intent.
Retained MQTT topics publish reality.
The database is the durable source of truth.
The responder enforces permission.
The client role selects an experience, not a security boundary.
```

Additional reader-specific principles are:

1. Create a separate reader component tree.
2. Share read-model and topic-subscription code where safe.
3. Do not instantiate editor components for reader routes.
4. Do not expose mutation services through reader components.
5. Keep server-side role checks authoritative.
6. Treat all MQTT data delivered to the browser as visible to that user.
7. Prefer semantic, bookmarkable diary routes over editor IDs and panels.
8. Make reader pages useful without exposing implementation metadata.

## Roles and Capabilities

Use one shared role definition across the client/responder contract:

```text
READER < EDITOR < ADMIN
```

The minimum capabilities should be:

| Capability | READER | EDITOR | ADMIN |
|---|---:|---:|---:|
| Sign in and refresh session | Yes | Yes | Yes |
| Subscribe to permitted retained diary state | Yes | Yes | Yes |
| Use the reader route tree | Yes | Yes | Yes |
| Use the editor route tree | No | Yes | Yes |
| Add/update/delete fragments or marquees | No | Yes | Yes |
| Acquire or release edit locks | No | Yes | Yes |
| Reorder/normalise diary objects | No | Yes | Yes |
| Upload, list or delete managed files | No | Yes | Yes |
| Administrative operations | No | No | As explicitly authorised |

`EDITOR` and `ADMIN` users should be able to switch into the reader experience
for preview/testing. A `READER` must never be offered a switch into the editor.

This proposal assumes `READER` means the user may read every diary object
published to the current client-readable retained topic tree. If readers need
different per-diary entitlements, or if some fragments are drafts, the current
shared browser MQTT model is not a sufficient confidentiality boundary. That
requirement would need topic-level segregation, a safe published projection, or
a server-side gateway.

## Authentication and Session Model

### Client role state

Add a client role/session model such as:

```typescript
export type Role = 'READER' | 'EDITOR' | 'ADMIN';

export interface AuthenticatedUser {
  userId: number;
  username: string;
  knownAs: string;
  status: 'ACTIVE';
  role: Role;
  sessionId: string;
}
```

Extend the client `SigninReply` DTO with `status` and `role`, matching the
existing responder reply. Store them in a single session service rather than
scattering independent flags across components.

The preferred refresh contract is to return current status/role identity along
with the new access token. This makes account disablement and role changes
visible to routing without requiring the browser to treat decoded JWT content
as authoritative application state. If the existing compact refresh reply is
retained, the client may decode the access-token payload for presentation, but
the responder signature and server-side checks remain the authority.

On refresh:

- an inactive account is signed out;
- a downgrade from `EDITOR` to `READER` immediately leaves any editor route,
  clears editor state and enters the reader route;
- an upgrade may expose the editor switch without forcing it;
- a missing, unknown or malformed role fails closed;
- tokens and role details are not logged.

### Route guards

Replace the token-only decision with explicit minimum-role guards:

```text
ReaderGuard: active session and role >= READER
EditorGuard: active session and role >= EDITOR
AdminGuard:  active session and role >= ADMIN
```

These guards improve navigation and user experience. They do not authorise MQTT
RPC operations; every responder handler must continue to do that.

### Startup routing

After sign-in or application restoration:

```text
READER          -> /read/diaries
EDITOR or ADMIN -> previous authorised return URL, otherwise /diaries
```

Return URLs must be validated before navigation. A reader return URL which
points at an editor route must be replaced with the reader landing route.

## Route Design

Keep current editor routes stable. Add a clearly separate Angular route tree:

```text
/read
/read/diaries
/read/diaries/:diaryId
/read/diaries/:diaryId/:year/:month/:day
/read/diaries/:diaryId/pages/:pageId
/read/fragments/:fragmentId
```

When deployed under the existing `/diaries/` base path, a browser URL would be
similar to:

```text
/diaries/read/diaries/1/1829/06/14
```

The date route should be the primary reading URL. Source-page and fragment URLs
are supporting routes, not the default reader workflow.

Use lazy route loading for reader and editor feature areas. Do not preload the
editor feature area for a `READER`. Lazy loading reduces accidental coupling and
the amount of editor code normally downloaded in a reader session, although it
must not be described as a security control: the deployment still contains the
editor application assets.

Unknown or forbidden routes should produce a controlled not-found/forbidden
view rather than falling through into editor components.

## Reader Component Structure

Prefer new presentation components over role conditionals throughout the
existing editor templates:

```text
reader/
├── reader.routes.ts
├── reader-shell/
├── diary-index/
├── diary-overview/
├── diary-day/
├── source-page/                 # optional in initial scope
├── fragment-permalink/          # optional in initial scope
├── navigation/
├── unavailable/
└── reader-view-model.service.ts
```

The reader components may reuse:

- immutable Diary, Page, Fragment and Marquee interfaces;
- MQTT connection and retained-object subscription infrastructure;
- date formatting;
- image URL construction;
- build/version information;
- deliberately presentation-neutral read selectors.

They should not directly reuse components which own mutation behaviour,
including:

- draggable diary/page/day tables;
- `FragmentComponent` and its Golden Layout editor shell;
- `TextPanelComponent`/Quill editing integration;
- editable `ImageViewerComponent` marquee interactions;
- mutation-oriented page headers;
- file management dialogs;
- `FragmentLockService`;
- RPC mutation methods.

Where existing read and write concerns are mixed, extract a read-only selector
or view-model service first. Do not pass `isReader` through a deep editor tree
and rely on every future control remembering to check it.

## Retained MQTT Model

The reader view continues to consume the canonical retained topic tree in the
browser. Relevant filters include the existing forms:

```text
diaries/diaries/+
diaries/diaries/{diaryId}/+
diaries/pages/{pageId}
diaries/fragments/{fragmentId}
diaries/dates/{year}/{month}/{day}/+
diaries/marquees/{marqueeId}
```

The reader view-model service should expose presentation-oriented streams such
as:

```text
diaries$
selectedDiary$
availableDates$
selectedDayFragments$
previousDay$
nextDay$
sourcePage$
connectionState$
```

It should hide MQTT subscription lifetime, relationship resolution and
tombstone handling from the components.

For a diary/date route, fragments selected from a date topic must be checked
against the complete relationship chain:

```text
Fragment.marqueeId
    -> Marquee.pageId
    -> Page.diaryId
    -> Diary.id from the route
```

This prevents a date containing more than one diary's fragments from showing
content under the wrong diary URL. Missing relationships should remain pending
or be omitted with diagnostics; they must not crash the page.

Retained empty payloads remove objects from the client model. Reconnect logic
must avoid displaying objects deleted while the browser was offline. The reader
view should show a concise reconnecting/unavailable state until its required
subscriptions have replayed sufficiently to render a consistent route.

## Reader Page Composition

### Diary index

Present diary names as ordinary links or cards ordered by sequence. Hide:

- database IDs;
- sequence numbers;
- draggable affordances;
- Material data-table headers where they add no reader value.

### Diary overview

Show the diary title, descriptive information available in the canonical model,
and navigation by year/month/date. If the retained model does not yet provide a
complete date index for a diary, document and implement the minimum selector
needed to derive it from subscribed fragments and relationships.

### Diary day

Render a day as a semantic document:

```html
<main>
  <article class="diary-day">
    <header>
      <h1>Sunday 14 June 1829</h1>
    </header>
    <section class="diary-entry">...</section>
    <section class="diary-entry">...</section>
  </article>
</main>
```

Fragments are sorted by numeric `sequence`, with a deterministic ID tie-break.
Do not display fragment IDs, sequence values, locks, marquees or edit state.

Provide conventional previous/next day links, a diary index link and meaningful
document titles. Browser back/forward and direct refresh must work.

### Original page image

The cleanest default reader view is the assembled transcript. An optional
“View original page” link can open a separate read-only source-page route using
the existing responder-served image URL.

If a source-page view is included:

- do not expose marquee creation, selection handles, resize cursors or overlays
  by default;
- do not instantiate the editable image-viewer component;
- use a simple responsive image/zoom viewer;
- provide useful alternate text and a transcript link;
- avoid a NAS mount or new file API.

## Hiding Editor Features

There are three different meanings of “hide”, and the proposal must distinguish
them:

1. **Visually absent:** the reader page does not render an editor control.
2. **Not instantiated:** reader routes do not create editor components or bind
   their mutation handlers.
3. **Not security-accessible:** the responder rejects an unauthorised operation
   even if a user constructs a request manually.

The first two belong to Angular. The third belongs to the responder and broker
security design.

| Editor capability | Reader presentation | How completely it can be hidden |
|---|---|---|
| Golden Layout panels/tabs | Use a separate single-column reader shell | Completely from normal UI |
| Quill toolbar and editable body | Render sanitised fragment HTML in a normal article | Completely from normal UI |
| Add/delete/save controls | Do not include mutation components | Completely from normal UI |
| Drag-and-drop ordering | Use non-draggable links/articles | Completely from normal UI |
| Locks and lock-owner badges | Do not render lock fields | Visually complete; payload may still contain them |
| Marquee creation/move/resize | Use a separate static image viewer | Completely from normal UI |
| File upload/list/delete | Do not expose routes or dialogs | Completely from normal UI |
| IDs, versions and sequences | Keep inside selectors; omit from DOM | Visually complete; available in retained payloads |
| MQTT topics and connection | Show only a neutral loading/reconnecting status | Hidden from page, visible in developer tools |
| Editor application code | Lazy-load editor routes only for editors | Normally not loaded; still present in deployed assets |
| Mutation RPC access | Reader components never call it; responder requires `EDITOR` | Secure only when responder checks are complete |

The recommended design can therefore hide advanced features very effectively
from the ordinary reader experience. It cannot conceal data already delivered
to that browser or make shipped JavaScript undiscoverable.

## How Much It Can Resemble a Straightforward Website

### Achievable presentation

After authentication, the reader experience can look and behave like a simple
website:

- a restrained site header and diary title;
- ordinary links rather than application toolbars;
- one readable content column;
- date headings and continuous fragment prose;
- responsive typography and images;
- previous/next navigation;
- bookmarkable routes;
- conventional 404, forbidden, loading and unavailable pages;
- no visible IDs, tables, panels, locks, drag handles or editing vocabulary;
- no need for the reader to understand MQTT.

Angular Material may still be used selectively, but the reader surface should
not look like an administration dashboard. Semantic HTML and reader-specific CSS
should take precedence over reusing editor tables.

### Irreducible application behaviour

It will still be a client-side application:

- the browser first downloads an application shell and JavaScript bundles;
- an authenticated MQTT WebSocket connection is established;
- retained messages must arrive before content becomes complete;
- connection loss needs visible recovery behaviour;
- “view source” will not contain the diary entry;
- crawlers and social link unfurlers may see only the shell/sign-in page;
- content will not work with JavaScript disabled;
- initial rendering may shift as model relationships resolve;
- broker credentials and permitted topics are necessarily available to browser
  code.

Angular SSR or prerendering would not naturally solve this because the current
live model depends on browser MQTT credentials/session behaviour. Adding SSR
would be a separate architecture with many of the responsibilities of the Java
`diaries-web` proposal.

The reasonable claim is therefore:

> The reader route can present like a straightforward website to an
> authenticated, JavaScript-enabled user, but it remains technically an Angular
> MQTT application rather than a conventional HTTP-rendered site.

## Security and Authorisation

### Server authority

No client-side `*ngIf`, route guard, disabled control or lazy-loaded boundary is
an authorisation mechanism.

Before rollout, inventory every responder RPC handler and prove:

- sign-in, refresh, health and version operations have their intended public or
  session semantics;
- every content/file/lock mutation requires at least `EDITOR`;
- administrative operations require their explicit higher role;
- missing, expired, inactive, malformed or insufficient-role tokens fail
  closed;
- a `READER` cannot cause a retained publication or database change;
- role changes take effect when access tokens refresh.

Add responder tests which invoke every mutation with `READER`, missing-role and
invalid-role claims. UI tests alone are insufficient.

### MQTT broker boundary

The current browser MQTT identity is shared by `diaries-client`. Its broker ACL
permits retained-topic reads and publication to the RPC request topic. The
broker does not know the signed-in application role.

For the initial design, it is acceptable for the shared client identity to
retain RPC-request publication only if responder authorisation is comprehensively
tested and remains authoritative. A reader can technically construct a request,
but cannot supply an access token with sufficient role for mutation.

This limitation should be recorded rather than implying that hidden UI makes
the MQTT connection read-only.

Issuing role-specific, short-lived broker credentials could enforce a stronger
broker boundary, but would require a credential-minting/authentication design
and is outside the simple client-extension proposal.

### Data visibility

Any field received over MQTT must be considered disclosed to the reader,
including fields omitted from the DOM. In particular, review retained DTOs for:

- lock owner identity and session details;
- internal IDs and versions;
- filenames/paths;
- unpublished or draft fragments;
- people or role objects;
- operational metadata.

Do not subscribe reader routes to topic trees they do not need. However, topic
minimisation cannot provide per-object confidentiality when the browser broker
credential itself can subscribe more broadly.

If the canonical retained tree contains data that a reader must not inspect,
choose one of these before implementation:

1. publish a separate reader-safe retained projection with a narrow ACL;
2. introduce a server-side gateway/projection such as the existing
   `diaries-web` proposal;
3. redesign broker authentication around user-specific entitlements.

CSS or TypeScript filtering is not an acceptable solution to confidential data.

## Fragment HTML Safety

The current day view passes fragment HTML through a pipe which explicitly
bypasses Angular sanitisation. Reusing that path in a broader reader surface
would increase stored-XSS impact.

The reader view must render fragment content through a documented and tested
sanitisation policy. It should permit only the formatting needed by diary
content, for example paragraphs, headings, emphasis, lists, safe links and
approved image attributes.

Reject or remove:

- scripts and event-handler attributes;
- unsafe URL schemes;
- iframes/embedded active content unless explicitly justified;
- styles capable of obscuring or taking over the application;
- unexpected forms or interactive controls.

The safest long-term boundary is responder-side sanitisation on write plus safe
client rendering. Client sanitisation remains useful as defence in depth. Do not
introduce another `bypassSecurityTrustHtml` path for reader content without an
independently sanitised input contract.

## Images and Static Content

Continue to use the responder's existing HTTP image/static-file route and the
runtime-configured base URL. Do not put image bytes in MQTT and do not expose NAS
paths directly.

Reader pages should handle:

- missing images;
- slow image loading;
- responsive sizing;
- correct URL escaping;
- meaningful alternate text where available;
- a clear separation between transcript and source-page image.

## Live Behaviour

The reader is naturally browser-live because it consumes retained updates in the
same way as the editor.

```text
editor mutation
      │
      ▼
diaries-responder commits database transaction
      │
      ▼
responder publishes canonical retained update
      │
      ▼
reader browser updates current view
```

Do not update the reader optimistically from RPC replies. Readers make no
mutation RPC requests, and retained topics remain the published reality.

For a calm website-like experience, apply updates without stealing focus,
resetting scroll position or flashing entire pages. A discreet “content
updated” announcement may be appropriate when an already-visible day changes.

## Accessibility and Responsive Design

The reader route should meet WCAG 2.2 AA-oriented expectations for its primary
flows:

- semantic heading hierarchy, landmarks and lists;
- keyboard-accessible navigation;
- visible focus states;
- skip-to-content link;
- sufficient contrast;
- useful link names;
- no drag-only interaction;
- readable line length and responsive typography;
- images which do not force horizontal scrolling;
- status/reconnect announcements which do not overwhelm screen readers;
- reduced-motion support.

Test at phone, tablet and desktop sizes. The reader route should not inherit
fixed editor-panel dimensions or require desktop-sized Golden Layout panels.

## Failure and Session States

Provide reader-specific states for:

- signed out or expired session;
- active account with no valid role;
- forbidden editor URL;
- MQTT connecting/reconnecting;
- retained model not ready;
- diary/date/page not found;
- temporarily unresolved relationships;
- malformed retained message;
- image unavailable;
- application version/configuration failure.

Do not expose stack traces, broker credentials, tokens, raw topic payloads or
private paths. Preserve the intended return URL across sign-in only when the
role permits it.

## Recommended Implementation Shape

### Client changes

Expected `diaries-client` areas include:

```text
src/app/
├── auth/
│   ├── role.ts
│   ├── session.service.ts
│   ├── reader.guard.ts
│   └── editor.guard.ts
├── reader/
│   ├── reader.routes.ts
│   ├── reader-shell/
│   ├── diary-index/
│   ├── diary-overview/
│   ├── diary-day/
│   ├── source-page/
│   ├── navigation/
│   └── reader-view-model.service.ts
└── model/
    └── retained read DTOs/selectors
```

The exact layout may follow current repository conventions, but reader
presentation, read-model selection and editor mutations should have explicit
boundaries.

### Responder changes

No persistence or retained-topic redesign is expected for the base proposal.
Likely responder work is limited to:

- keeping sign-in/refresh role contracts explicit and compatible;
- optionally returning current role/status in the refresh reply;
- completing mutation-handler authorisation coverage;
- adding focused role-contract tests;
- fixing any discovered handler which does not enforce its required role.

### Configuration and deployment changes

No new deployable service, Java subproject, database connection or container is
introduced. The existing `diaries-client` image and nginx route are updated.

Deployment must preserve Angular deep-link fallback for reader URLs. Runtime
configuration continues to provide MQTT WebSocket and responder image-base
settings without hard-coded deployment URLs or credentials.

## Approaches Considered

### Add role checks to existing editor components

This is the smallest apparent code change but is not recommended. Mutation code,
drag/drop, locks and editor state would still be instantiated, and every new
button or handler would need a correct role condition. The result would continue
to look like a disabled editor rather than a diary website.

### Dedicated reader components in the same Angular application

This is the recommended form of this alternative. It shares infrastructure and
DTOs while giving the reader a separate route, shell, presentation and test
surface. It hides advanced UI reliably from normal use and minimises accidental
mutation calls.

### A second Angular application in the same repository

This could produce a smaller reader bundle, but it duplicates configuration,
build targets and MQTT/session wiring. It also remains browser-MQTT and
client-rendered. Consider it only if bundle isolation becomes important enough
to justify a separately built frontend.

### Angular SSR/prerender

This is not recommended as an incremental step. Authentication, retained replay,
MQTT lifecycle and live updates would require a new server runtime and clear
session boundaries. At that point the separate `diaries-web` projection is the
cleaner architecture to compare.

## Testing Strategy

### Role and session unit tests

- parse/store all valid roles and reject unknown/missing roles;
- reader/editor/admin capability comparisons;
- sign-in and refresh update role/status atomically;
- downgrade clears editor state and redirects;
- sign-out clears role, tokens and identity;
- return URL validation prevents reader entry to editor routes.

### Route-guard tests

Test unauthenticated, `READER`, `EDITOR`, `ADMIN`, inactive and malformed session
states against every reader/editor route group.

### Reader component tests

- editor controls/components are absent, not merely disabled;
- diary/day content is ordered and related correctly;
- IDs, versions, sequences and locks do not enter rendered text/attributes;
- previous/next and direct routes work;
- loading, empty, not-found and reconnect states are controlled;
- source image view has no annotation/edit interactions;
- sanitisation removes hostile stored HTML.

### Mutation-isolation tests

- constructing and navigating reader pages makes no mutation RPC call;
- reader components do not inject mutation/lock/file services;
- drag/drop and editable controls are absent;
- editor lazy routes are not selected or preloaded for a reader session.

### Responder authorisation tests

For every mutation handler, test:

- no token;
- expired/invalid token;
- active `READER` token;
- active `EDITOR` token;
- role/status changes on refresh;
- no database or retained-topic change after a rejected reader request.

### MQTT and integration tests

- retained startup populates reader routes;
- live update changes the open view;
- tombstone removes content;
- reconnect converges without stale objects;
- out-of-order Page/Marquee/Fragment relationships resolve;
- a date route cannot mix fragments from different diaries;
- a reader attempting a crafted mutation is rejected by the responder.

### Browser and presentation tests

- sign in as reader and land on the reader index;
- navigate index -> diary -> day -> original page -> day;
- direct deep-link refresh works through nginx;
- editor controls and terminology are absent at phone and desktop widths;
- JavaScript/MQTT loading behaviour is understandable;
- keyboard and accessibility checks pass;
- sign in as editor and verify the existing editor remains functional;
- switch an editor into reader preview and back without losing authority state.

## Suggested Initial Scope

1. Define the client Role/session contract.
2. Extend sign-in and refresh handling to maintain current role/status.
3. Add reader/editor route guards and role-aware startup routing.
4. Add a lazy reader route tree and reader shell.
5. Add non-draggable diary index and diary overview.
6. Add an assembled date/day page using retained fragments.
7. Add previous/next and index navigation.
8. Add safe fragment HTML rendering.
9. Add a simple optional read-only original-page view.
10. Add connection/loading/not-found/forbidden states.
11. Audit and test all responder mutation authorisation.
12. Verify reader routes expose no mutation UI or calls.
13. Run Angular tests and production build.
14. Run responder tests/build for authentication-contract changes.
15. Perform a side-by-side reader/editor integration test.

## Explicit Non-Goals

- Replacing or reducing the existing editor capability.
- Moving persistence or validation into Angular.
- Letting UI role checks replace responder authorisation.
- Providing anonymous public access in the first implementation.
- Per-diary reader entitlements without a separate security design.
- Hiding data which has already been sent to the browser over MQTT.
- Server-side rendering, prerendering or static-site generation.
- Search indexing, public link previews or no-JavaScript content.
- Creating the server-side Java `diaries-web` projection at the same time.
- Changing the canonical retained model solely for visual presentation, unless a
  reader-safe publication boundary is required for confidentiality.

## Trade-Off Against the Separate Server-Side Proposal

| Concern | Extend `diaries-client` | Separate Java `diaries-web` |
|---|---|---|
| Initial code/deployment footprint | Lower | Higher |
| Reuse of current MQTT/model code | High | Contracts reused; implementation new |
| Authenticated reader portal | Strong fit | Requires HTTP/session design |
| Anonymous/public website | Weak without major security changes | Stronger fit |
| Conventional HTTP HTML | No | Yes |
| JavaScript-free reading | No | Possible |
| Search crawling/link previews | Weak | Stronger |
| Browser MQTT exposure | Yes | No |
| Ability to conceal internal retained fields | No, once subscribed | Stronger through projection |
| Visual separation from editor | Good with dedicated components | Complete application separation |
| Operational service count | Unchanged | Adds a service |
| Failure isolation from editor bundle | Shared | Separate |
| Browser-live updates | Native | Additional SSE/WebSocket work |
| Risk of editor/reader UI coupling | Must be actively controlled | Lower |

## Decision Criteria

Choose this client-extension proposal when all of the following are acceptable:

- readers are authenticated;
- every reader may inspect all retained data allowed to the shared browser MQTT
  identity;
- JavaScript and MQTT WebSockets are required and available;
- the goal is a clean human reading experience rather than server-rendered
  public pages;
- SEO, social previews and no-JavaScript access are not initial requirements;
- responder RBAC can remain the authoritative mutation boundary;
- maintaining one frontend deployment is valued more than complete application
  separation.

Prefer the separate server-side `diaries-web` proposal if any of these are
required:

- anonymous or broadly public access;
- conventional HTTP GET responses containing the diary content;
- search-engine indexing or reliable link previews;
- no MQTT credentials/topic data in browsers;
- strict filtering of internal retained fields;
- per-audience publication boundaries;
- useful content without JavaScript;
- operational isolation from the editor frontend.

## Recommendation

If the immediate audience is a controlled set of signed-in family members or
researchers with uniform read access, extending `diaries-client` is a reasonable
and economical first choice.

Implement it as a distinct lazy-loaded reader experience, not as a disabled
version of the editor. Use role-aware routing and session state for presentation,
retain comprehensive responder-side `EDITOR` checks for security, and treat all
subscribed MQTT data as disclosed to the reader.

The result can look like a straightforward diary website during ordinary use,
but project documentation must accurately describe it as an authenticated
Angular/MQTT reader portal. If the target becomes a genuinely public,
HTTP-native website, the separate server-side `diaries-web` projection remains
the more appropriate architecture.

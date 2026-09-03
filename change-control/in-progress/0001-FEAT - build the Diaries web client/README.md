# 0001-FEAT - Build the Diaries read-only web projection

## Type

Feature

## Status

In progress

## Priority

High

## Opened

2026-09-01

## Controlling Proposal

The reader-facing UI is superseded by and must now follow:

```text
change-control/correspondence/2026-09-02 design proposal/
diaries-web-month-reader-view-design-proposal.md
```

That proposal replaces the original day-led reader journey with the month
reader, preserves the source-page compatibility route, and records the agreed
navigation and viewer decisions. The retained-projection and read-only
architectural requirements in this feature continue to apply.

This feature is the detailed implementation specification for:

```text
change-control/correspondence/diaries-web-high-level-architecture.md
```

The proposal is the controlling architectural document. This feature must stay
compatible and in step with it. If implementation discoveries require a change
to the high-level architecture, update the proposal and this feature together
and record the decision before changing direction.

The feature directory retains its original name for identifier stability. The
phrase "web client" in that directory name must not be interpreted as a
replacement for the existing Angular application. The deliverable is a
server-side, browser-facing, read-only web projection service.

## Summary

Create `diaries-web` as a new sibling application which consumes the canonical
retained MQTT diary model, maintains a live in-memory projection, and renders
read-only diary content in response to ordinary HTTP GET requests.

`diaries-web` is definitely **not** intended to replace `diaries-client`.

The responsibilities remain deliberately separated:

```text
diaries-client
    interactive Angular editor
    views original scanned diary pages
    creates and maintains fragments and marquees
    sends authenticated MQTT RPC commands

diaries-responder
    validates and authorises commands
    owns PostgreSQL/JPA persistence
    enforces versions and locks
    publishes the canonical retained MQTT model

diaries-web
    subscribes to retained MQTT state with read-only credentials
    reconstructs a live in-memory diary projection
    assembles fragments into diary day and source-page views
    renders HTML for HTTP GET/HEAD requests
    never edits or persists diary data
```

The complete data flow is:

```text
Editor browser
     |
     v
diaries-client -- MQTT RPC intent --> diaries-responder --> PostgreSQL
                                           |
                                           | retained canonical state
                                           v
                                      Mosquitto
                                           |
                                           | subscribe only
                                           v
                                      diaries-web
                                           |
                                           | server-rendered HTTP GET
                                           v
                                      Public browser
```

The existing architectural principle continues to apply:

```text
RPC requests express intent.
Retained MQTT topics publish reality.
PostgreSQL is the durable source of truth.
diaries-web is a disposable read-only projection of published reality.
```

## Non-Replacement Invariant

The following is an acceptance invariant, not merely a migration preference:

- `diaries-client` remains the editing and maintenance application.
- Editors continue to use `diaries-client` to view original diary pages and to
  create, lock, update, order and delete fragments and marquees.
- `diaries-web` must not acquire editing controls, registration/sign-in flows,
  access/refresh token handling or MQTT RPC request capability.
- `diaries-web` must not be used as a staged replacement for
  `diaries-client`.
- Deployment of `diaries-web` must not remove, redirect or decommission
  `diaries-client`.
- Future editing requirements belong in `diaries-client` and the responder
  contract, not in `diaries-web`.

Any future proposal to merge or transfer those responsibilities requires a new
architectural change and is outside this feature.

## Goals

- Connect to Mosquitto as a server-side, subscription-only consumer.
- Subscribe to the retained objects required to reconstruct the published diary
  model.
- Keep an internally consistent, immutable in-memory snapshot updated as
  retained MQTT publications arrive.
- Assemble fragments into diary/day and source-page projections.
- Sort fragments by sequence with a deterministic ID tie-breaker.
- Render semantic, accessible HTML on each request using the latest complete
  in-memory snapshot.
- Serve a public/read-only navigation experience over HTTP/HTTPS without
  exposing MQTT to the browser.
- Reference existing responder-served diary images rather than reading the NAS
  or duplicating image storage.
- Start, reconnect and reconcile deterministically, including deletion of
  objects while the service was offline.
- Provide liveness/readiness health endpoints, metrics-friendly logging,
  version information, tests, a runnable fat JAR and a container image.
- Remain independently deployable beside the client and responder.

## Non-Goals

- Replacing or reproducing the Angular editing application.
- Authenticating Diaries editors or managing access/refresh JWTs.
- Publishing MQTT messages of any kind.
- Calling MQTT RPC handlers, including health, sign-in or mutation handlers.
- Connecting directly to PostgreSQL or using JPA/Hibernate.
- Owning a durable cache or a second source of diary truth.
- Writing to the NAS or mounting the diary image filesystem.
- Uploading, deleting or maintaining files.
- Creating, locking, updating, ordering or deleting Diaries objects.
- Allowing public browsers to connect directly to Mosquitto.
- Browser-live SSE/WebSocket updates in the initial release. A browser refresh
  or new GET obtains the latest server-side model.
- Pre-generating a static site or maintaining invalidation-sensitive rendered
  HTML caches in the initial release.
- Full-text search, EPUB/PDF generation, public write APIs or feeds unless added
  by later features.

## Authoritative Sources

Before implementation, inspect the active source rather than historical copies:

- `change-control/correspondence/diaries-web-high-level-architecture.md`;
- `../ARCHITECTURE.md`;
- `../README.md`;
- `../diaries-client/src/app/model/`;
- `../diaries-client/src/app/mqtt/live.object.service.ts`;
- `../diaries-client/src/app/mqtt/live.object.list.service.ts`;
- `../diaries-client/src/app/model/model-context.ts`;
- `../diaries-responder/src/main/java/com/rsmaxwell/diaries/responder/dto/`;
- `../diaries-responder/src/main/java/com/rsmaxwell/diaries/responder/model/`;
- `../diaries-responder/src/main/java/com/rsmaxwell/diaries/responder/Responder.java`;
- responder DTO contract tests where present.

The responder's actual retained publications are authoritative for wire shape.
The Angular client is a useful behavioural reference for topic selection,
sorting, URL construction and tombstones, but its implementation must not be
copied wholesale into this server application.

## Technology Decision

Implement a small Java service in line with the proposal's lightweight Java
structure.

- Java 25, matching the current Diaries Java toolchain unless the repository
  standard changes before implementation;
- the `diaries` parent Gradle wrapper/version catalog and a Groovy
  `diaries-web/build.gradle` subproject build;
- Javalin with embedded Jetty for HTTP routing/server lifecycle;
- Eclipse Paho MQTT v5 client;
- Jackson for retained JSON DTO parsing and configuration;
- Pebble server-side templates with auto-escaping enabled;
- OWASP Java HTML Sanitizer for stored fragment HTML;
- SLF4J with the repository-standard logging implementation;
- Shadow JAR for a self-contained runnable artifact;
- JUnit Jupiter, AssertJ and Awaitility for tests;
- Testcontainers `GenericContainer` with Mosquitto for MQTT integration tests;
- plain CSS and small progressive-enhancement JavaScript only where useful.

Use current stable compatible dependency versions when work starts. Shared
versions and plugin aliases belong in the parent
`diaries/gradle/libs.versions.toml`; dependencies which are genuinely local to
the web projection may be declared in `diaries-web/build.gradle`. Record the
selected versions in the project README.

Spring Boot, a JavaScript SPA and a browser MQTT library are intentionally not
required. If Javalin or Pebble proves unsuitable, changing framework/template
engine requires an explicit recorded decision while preserving the lightweight,
server-rendered architecture.

## Git and Gradle Project Ownership

`diaries` is both the top-level Git project and the Gradle root project. Its Git
submodules are:

```text
diaries-client
diaries-responder
diaries-web
```

`diaries-client` remains an Angular project and is not a Gradle subproject. The
Gradle child projects are:

```text
:diaries-responder
:diaries-web
```

The parent `diaries/settings.gradle` must therefore contain both includes while
retaining the existing root name and settings plugins:

```groovy
rootProject.name = 'diaries'
include('diaries-responder')
include('diaries-web')
```

The parent `diaries` repository exclusively owns the Gradle build entry points
and shared build infrastructure:

```text
diaries/build.gradle
diaries/settings.gradle
diaries/gradle.properties              # if root-wide properties are required
diaries/gradle/libs.versions.toml
diaries/gradle/wrapper/
diaries/gradlew
diaries/gradlew.bat
```

`diaries-web` owns only its subproject build file and project-specific sources,
resources and tasks. It must not contain a nested `settings.gradle`, Gradle
wrapper, `gradlew`, `gradlew.bat`, `gradle/wrapper`, or
`gradle/libs.versions.toml`. Do not generate a second Gradle root inside the Git
submodule. A component-local `gradle.properties` should also be avoided unless
a property is demonstrably specific to `diaries-web`; shared build properties
belong at the parent root.

The fact that `diaries-web` is a Git submodule does not make it an independent
Gradle root. All Gradle commands and dependency resolution start at `diaries`.

## Required Repository Layout

The combined parent/submodule layout must be at least:

```text
diaries/
├── .gitmodules
├── build.gradle
├── settings.gradle
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── gradlew
├── gradlew.bat
├── diaries-client/                       # Git submodule; not a Gradle child
├── diaries-responder/                    # Git submodule and Gradle child
└── diaries-web/                          # Git submodule and Gradle child
    ├── .editorconfig
    ├── .gitignore
    ├── AGENTS.md
    ├── Dockerfile
    ├── Dockerfile.dockerignore
    ├── README.md
    ├── build.gradle
    ├── config/
    │   └── diaries-web.example.json
    ├── scripts/
    │   └── container-healthcheck.sh
    ├── src/
    │   ├── main/
    │   │   ├── java/com/rsmaxwell/diaries/web/
    │   │   │   ├── DiariesWeb.java
    │   │   │   ├── buildinfo/
    │   │   │   ├── config/
    │   │   │   ├── health/
    │   │   │   ├── http/
    │   │   │   ├── model/
    │   │   │   ├── mqtt/
    │   │   │   ├── projection/
    │   │   │   ├── rendering/
    │   │   │   └── utilities/
    │   │   └── resources/
    │   │       ├── build-info.properties # generated, not hand-maintained
    │   │       ├── log4j2.xml
    │   │       ├── static/
    │   │       │   ├── css/diaries.css
    │   │       │   ├── js/diaries.js
    │   │       │   └── images/
    │   │       └── templates/
    │   │           ├── layouts/base.peb
    │   │           ├── components/
    │   │           ├── diary-index.peb
    │   │           ├── diary.peb
    │   │           ├── day.peb
    │   │           ├── source-page.peb
    │   │           ├── fragment.peb
    │   │           ├── error.peb
    │   │           └── unavailable.peb
    │   └── test/
    │       ├── java/com/rsmaxwell/diaries/web/
    │       └── resources/fixtures/
    └── change-control/
```

Names may be refined, but MQTT transport, mutable ingestion state, immutable
projection, rendering and HTTP concerns must remain separated.

## Process and Component Architecture

```text
Paho MQTT callback
       |
       | validated topic + DTO event
       v
single-threaded projection updater
       |
       | copy/build immutable model
       v
AtomicReference<ProjectionSnapshot>
       |
       | one snapshot captured per request
       v
Javalin route -> view model -> Pebble template -> HTML response
```

Important properties:

- MQTT callbacks do not render HTML and do not block on HTTP work.
- HTTP handlers do not mutate MQTT state.
- One request reads one `ProjectionSnapshot` reference and therefore cannot see
  half of an update.
- The snapshot is disposable and can always be rebuilt from retained topics.
- No request thread accesses a mutable collection owned by the MQTT callback.

## Runtime Configuration

Start the application with:

```text
java -jar diaries-web-fat.jar --config /config/diaries-web.json
```

Support this conceptual configuration:

```json
{
  "http": {
    "host": "0.0.0.0",
    "port": 8080,
    "basePath": "/",
    "publicBaseUrl": "https://example.test"
  },
  "mqtt": {
    "host": "mosquitto",
    "port": 1883,
    "clientId": "diaries-web",
    "topicPrefix": "diaries",
    "keepAliveSeconds": 30,
    "connectTimeoutSeconds": 10,
    "reconnectDelaySeconds": 2,
    "cleanStart": true
  },
  "projection": {
    "initialReplayQuietPeriodMillis": 1500,
    "initialReplayTimeoutSeconds": 30
  },
  "content": {
    "responderBaseUrl": "http://diaries-responder:8081",
    "publicResponderBaseUrl": "/diaries-responder",
    "diariesPath": "diaries"
  },
  "site": {
    "title": "Diaries",
    "description": "A read-only diary archive",
    "defaultLocale": "en-GB",
    "zoneId": "Europe/London"
  }
}
```

MQTT credentials come from environment variables or container secrets:

```text
DIARIES_WEB_MQTT_USERNAME
DIARIES_WEB_MQTT_PASSWORD
```

Rules:

- Do not commit working credentials.
- Configuration errors fail startup with one concise message and non-zero exit.
- Redacted effective configuration may be logged; passwords may not.
- Host/port values are server-side MQTT TCP settings, not browser WebSocket
  settings.
- `basePath` is normalized and supported by every route and generated link.
- `publicResponderBaseUrl` is what browsers use for images. The server-side
  responder URL is not leaked when production routing uses a public proxy path.
- Environment overrides may be added consistently, but avoid multiple names for
  the same setting.

## MQTT Security and Permissions

Create a dedicated broker identity for `diaries-web`.

It may subscribe only to the minimum retained topics needed for rendering:

```text
read  diaries/diaries/+
read  diaries/pages/+
read  diaries/fragments/+
read  diaries/marquees/+
```

It must not have permission to:

```text
write diaries/#
read  diaries/rpc/#
write diaries/rpc/#
```

Do not reuse responder, editor or health-check credentials. Add exact ACL and
password provisioning through coordinated parent/deployment changes. Never
weaken anonymous broker access to make the website work.

## MQTT Subscription Contract

The initial implementation consumes canonical lookup topics rather than every
hierarchy alias:

| Topic filter | Payload |
| --- | --- |
| `diaries/diaries/+` | `Diary` |
| `diaries/pages/+` | `Page` |
| `diaries/fragments/+` | `Fragment` |
| `diaries/marquees/+` | `Marquee` |

The date index represented by `diaries/dates/{year}/{month}/{day}/+` is derived
from the canonical fragment fields in memory. This avoids duplicate ingestion
of the same fragment while preserving the proposal's date-based projection.

Hierarchy aliases may be subscribed to later for diagnostics or contract
validation, but must not create duplicate model objects or become a conflicting
source of truth.

Connection requirements:

- use MQTT v5 over server-side TCP;
- use a unique actual client ID derived from the configured prefix;
- subscribe at QoS 1;
- use automatic reconnect with bounded logging/backoff;
- attach one callback/dispatcher;
- subscribe again after every new connection;
- reject unexpected topic depth, non-numeric IDs and payload/topic ID mismatch;
- treat an empty payload as a retained tombstone, never as JSON;
- log topic and object ID for validation errors without logging fragment HTML;
- publish no messages, including status or birth/death messages, unless a later
  reviewed change explicitly authorizes a non-Diaries operational topic.

## Retained DTO Contract

Incoming data is untrusted. Deserialize with Jackson into dedicated wire DTOs,
validate required fields, and convert to immutable projection records.

### Diary

```text
id: Long
version: Long
name: String
sequence: BigDecimal
```

### Page

```text
id: Long
version: Long
diaryId: Long
name: String
sequence: BigDecimal
extension: String
width: Integer
height: Integer
```

### Fragment

```text
id: Long
version: Long
year: Integer
month: Integer
day: Integer
sequence: BigDecimal
text: String
marqueeId: Long | null
lock: object | null
```

Lock fields are tolerated but are presentation-irrelevant. Do not expose
session IDs or other lock details on public pages.

### Marquee

```text
id: Long
version: Long
pageId: Long
fragmentId: Long
rectangle:
  x: Double
  y: Double
  width: Double
  height: Double
```

Unknown additive JSON fields are tolerated. Missing required fields, invalid
dates, negative/invalid dimensions and topic/payload ID mismatch reject that
event while preserving the last valid snapshot. Contract tests must use
representative JSON produced by active responder DTOs.

## In-Memory Projection Model

The immutable published snapshot contains at least:

```text
ProjectionSnapshot
├── generation
├── createdAt
├── diariesById
├── pagesById
├── fragmentsById
├── marqueesById
├── pagesByDiaryId
├── marqueesByPageId
├── fragmentByMarqueeId
├── fragmentsByDiaryAndDate
├── datesByDiaryId
├── relationshipDiagnostics
└── sourceConnectionState
```

Primary maps retain every valid canonical object, including temporarily
unresolved objects. Derived indexes contain only valid relationships.

### Relationship rules

The responder model relates a fragment to a diary through:

```text
Fragment.marqueeId
       -> Marquee.id
       -> Marquee.pageId
       -> Page.id
       -> Page.diaryId
       -> Diary.id
```

A fragment with no marquee cannot be attributed to a diary using the current
published contract. Such a fragment remains in the primary map but is excluded
from diary-specific public pages until its relationship becomes resolvable.

Similarly:

- a page whose diary is missing is retained but not publicly linked;
- a marquee whose page or fragment is missing is retained but not rendered;
- out-of-order arrival is normal and must resolve automatically when related
  messages arrive;
- removing a parent immediately removes dependent items from derived views but
  does not invent tombstones for their canonical objects;
- diagnostics count unresolved relationships without exposing private text.

### Ordering

- diaries: `sequence`, then `id`;
- pages within a diary: `sequence`, then `id`;
- fragments within a day: `sequence`, then `id`;
- source-page transcript: fragment `sequence`, then `id`;
- date indexes: chronological date order.

Use `BigDecimal.compareTo`, not scale-sensitive equality, for sequence ordering.

## Atomic Updates and Thread Safety

MQTT events are serialized through one projection-update executor.

For each accepted event:

1. apply upsert or tombstone to mutable state owned only by the updater;
2. resolve relationships and rebuild affected indexes;
3. construct immutable maps/lists for the next snapshot;
4. atomically replace `AtomicReference<ProjectionSnapshot>`;
5. increment a monotonic generation number.

HTTP handlers capture the reference exactly once and pass that snapshot through
selection, view-model construction and rendering. A response must never mix
generations.

The initial implementation may rebuild all derived indexes per event if the
expected data volume makes this simpler and tests demonstrate acceptable
performance. Optimize incrementally only after measurement.

## Initial Retained Replay and Reconnect Reconciliation

MQTT does not provide an explicit "retained replay complete" message. Define a
deterministic approximation using subscription acknowledgement plus a
configurable quiet period.

### Initial startup

1. Start the HTTP server with readiness false.
2. Connect to MQTT and subscribe to all canonical filters.
3. Populate an empty staging generation from retained messages.
4. Restart the quiet-period timer whenever a relevant message arrives.
5. After all subscriptions are acknowledged and the quiet period expires,
   atomically publish the staging snapshot and mark ready.
6. If the replay timeout expires first, remain unready and report the reason.

An empty diary system is valid and becomes ready after the quiet period.

### Reconnect

Objects may have been deleted while `diaries-web` was offline. The broker will
not replay tombstones for retained topics which no longer exist. Therefore, it
is incorrect to keep the old maps and merely apply replayed objects.

On every fresh MQTT connection:

1. mark readiness false;
2. create a completely empty staging generation;
3. resubscribe and rebuild it from the broker's current retained set;
4. atomically replace the prior snapshot only after replay quietness;
5. mark readiness true.

While rebuilding, normal diary routes return HTTP 503 rather than knowingly
serve a stale projection. Liveness remains up. This behaviour prevents deleted
offline objects from surviving in memory indefinitely.

Live updates received after a generation becomes active are applied normally.

## HTTP Behaviour

The public browser communicates only through HTTP/HTTPS.

- Render on every request from the current in-memory snapshot.
- Do not connect the browser to MQTT.
- Do not expose MQTT credentials, broker hostnames or internal ACL details in
  HTML or JavaScript.
- Support GET and HEAD for page/resource routes.
- POST, PUT, PATCH and DELETE on content routes return 405 with an `Allow`
  header.
- Invalid path IDs/dates return 404 or 400 as appropriate, never stack traces.
- Normal content routes return 503 while the projection is not ready.
- Capture one immutable snapshot per request.

## Initial HTTP Routes

All routes respect configured `basePath`.

| Route | Result |
| --- | --- |
| `GET /` | Site landing page and ordered diary list. |
| `GET /diaries` | Canonical ordered diary index; `/` may link or redirect here. |
| `GET /diaries/{diaryId}` | Diary landing page with ordered source pages and date/year/month navigation. |
| `GET /diaries/{diaryId}/{year}/{month}/{day}` | Core diary-day page assembled from reachable fragments for the date. |
| `GET /diaries/{diaryId}/pages/{pageId}` | Read-only original source-page image plus related fragment transcript/overlays. |
| `GET /fragments/{fragmentId}` | Stable fragment permalink which redirects to or renders its diary/day context and anchor. |
| `GET /about` | Read-only site/build information. |
| `GET /health/live` | Process liveness. |
| `GET /health/ready` | MQTT/projection readiness. |
| `GET /assets/*` | Versioned static CSS/JavaScript/images. |

Canonical URLs use numeric IDs and zero-padded Gregorian date segments in
links. Accepting non-padded incoming segments is optional; if accepted, redirect
to the canonical form.

### Diary index

- show diaries ordered by sequence then ID;
- use diary names as visible link text;
- omit internal sequence/version values from ordinary public presentation;
- show an intentional empty state when no diaries are published.

### Diary landing page

- show diary title and site navigation;
- provide chronological year/month/day navigation derived from reachable
  fragments;
- list source pages in responder order;
- link previous/next diary where useful;
- identify content as read-only without displaying editor controls.

### Day page

For `/diaries/{diaryId}/{year}/{month}/{day}`:

1. select fragments whose date fields match;
2. resolve each fragment through marquee/page to the requested diary;
3. sort by sequence then ID;
4. sanitize each fragment's stored HTML;
5. render semantic article/section markup;
6. link each fragment to its source-page view when resolvable;
7. include previous/next published day navigation within the diary.

Conceptual HTML:

```html
<article class="diary-day">
  <header class="diary-date">
    <h1>Sunday 14 June 1829</h1>
  </header>
  <section class="fragment" id="fragment-123">
    <!-- sanitized fragment HTML -->
  </section>
</article>
```

If the date is valid but has no published fragments for that diary, return 404
with navigation back to the diary rather than an empty 200 page.

### Source-page view

- verify the page belongs to the requested diary;
- render the original responder-served page image;
- use intrinsic page dimensions for a read-only SVG overlay when marquee data
  is available;
- link marquee regions to fragment anchors/transcripts;
- list related fragments in sequence/ID order beneath or beside the image;
- provide day links for fragment dates;
- include useful fallback text if the image cannot be loaded;
- provide no selection, lock, drag, resize, edit or delete controls.

### Fragment permalink

- resolve the fragment to a diary and date using current relationships;
- redirect to the canonical day URL plus `#fragment-{id}` or render equivalent
  context;
- unresolved/orphan fragments return 404 and are not leaked through diagnostics.

## Images and Existing Static Content

`diaries-web` does not mount or read the NAS.

Construct browser-visible page image URLs using the same active convention as
the editor:

```text
{publicResponderBaseUrl}/{diariesPath}/{encodedDiaryName}/{encodedPageName}{extension}
```

Normalize slashes and encode path segments independently. Do not double-encode
or assume the extension includes or excludes a dot without verifying the active
retained data. Contract tests must cover the repository's actual convention.

Images are requested by the browser from the existing responder/proxy route.
`diaries-web` does not download, cache or re-serve the binary image in the
initial implementation.

## Rendering and Presentation

Presentation belongs to `diaries-web`, while content remains in MQTT.

Templates provide:

- complete HTML document structure;
- site header, footer and navigation;
- diary/date/source-page breadcrumbs;
- accessible previous/next links;
- fragment wrappers and stable anchors;
- responsive image/transcript layout;
- metadata needed for canonical links and social previews where configured;
- error, empty and temporarily unavailable pages.

Use progressive enhancement. Core diary reading and navigation must work with
JavaScript disabled. Initial JavaScript may provide only non-essential features
such as a responsive menu or image overlay convenience.

Do not maintain a rendered HTML cache initially. Render-on-request from the
in-memory snapshot is the preferred proposal. Add ETags based on snapshot
generation plus route/view identity so conditional GETs are possible without
making cached HTML a second model.

Suggested headers:

- correct UTF-8 content type;
- conservative `Cache-Control` for dynamic HTML;
- immutable long caching for fingerprinted assets;
- ETag for snapshot-derived pages;
- `X-Content-Type-Options: nosniff`;
- `Referrer-Policy`;
- a Content Security Policy compatible with configured responder image origin;
- frame protection according to deployment requirements.

## Fragment HTML Safety

Fragment text is stored HTML and must be treated as untrusted at the public
rendering boundary even if created by authenticated editors.

- Define one explicit OWASP sanitizer policy.
- Permit only formatting needed by existing Quill-produced diary content.
- Remove scripts, event handlers, forms, embedded objects, unsafe URL schemes
  and style constructs outside the agreed policy.
- Sanitize before passing content to Pebble's unescaped HTML facility.
- Pebble auto-escaping remains enabled for every other value.
- Cache of sanitized fragment text within a snapshot is allowed because it is
  derived and replaced when the fragment changes.
- Add malicious HTML fixtures and regression tests.

Do not use a blanket "trust editor content" exception.

## Read-Only Boundary

The codebase must contain no Diaries mutation client.

Specifically, do not implement:

- an MQTT publisher abstraction for application topics;
- request/reply response-topic or correlation handling;
- access/refresh token storage;
- register, sign-in, lock or unlock flows;
- fragment, marquee, diary, page or file mutation methods;
- POST form actions for diary content;
- direct repository/database classes.

Static asset build tooling and operational health responses do not weaken this
rule. Tests should fail if content routes accept mutation methods.

## Health and Operational State

### Liveness

`GET /health/live` returns 200 when the process and HTTP server are functioning.
It does not require MQTT to be connected.

### Readiness

`GET /health/ready` returns 200 only when:

- MQTT is connected;
- all required subscriptions are acknowledged;
- the current retained replay generation has completed its quiet period;
- an immutable snapshot is available;
- the projection updater has not failed.

Otherwise return 503 with concise JSON containing non-sensitive state/reason.

### Public content during unready state

Return a styled 503 page with `Retry-After` for normal diary routes. Do not serve
known-stale content after a reconnect has begun.

### Diagnostics

Log/measure at least:

- MQTT connection/reconnect transitions;
- subscription acknowledgement/failure;
- replay generation and duration;
- snapshot generation and entity counts;
- invalid/tombstone message counts;
- unresolved relationship counts by type;
- HTTP status/latency using privacy-safe route templates, not fragment text;
- last accepted MQTT update time.

Avoid per-health-check noise at normal log level.

## Build and Version Information

Generate classpath build information with:

```text
name
version
buildId
buildDate
gitCommit
gitBranch
gitUrl
```

CI environment values take precedence over local Git discovery. Builds still
work when Git metadata is unavailable. Expose safe version information on
`/about` and optionally in the footer/health detail. Never expose configuration
credentials.

Build outputs include:

- normal JAR;
- runnable Shadow/fat JAR;
- test and coverage reports;
- container image from the same validated artifact.

## Accessibility and Responsive Requirements

- Use semantic headings, landmarks, articles, navigation and lists.
- Every page has a unique descriptive title and one primary `h1`.
- Navigation works fully by keyboard.
- Visible focus and skip-to-content links are provided.
- Colour is not the only information carrier.
- Source-page overlays have transcript/link alternatives.
- Images have useful alt text or are correctly marked decorative.
- Date text uses readable localized formats while URLs remain stable.
- Layout works on phone, tablet and desktop widths.
- Respect reduced-motion and user font scaling.
- Target WCAG 2.2 AA for application-authored markup and styling.

## Error Handling

- Invalid configuration prevents startup with a non-zero exit code.
- Initial MQTT failure leaves HTTP live but unready and continues bounded
  reconnect attempts unless configuration is invalid.
- Malformed retained messages do not replace last valid objects.
- Rendering failure returns a controlled 500 page with a request ID and logs the
  exception without fragment HTML.
- Missing entities/relationships produce 404 or omission as specified, not
  `NullPointerException`.
- Image failure is handled in rendered markup/browser presentation and does not
  affect projection readiness.
- Shutdown stops accepting requests, disconnects MQTT and terminates executors
  within a bounded period.

## Testing Requirements

### DTO and topic contract tests

- deserialize exact responder Diary, Page, Fragment and Marquee JSON fixtures;
- tolerate documented additive fields such as computed lock state;
- reject topic/payload ID mismatch and invalid required fields;
- test canonical filter/topic parsing;
- test empty-payload tombstones;
- verify fixtures against active responder DTO contract tests.

### Projection unit tests

- build complete diary/page/marquee/fragment relationships in every arrival
  order;
- sort `BigDecimal` sequence numerically then ID;
- derive diary/date indexes from fragment fields;
- keep unresolved objects without exposing them publicly;
- resolve relationships when missing parents arrive;
- remove derived relationships when any parent is tombstoned;
- re-add objects cleanly after tombstones;
- guarantee immutable published collections;
- guarantee one captured snapshot cannot change beneath a renderer;
- sanitize malicious and normal Quill HTML;
- construct correct responder image URLs.

### Replay/reconnect tests

- initial replay does not mark ready before subscription acknowledgement and
  quietness;
- empty broker becomes ready after quietness;
- replay timeout remains unready;
- reconnect starts an empty staging generation;
- an entity deleted while offline is absent after the new generation swaps;
- old snapshot is not served during rebuild;
- live messages after readiness increment generation once per accepted event;
- duplicate delivery is idempotent.

### HTTP route tests

- diary index and diary landing selection/order;
- day assembly and previous/next navigation;
- source-page relationship and image URL;
- fragment permalink canonical redirect;
- empty system, missing diary/page/day/fragment and unresolved relationships;
- GET versus HEAD behaviour;
- conditional GET/ETag;
- 405 and `Allow` for POST/PUT/PATCH/DELETE;
- 503 plus `Retry-After` while unready;
- liveness versus readiness distinction;
- base-path link correctness;
- no secrets/internal broker details in HTML;
- HTML escaping and sanitizer policy;
- primary accessibility checks.

### MQTT integration tests

Use disposable Mosquitto infrastructure to prove:

1. retained objects present before startup build the expected projection;
2. a live retained update changes the next HTTP response;
3. a retained tombstone removes content;
4. reconnect/resubscribe rebuilds state;
5. offline deletion does not survive the next staging generation;
6. credentials without write permission can run the service;
7. a write/RPC attempt is impossible from normal application APIs and rejected
   by the test ACL if attempted directly.

### Concurrency tests

- multiple HTTP requests during rapid updates each see one valid generation;
- no mutable collection leaks from a snapshot;
- updater failure marks readiness false rather than publishing partial state;
- graceful shutdown does not deadlock callback, updater or Jetty threads.

### Browser smoke tests

Use Playwright or an equivalent browser runner for:

- navigate diary index -> diary -> day -> source page -> fragment/day;
- responsive phone and desktop rendering;
- keyboard navigation and skip link;
- JavaScript-disabled core reading flow;
- image-load failure fallback;
- styled 404 and 503 pages.

Use synthetic content only. Do not depend on production diary text or NAS data.

## Required Gradle Tasks

Run and document component tasks from the `diaries` Gradle root:

```text
cd diaries
gradlew.bat :diaries-web:test
gradlew.bat :diaries-web:build
gradlew.bat :diaries-web:shadowJar
gradlew.bat :diaries-web:run --args="--config ..."
```

On Unix-like systems use `./gradlew` from the same parent directory. The
unqualified root `gradlew.bat build` remains available to build every Gradle
subproject. Do not add or document a wrapper invocation from inside
`diaries-web`. Add a component verification task if useful, but
`:diaries-web:build` must compile, test and produce the expected artifacts.

## Container and Deployment

Create a multi-stage `diaries-web/Dockerfile`, built with `diaries` as its Docker
context so the parent Gradle root is available:

```text
docker build -f diaries-web/Dockerfile .
```

The build stage must invoke the parent wrapper with a qualified
`:diaries-web:shadowJar` task. It may copy only the parent Gradle build metadata
and subproject files required for configuration/build, but must not manufacture
a nested Gradle root inside the image context. The Dockerfile must then:

1. build with the parent project's pinned Gradle wrapper and required JDK;
2. copy the validated fat JAR into a minimal Java runtime image;
3. run as a non-root user;
4. expose only the HTTP port;
5. load config/secrets at runtime;
6. use `/health/ready` for container readiness/health with low log noise.

The container requires:

- network access to Mosquitto TCP;
- HTTP access only insofar as browsers need the separately proxied responder
  image route;
- no PostgreSQL connectivity;
- no NAS mount;
- no writable diary-content volume.

Production reverse proxy configuration should expose `diaries-web` over HTTPS
and expose the responder's read-only image route under the configured public
base URL. Public browsers must never receive a route to internal MQTT TCP.

Support standalone and shared frontend nginx topologies according to established
Diaries deployment patterns. Parent Compose, pipeline and Ansible/playbook
changes are coordinated work in their owning repositories; do not silently edit
them when only this repository is in scope.

## MQTT ACL and Deployment Identity Deliverables

The feature is not operationally complete until coordinated deployment defines:

- dedicated `diaries-web` MQTT username;
- generated password from secret storage;
- minimum read-only topic ACL;
- container environment/secret wiring;
- broker hostname/port;
- HTTP route/hostname;
- public responder image base URL;
- health-check configuration;
- explicit confirmation that client and responder ACLs are unchanged.

Do not record actual credential values in this change.

## CI Expectations

A component pipeline should check out the `diaries` parent repository with its
required Git submodules and then:

1. use the parent repository Gradle wrapper from the `diaries` root;
2. run unit and HTTP tests;
3. run MQTT integration tests with disposable Mosquitto;
4. create coverage/test reports;
5. run `build` and create one fat JAR;
6. build the container from that artifact;
7. start it with synthetic retained topics and smoke-test HTTP/readiness;
8. verify the container has no DB/NAS dependencies or embedded secrets;
9. publish an explicitly versioned image only after validation;
10. retain build/version metadata.

## Documentation Deliverables

The generated project README must explain:

- the non-replacement relationship with `diaries-client`;
- the responder's persistence/RPC/canonical-publication responsibility;
- the read-only projection architecture;
- retained topics and object relationships;
- startup/reconnect staging generations;
- HTTP routes and render-on-request behaviour;
- fragment HTML sanitization;
- image URL/proxy requirements;
- configuration and secret environment variables;
- build, run, test and container commands;
- the distinction between the `diaries-web` Git submodule and Gradle
  subproject, including that commands run from the `diaries` root;
- local integration with development infrastructure;
- MQTT ACL requirements;
- liveness/readiness semantics;
- troubleshooting for MQTT, missing relationships, 503s and images;
- deployment and rollback.

Add `AGENTS.md` with instructions that prevent future Codex work from adding
editing, MQTT publishing, RPC or database access without an explicit
architecture change.

## Implementation Plan

### Phase 1 - Confirm contracts and scaffold

- [x] Re-read the controlling proposal and active responder/client DTO sources.
- [x] Record exact repository revisions used for the retained contract.
- [x] Add `include('diaries-web')` to the parent `diaries/settings.gradle` while
      preserving `diaries-responder` as a child project.
- [x] Create the Java/Javalin Gradle subproject without overwriting change
      control or adding nested Gradle root artifacts.
- [x] Add `diaries-web/build.gradle`; extend the parent version catalog where
      shared dependency/plugin versions are needed; add formatting and
      build-info generation.
- [x] Add README, AGENTS instructions and example redacted configuration.
- [x] Add fat-JAR and container skeleton.

### Phase 2 - Configuration and domain contracts

- [x] Implement CLI/config loading and environment credential resolution.
- [x] Implement immutable wire DTOs and validation.
- [x] Implement canonical topic filters/parser and payload/topic ID checks.
- [x] Implement build information.
- [x] Add responder-compatible JSON fixtures and contract tests.

### Phase 3 - Projection engine

- [x] Implement mutable updater-owned primary maps.
- [x] Implement relationship resolution and derived indexes.
- [x] Implement immutable `ProjectionSnapshot` and atomic publication.
- [x] Implement tombstones, out-of-order relationships and deterministic ordering.
- [x] Implement fragment HTML sanitization.
- [x] Add projection, sanitizer, immutability and concurrency tests.

### Phase 4 - MQTT lifecycle

- [x] Implement subscription-only Paho client and minimum topic filters.
- [x] Implement initial staging replay, acknowledgement and quiet-period readiness.
- [x] Implement reconnect with a new empty staging generation.
- [x] Implement graceful disconnect/shutdown.
- [x] Add fake-client lifecycle tests and Mosquitto integration tests.

### Phase 5 - HTTP rendering

- [x] Implement Javalin lifecycle, base path, errors and security headers.
- [x] Implement Pebble base/component templates and static assets.
- [x] Implement diary index and diary landing page.
- [x] Implement date/day rendering with ordered sanitized fragments.
- [x] Implement source-page image/transcript/overlay view.
- [x] Implement fragment permalink and canonical links.
- [x] Implement GET/HEAD, 404, 405 and unready 503 behaviour.
- [x] Add HTTP/template/accessibility tests.

### Phase 6 - Operations and delivery

- [x] Implement liveness/readiness and privacy-safe diagnostics.
- [x] Complete fat-JAR/container build and non-root runtime.
- [x] Add container health check.
- [x] Document MQTT ACL and deployment/proxy requirements.
- [x] Run synthetic end-to-end and browser smoke tests.
- [x] Verify no MQTT publish/RPC, DB or NAS dependency exists.
- [x] Verify `diaries-client` remains deployed and unaffected.
- [x] Review final diffs and record validation evidence.

## Acceptance Criteria

### Repository and Gradle structure

- [x] The parent `diaries` repository continues to declare `diaries-client`,
      `diaries-responder` and `diaries-web` as Git submodules.
- [x] Parent `diaries/settings.gradle` includes both `diaries-responder` and
      `diaries-web` as Gradle subprojects; it does not include `diaries-client`.
- [x] `diaries-web/build.gradle` defines only the web subproject's plugins,
      dependencies and tasks and can use the parent version catalog.
- [x] `diaries-web` contains no `settings.gradle`, `gradlew`, `gradlew.bat`,
      `gradle/wrapper` or `gradle/libs.versions.toml`.
- [x] Qualified `:diaries-web:*` tasks run successfully through the wrapper in
      the `diaries` root, and the root `build` continues to cover both Java
      subprojects.
- [ ] CI and the container build use the parent checkout/build context and do
      not construct a standalone Gradle root for `diaries-web`.

### Architectural separation

- [x] `diaries-client` remains the sole interactive editor and is not replaced or deprecated.
- [x] `diaries-responder` remains solely responsible for persistence, validation, MQTT RPC and canonical retained publication.
- [x] `diaries-web` has no PostgreSQL/JPA dependency.
- [x] `diaries-web` has no MQTT publisher or RPC request implementation.
- [x] `diaries-web` exposes no diary mutation HTTP endpoint or editing control.
- [x] Public browsers communicate only with `diaries-web`/image HTTP routes, never MQTT.

### Projection correctness

- [x] Canonical retained Diary, Page, Fragment and Marquee objects populate primary maps.
- [x] Empty retained payloads remove objects.
- [x] Out-of-order relationships resolve without restart.
- [x] Diary/date and source-page views include only correctly related fragments.
- [x] Fragments sort by numeric sequence then ID.
- [x] One HTTP response observes exactly one immutable snapshot generation.
- [x] Reconnect rebuilds from an empty staging generation and removes objects deleted while offline.
- [x] Malformed messages do not replace last valid data.

### HTTP rendering

- [x] Required GET/HEAD routes render semantic HTML from the current snapshot.
- [x] Day pages assemble and sanitize fragments as specified.
- [x] Source-page views reference existing responder-served images and provide transcripts.
- [x] Previous/next and index navigation is deterministic.
- [x] Missing content returns controlled 404; unready projection returns 503.
- [x] Mutation methods return 405 and cannot change projection state.
- [x] Core reading works without browser JavaScript.
- [x] Primary pages meet agreed responsive/accessibility checks.

### Security and operations

- [x] Dedicated MQTT credentials have only required subscribe permissions.
- [x] Credentials and fragment content do not appear in logs, builds or health output.
- [x] Stored fragment HTML is sanitized using a tested allowlist.
- [x] Liveness and readiness accurately distinguish HTTP process health from MQTT projection availability.
- [x] Container runs as non-root with no database or NAS mount.
- [x] Build and version information is available without exposing secrets.

### Verification

- [x] Unit, projection, HTTP and concurrency tests pass.
- [x] Mosquitto integration tests prove retained startup, update, tombstone and reconnect behaviour.
- [x] Production build and fat JAR succeed.
- [x] Container build and health/read-only smoke tests succeed.
- [ ] Side-by-side smoke test confirms an edit made through `diaries-client` appears on the next `diaries-web` GET.
- [x] No deployment step redirects editor users away from `diaries-client`.

## Validation Plan

Record commands and actual results during implementation.

### Build and tests

```text
cd diaries
gradlew.bat :diaries-web:test
gradlew.bat :diaries-web:build
gradlew.bat build
```

### Local server

```text
java -jar diaries-web/build/libs/diaries-web-fat.jar --config diaries-web/config/local.json
```

Verify:

```text
GET /health/live
GET /health/ready
GET /
GET /diaries/{id}
GET /diaries/{id}/{year}/{month}/{day}
GET /diaries/{id}/pages/{pageId}
HEAD on representative content
POST on representative content -> 405
```

### Retained model integration

With synthetic disposable topics:

1. publish retained diary/page/marquee/fragment fixtures before startup;
2. start `diaries-web` and wait for readiness;
3. verify rendered content and ordering;
4. update one retained fragment and verify the next GET changes;
5. tombstone it and verify removal;
6. disconnect the service, delete another retained object, reconnect and verify
   the absent object is removed by staging-generation rebuild;
7. verify no messages were published by `diaries-web`.

### Side-by-side application verification

Using non-production diary/test data:

1. run the normal editor, responder, broker and `diaries-web`;
2. edit a fragment through `diaries-client`;
3. observe responder transaction and retained publication;
4. request the corresponding page from `diaries-web`;
5. verify updated content appears;
6. verify `diaries-web` performed no RPC and database state changed only through
   the responder/editor flow.

### Container/deployment verification

- build the image from the validated fat JAR;
- run with read-only MQTT credentials and runtime config;
- verify health/readiness and routes through the intended HTTPS proxy;
- verify responder image URLs;
- inspect mounts/network/config to confirm no DB or NAS dependency;
- verify `diaries-client` remains independently reachable.

## Repository and Deployment Impact

### diaries-web

Owns the projection service, templates, static presentation assets, tests,
configuration schema, subproject `build.gradle`, fat JAR and container
definition. It does not own a Gradle wrapper, settings file or version catalog.

### diaries-client

No replacement, removal or source change is required. It remains the editor.
It is used in compatibility smoke tests as the source of legitimate mutations.

### diaries-responder

No persistence or RPC change is expected. Its retained DTOs must contain enough
information for the projection. If a genuine missing field is discovered, raise
a separate cross-component change and preserve backward compatibility.

### diaries parent repository

Already owns the `diaries-web` Git submodule declaration. The implementation
must add `diaries-web` to the root `settings.gradle`, extend the root version
catalog/build configuration as needed, and keep all wrapper infrastructure at
this level. Coordinated changes may also add the new service to Compose modes,
environment files, scripts and architecture documentation. These changes must
not alter existing client/responder semantics.

### Mosquitto configuration

Requires a dedicated user/password and read-only ACL. Exact changes belong in
the configuration/deployment owner and must be validated without weakening
other users.

### Pipelines and playbooks

Require component image build/publish and deployment wiring, including both
standalone/shared frontend considerations where applicable.

## Data Safety, Deployment and Rollback

`diaries-web` stores no durable diary data. It requires no migration, database
backup/restore or NAS content change.

- Deploy it additively at its own hostname/path.
- Do not replace the `diaries-client` route or image.
- Rollback stops/removes only `diaries-web` routing/container and revokes its
  read-only MQTT credentials if appropriate.
- The responder, retained model, database and editor remain operational during
  rollout and rollback.
- Never run destructive DB, Docker volume or NAS operations for this feature.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Scope drifts into replacing the editor | Non-replacement invariant, no auth/RPC/write dependencies, AGENTS rule and acceptance checks. |
| Stale objects survive offline deletion | Empty staging generation on every reconnect before atomic swap. |
| HTTP request sees partial update | Immutable snapshots published through one `AtomicReference`. |
| MQTT duplicates/hierarchy aliases create duplicates | Consume canonical lookup topics once and derive indexes. |
| Fragment cannot be linked to a diary | Retain as unresolved; do not expose until marquee/page/diary relationship resolves. |
| Stored HTML creates XSS | OWASP sanitizer allowlist plus CSP and malicious fixtures. |
| Public browser gains broker access | Server-side TCP MQTT only; dedicated secret and ACL; no browser MQTT code. |
| A read-only service gains write rights | Minimum ACL and integration test proving writes are rejected. |
| Site becomes a second source of truth | No DB/durable cache; snapshot always rebuildable from retained state. |
| Image paths differ by environment | Explicit public responder base URL and tested segment-safe URL builder. |
| Quiet-period replay is incomplete | Configurable acknowledgement/quietness/timeout, readiness false until complete, observable diagnostics. |

## Future Work Explicitly Outside This Feature

The proposal identifies legitimate later consumers/enhancements, but they must
not complicate the initial service:

- browser-live SSE or WebSocket updates;
- rendered-page cache invalidation;
- static-site export;
- search/indexing;
- EPUB/PDF/book generation;
- public APIs/feeds;
- alternate themes;
- people views;
- analytics beyond privacy-safe operations.

All remain read-only consumers if later implemented. None imply replacement of
`diaries-client`.

## Git References

### diaries-web repository references

- Branch: `main` (uncommitted implementation in the working tree)
- Commits:
- Pull request:

### diaries parent repository references

- Branch: `main` (uncommitted coordinated Gradle/Compose/ACL/docs changes)
- Commits:
- Pull request:

### diaries-client reference

- Revision used for editor/topic behaviour: `3a0728a1dd28dac0ecc1364d7658d3effcce02a4`

### diaries-responder reference

- Revision used for retained DTO contract: `090d78a10955e27c411181000b3c3c868cddc0b6`

### Configuration/pipeline/playbooks

- Repository:
- Branch:
- Commits:
- Pull request:

## Implementation and Validation Evidence

Implemented on 2026-09-01:

- registered `diaries-web` as a Gradle child of the parent `diaries` project,
  without adding nested Gradle root artifacts;
- implemented strict configuration with environment-only MQTT credentials,
  responder-compatible retained DTOs, canonical topic parsing and tombstones;
- implemented a subscription-only Paho MQTT lifecycle with initial/reconnect
  empty staging generations, replay acknowledgement/quietness, atomic immutable
  snapshots, readiness and privacy-safe diagnostics;
- implemented relationship resolution, deterministic date/sequence indexes,
  orphan diagnostics and deletion/re-addition behavior;
- implemented Javalin/Pebble GET/HEAD routes, generation ETags, controlled
  404/405/503 responses, security headers, OWASP fragment sanitization,
  responder image URLs, scan overlays and accessible transcripts;
- added build metadata, a fat JAR, a non-root multi-stage image, health check,
  example configuration, minimum read-only Mosquitto ACL, both local Compose
  services, project/parent documentation and architecture guardrails;
- preserved `diaries-client` as the existing editor and made no client or
  responder source changes.

Validation performed from the parent `diaries` checkout:

```text
gradlew.bat :diaries-web:clean :diaries-web:test :diaries-web:build :diaries-web:shadowJar
  BUILD SUCCESSFUL (21 unit, DTO, projection, concurrency, HTTP and Mosquitto tests)

gradlew.bat :diaries-responder:test
  BUILD SUCCESSFUL (109 tests, 0 failures)

gradlew.bat build
  BUILD SUCCESSFUL for both parent Java subprojects (29 tasks)

docker build -f diaries-web/Dockerfile -t diaries-web:feature-0001 .
  SUCCESS; the Docker build also ran the web tests and fat-JAR task

docker image inspect diaries-web:feature-0001
  User=diaries; exposed port=8082; readiness health check present

docker compose ... config --services
  both local-docker-build and local-published-smoke contain database, MQTT,
  responder, web and the unchanged client

live image smoke
  /health/live=200, /health/ready=503 without MQTT, content=503 with Retry-After,
  POST content=405 with Allow: GET, HEAD
```

The disposable authenticated Mosquitto integration test proves retained
startup, a live update appearing on the next HTTP GET, tombstone removal,
read-only ACL rejection, broker loss, automatic reconnect, and replacement by
a new empty generation so offline-deleted state cannot survive.

An in-app browser smoke using synthetic content verified index -> diary -> day
-> source/transcript navigation, semantic landmarks, sanitizer output, the
image fallback caption, phone and desktop layouts without horizontal overflow,
and the styled 404 page without console errors.

Coverage after the full suite was 81.3% instructions, 77.6% lines and 97.5%
classes. The only validation that still requires the user's configured
local environment is the literal side-by-side edit through the real Angular
`diaries-client` and a production reverse-proxy/playbook deployment. The
automated MQTT-to-HTTP test covers the same retained-state propagation contract
without using private diary data.

## Completion Summary

The complete application implementation and repository-owned deployment
configuration are present. The change remains in progress until the explicit
real-editor side-by-side and owning production playbook/proxy checks are
performed; those require external credentials, data and deployment repositories
that were not placed in scope. No database, NAS or existing retained content was
modified during implementation or validation.

## Completed Date

Pending the external side-by-side/deployment verification described above.

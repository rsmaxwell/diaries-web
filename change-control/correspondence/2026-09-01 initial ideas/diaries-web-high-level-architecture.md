# Diaries Web – High-Level Architecture Proposal

## Overview

Yes — the current Diaries architecture is well suited to adding a sibling application that publishes a live, read-only HTTP view of the diaries.

The proposed application, `diaries-web`, would connect to the same MQTT broker as the existing Diaries client and responder, subscribe to the retained topic tree, reconstruct the live diary model in memory, assemble fragments into pages or day views, apply presentation structure and styling, and serve the resulting content over HTTP.

The main architectural idea is:

```text
                         PostgreSQL
                             ▲
                             │
                     diaries-responder
                             │
                    retained MQTT data
                             ▼
                        Mosquitto
                         /      \
                        /        \
                       ▼          ▼
              diaries-client    diaries-web
              Angular editor    publisher/viewer
                                  │
                                  ▼
                               HTTP
                                  │
                                  ▼
                             Web browser
```

The existing `diaries-responder` would remain responsible for persistence, validation, MQTT RPC, and publishing the canonical retained application model. The new `diaries-web` application would be a read-only projection of that model.

## Why a Separate `diaries-web` Application Is Preferable

A separate sibling application is cleaner than extending `diaries-responder` with public website rendering responsibilities.

The responder already has a clear role:

- PostgreSQL/JPA persistence
- MQTT RPC handling
- retained topic publication
- synchronisation of broker state
- serving existing static diary files and images

`diaries-web` can have a much narrower responsibility:

- subscribe to retained MQTT topics
- maintain a live in-memory model
- assemble diary content
- apply HTML structure and styles
- serve public/read-only HTTP pages

This gives a clean separation between the editable application model and the public presentation layer.

## Live Topic-Tree Model

The Angular client already treats the retained MQTT topic tree as a live application model.

Typical retained topics include structures such as:

```text
diaries/diaries/{id}
diaries/diaries/{diaryId}/+
diaries/pages/{id}
diaries/fragments/{id}
diaries/dates/{year}/{month}/{day}/+
diaries/marquees/{id}
```

The client keeps local objects up to date as MQTT messages arrive.

`diaries-web` could follow the same principle server-side.

On startup it would subscribe to the relevant retained topic trees and build an in-memory model representing objects such as:

```text
Diary
 ├── Page
 │    ├── Fragment
 │    ├── Fragment
 │    └── ...
 ├── Page
 │    └── ...
```

It would also maintain the date-based projection represented by topics such as:

```text
diaries/dates/yyyy/mm/dd/+
```

When a retained MQTT object changes, `diaries-web` updates its in-memory state.

When an object is removed through the retained topic model, the corresponding in-memory object is removed.

## Rendering a Diary Day

Suppose the retained topic tree contains fragments for:

```text
1829-06-14
```

with sequence numbers such as:

```text
1000
2000
3000
4000
```

`diaries-web` could assemble them into HTML such as:

```html
<article class="diary-day">

  <header class="diary-date">
    Sunday 14 June 1829
  </header>

  <section class="fragment">
    ...
  </section>

  <section class="fragment">
    ...
  </section>

  <section class="fragment">
    ...
  </section>

</article>
```

The application would sort fragments by `sequence`, following the same model already used by the Angular client.

The fragment content would form the diary body, while `diaries-web` would add the presentation framework around it.

## Separation of Content and Presentation

The MQTT topic tree should remain the canonical content model.

It would contain application data such as:

```text
MQTT topic tree
    │
    ├── diary metadata
    ├── pages
    ├── fragments
    ├── dates
    └── marquees
```

The `diaries-web` application would own the presentation layer:

```text
diaries-web
    │
    ├── HTML document structure
    ├── CSS
    ├── navigation
    ├── fixed headers
    ├── fixed footers
    ├── JavaScript
    └── public URL routing
```

A rendered page could therefore look conceptually like:

```html
<!doctype html>
<html>
<head>
    <title>...</title>
    <link rel="stylesheet" href="/assets/diary.css">
</head>

<body>

<header class="site-header">
   ...
</header>

<nav>
   ...
</nav>

<main>
   <!-- assembled diary content -->
</main>

<script src="/assets/diary.js"></script>

</body>
</html>
```

This means the retained MQTT data remains independent of a particular website design.

## What “Live” Could Mean

There are two useful levels of live behaviour.

### Server-Live

The simplest design is for `diaries-web` to maintain a continuously updated in-memory MQTT model.

Then every new HTTP request renders from the latest model:

```text
GET /diaries/1829/06/14
       │
       ▼
read current in-memory topic-tree model
       │
       ▼
generate HTML
```

If a fragment is edited in the Angular application, the next HTTP request immediately sees the new content.

This requires no browser-side MQTT, WebSocket, or Server-Sent Event support.

### Browser-Live

A later enhancement could update pages that are already open in a browser.

The flow could be:

```text
Angular editor
      │
      │ MQTT RPC update
      ▼
Responder
      │
      ├── update PostgreSQL
      │
      └── publish retained topic
                 │
                 ▼
              Mosquitto
                 │
                 ▼
             diaries-web
                 │
                 │ SSE/WebSocket
                 ▼
              browser
```

In that model, a changed fragment could appear in an already-open diary page without requiring a manual refresh.

This would be a useful later feature, but it is not necessary for the first implementation.

## Avoid Direct Browser-to-MQTT Access

A public website could technically connect directly to Mosquitto over WebSockets.

That is probably not the best design.

Direct browser-to-MQTT access would require exposing:

- broker connectivity to public clients
- MQTT credentials or anonymous access
- internal topic structure
- topic ACL decisions to every browser

A cleaner security boundary is:

```text
Browser -> HTTPS -> diaries-web -> MQTT
```

Only `diaries-web` needs broker credentials.

The public browser sees only ordinary HTTP or HTTPS.

## No PostgreSQL Dependency in `diaries-web`

A major architectural advantage is that `diaries-web` would not need direct database access.

Its authoritative data source would be the retained MQTT model.

The data flow becomes:

```text
PostgreSQL
     │
Responder
     │
 MQTT retained model
     │
 ┌───┴────┐
 ▼        ▼
Editor   Website
```

This makes the retained MQTT tree a genuine application-wide projection of the diary model.

It also provides a useful architectural property:

> If the retained MQTT topic tree contains sufficient information to reconstruct the diary website, then the topic tree is a complete representation of the published diary model.

That is a desirable characteristic because multiple consumers can depend on the same canonical application projection.

## HTTP Rendering Strategy

There are two reasonable implementation approaches.

### Render on Every Request

For example:

```text
GET /diaries/1829/06/14
       │
       ▼
read in-memory model
       │
       ▼
assemble fragments
       │
       ▼
render HTML
```

This is simple and likely fast enough for the expected workload.

### Regenerate Cached HTML on MQTT Changes

An alternative is:

```text
MQTT fragment changed
       │
       ▼
determine affected date/page
       │
       ▼
regenerate HTML
       │
       ▼
cache
```

Then HTTP requests simply return cached rendered content.

This may be useful later, but it adds invalidation and dependency complexity.

For an initial implementation, render-on-request from an in-memory MQTT model is likely the simpler and safer design.

## Images and Static Diary Content

The existing responder already serves diary image/content files over HTTP.

That means generated HTML can reference those existing resources rather than requiring `diaries-web` to mount the NAS filesystem directly.

For example:

```html
<img src="/files/...">
```

or whatever canonical path scheme is chosen.

This keeps `diaries-web` independent of the NAS filesystem and reduces deployment complexity.

## MQTT Permissions

`diaries-web` should be read-only.

Its broker identity should allow subscription to only the retained topics needed to render the website.

Conceptually:

```text
diaries-client
    read + MQTT RPC write

diaries-responder
    database + publish + MQTT RPC

diaries-web
    retained-topic read only
```

The exact ACL should be as narrow as practical.

The application should not have permission to perform update RPC operations.

## Possible Project Structure

A natural repository layout is:

```text
diaries-application/
    diaries/
        diaries-client/
        diaries-responder/
        diaries-web/
```

A small Java implementation could be structured as:

```text
diaries-web
├── src/main/java/
│   └── ...
│       ├── mqtt/
│       │   └── TopicTree.java
│       ├── model/
│       ├── renderer/
│       │   ├── DiaryRenderer.java
│       │   ├── DayRenderer.java
│       │   └── FragmentRenderer.java
│       └── http/
│           └── WebServer.java
│
├── src/main/resources/
│   ├── templates/
│   │   ├── diary.html
│   │   ├── day.html
│   │   └── fragment.html
│   └── static/
│       ├── diaries.css
│       └── diaries.js
│
└── build.gradle
```

A lightweight HTTP framework such as Javalin or Jetty would probably be sufficient.

Spring Boot is also possible, but may be more framework than this application initially needs.

## URL Design

The retained date projection suggests a natural public URL structure.

For example:

```text
/diaries/1829/06/14
```

maps naturally to retained data under:

```text
diaries/dates/1829/6/14/+
```

Possible routes could include:

```text
GET /
GET /diaries/{diaryId}
GET /diaries/{diaryId}/{year}/{month}/{day}
```

Additional routes could later support:

- page navigation
- people
- marquees
- search
- year/month indexes
- previous/next day links

## Suggested Initial Scope

A first implementation could remain deliberately small.

1. Connect to Mosquitto.
2. Subscribe to the required retained Diary, Page, Fragment, Date, and related topic trees.
3. Reconstruct those objects into an in-memory model.
4. Keep the model updated when MQTT messages change.
5. Expose basic HTTP routes.
6. Assemble fragments for a date or page.
7. Sort fragments by `sequence`.
8. Render the existing fragment HTML/content.
9. Add common page header, footer, navigation, CSS, and JavaScript.
10. Reference existing diary image URLs.
11. Make the application read-only.
12. Do not access PostgreSQL directly.
13. Do not perform MQTT RPC writes.

This produces a useful live public website without adding unnecessary complexity.

## Potential Future Uses

Once the retained topic tree is treated as a complete published diary model, other consumers become possible:

```text
                    retained MQTT diary
                           │
             ┌─────────────┼──────────────┐
             ▼             ▼              ▼
       Angular editor   HTML website   EPUB/PDF generator
```

Other future possibilities include:

- static-site export
- EPUB generation
- PDF/book generation
- full-text indexing
- search services
- archive snapshots
- public APIs
- alternate website themes

All of these could consume the same canonical retained topic model.

## Recommendation

The preferred architecture is therefore:

- keep `diaries-responder` focused on persistence, MQTT RPC, and canonical retained-state publication
- keep `diaries-client` as the interactive editing application
- introduce `diaries-web` as a read-only sibling application
- have `diaries-web` consume the retained MQTT topic tree
- maintain an in-memory live model
- render HTTP pages from that model
- keep presentation concerns such as HTML scaffolding, CSS, navigation, fixed headers, and JavaScript in `diaries-web`
- avoid direct PostgreSQL access
- avoid browser-to-MQTT connectivity for the public site
- start with server-live rendering and add browser-live updates later only if useful

This makes good use of the architecture already present in Diaries and creates a clean foundation for a public/read-only diary website.

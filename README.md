# Diaries web

`diaries-web` is the server-side, read-only web projection for Diaries. It does
not replace or deprecate `diaries-client`: editors continue to use the Angular
client to authenticate, lock, create and maintain fragments. The
`diaries-responder` remains authoritative for validation, persistence, MQTT
RPC, locking and publication of the canonical retained model.

The service subscribes to four retained MQTT lookup families, builds an
in-memory model, and renders ordinary HTML for browser GET requests:

```text
diaries-responder -> retained MQTT objects -> diaries-web projection -> HTML
       |                                                  |
       +-> PostgreSQL durable source                      +-> no writes
```

There is no database/JPA dependency, MQTT publisher, RPC client, NAS mount or
editing endpoint in this project. Page scans remain served by the responder's
existing read-only HTTP/static route.

## Parent project ownership

This directory is both a Git submodule of `diaries` and the Gradle subproject
`:diaries-web`. The Gradle root, wrapper, plugin catalog and version catalog are
owned by the parent `diaries` checkout. Run all Gradle commands from there; do
not add a wrapper, `settings.gradle`, or `gradle/` directory here.

```powershell
cd diaries
.\gradlew.bat :diaries-web:test
.\gradlew.bat :diaries-web:build
.\gradlew.bat :diaries-web:shadowJar
```

The fat JAR is written under `diaries-web/build/libs/`.

For a browser-only smoke check with synthetic (non-MQTT) data, run
`:diaries-web:runSyntheticSite` and open `http://127.0.0.1:18082`. The harness
lives in the test source set and is never packaged in the application JAR.

Windows development helpers are under `scripts/windows`:

```text
build.bat                  test and build the web subproject
clean.bat                  remove Gradle-generated web output
prepare.bat                regenerate build-info.properties
getDeps.bat                create the Application-plugin runtime distribution
run-web.bat [config]       build and run that distribution
deploy.bat [image]         build a local image; never push it
```

`run-web.bat` defaults to
`%USERPROFILE%\.diaries\diaries-web.json` and requires the two MQTT credential
environment variables described below. `deploy.bat` defaults to the local image
name `diaries-web:local`; registry publication remains the responsibility of
the `diaries-web-pipeline` Jenkins job.

## Retained projection

The subscriber uses QoS 1 and only these canonical lookup filters:

```text
diaries/diaries/+
diaries/pages/+
diaries/fragments/+
diaries/marquees/+
```

Empty retained payloads are tombstones. Topic and payload IDs must agree;
invalid payloads are rejected without replacing the last valid snapshot.
Relationships resolve as Fragment -> Marquee -> Page -> Diary, irrespective of
arrival order. Unresolved objects remain diagnostic data but are not rendered.
Fragments are ordered by numeric sequence and then ID.

Startup and reconnect each create a new empty staging generation. Once every
subscription is acknowledged and the configured quiet period passes, one
immutable snapshot is swapped atomically. Public content stays unavailable
with HTTP 503 while replaying, disconnected or failed. This prevents entities
deleted while the service was offline from surviving reconnection.

## HTTP routes

With an empty base path the routes are:

```text
GET/HEAD /                                      diary index
GET/HEAD /diaries                              diary index alias
GET/HEAD /diaries/{diaryId}                    diary month/page contents
GET/HEAD /diaries/{diaryId}/{year}/{month}     month reader
GET/HEAD /diaries/{diaryId}/{year}/{month}/{day}
GET/HEAD /diaries/{diaryId}/pages/{pageId}     scan, overlays and transcript
GET/HEAD /fragments/{fragmentId}                canonical month redirect
GET/HEAD /about
GET/HEAD /health/live
GET/HEAD /health/ready
```

The diary landing page lists published months and all source pages. The month
reader groups transcription fragments by date and displays the selected
fragment's source page with one read-only marquee. Previous/next navigation
skips empty months. Legacy day and fragment routes redirect to the containing
month, while source-page routes remain available for old bookmarks and pages
without transcription.

All links honor `http.basePath`. Content POST, PUT, PATCH and DELETE requests
return 405 with `Allow: GET, HEAD`. Pages are semantic and usable without
JavaScript. Browser JavaScript progressively enhances the month reader with
fragment selection, history, mouse/touch pan and zoom, Fit page, Fit selection
and a narrow-screen expanded viewer. It never publishes MQTT data or edits a
marquee. Fragment HTML is sanitized with an explicit OWASP allowlist before
template rendering. Security headers include a restrictive CSP, and
generation-based ETags support conditional GETs.

`/health/live` reports process/HTTP health. `/health/ready` is 200 only after a
complete projection generation is available; otherwise it returns 503. Health
and HTML output contain counts and build metadata, never MQTT credentials or
fragment content diagnostics.

## Configuration and secrets

Copy `config/diaries-web.example.json` for a directly run service. The strict
JSON schema has `http`, `mqtt`, `projection`, `content` and `site` sections.
MQTT credentials are deliberately absent from the file and are required in:

```text
DIARIES_WEB_MQTT_USERNAME
DIARIES_WEB_MQTT_PASSWORD
```

The committed Docker example uses the local Compose service names. Adjust
`http.publicBaseUrl` and `content.publicResponderBaseUrl` for the externally
visible HTTPS/proxy routes. The latter must reach the existing responder image
route; browsers do not need access to the internal `content.responderBaseUrl`.

For development infrastructure, run Mosquitto/responder as usual and start:

```powershell
$env:DIARIES_WEB_MQTT_USERNAME='diaries-web'
$env:DIARIES_WEB_MQTT_PASSWORD='<local password>'
.\gradlew.bat :diaries-web:run --args='--config diaries-web/config/diaries-web.example.json'
```

Never commit credentials. Add `diaries-web` to the external Mosquitto password
source and regenerate the ignored password database.

## Container

The image must be built from the parent `diaries` directory because it uses the
parent Gradle wrapper and version catalog:

```powershell
docker build -f diaries-web/Dockerfile -t diaries-web:local .
```

The multi-stage image runs as a non-root user, exposes only port 8082, has no DB
or NAS volume, and checks `/health/ready`. Mount configuration read-only at
`/config/diaries-web.json` and inject the two credential environment variables.

## Dependency choices

The implementation pins Javalin 7.2.3, Pebble 4.1.2, OWASP Java HTML Sanitizer
20260313.1, AssertJ 3.27.7, Awaitility 4.3.0 and Testcontainers 2.0.5 in the
parent version catalog. Existing parent versions supply Java 25, Jackson,
Paho MQTT v5, Log4j and JUnit.

## Troubleshooting

- Persistent 503: inspect `/health/ready`, broker reachability, credentials,
  ACL filters and retained replay timeout.
- Missing fragment: inspect relationship counts and confirm its marquee, page
  and diary retained objects all exist with matching IDs.
- Stale object after reconnect: readiness must remain false until a fresh empty
  staging generation swaps; never reuse the prior active map as staging.
- Missing scan: inspect the rendered URL, public responder base URL, reverse
  proxy/static route and the responder/NAS path. `diaries-web` does not mount or
  read NAS content.

Rollback consists of stopping/removing only `diaries-web` and its public route.
The editor, responder, database and retained state are unchanged.

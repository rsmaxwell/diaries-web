# Diaries Change Control

This directory provides lightweight, file-based change control for the Diaries application. It records defects, features, and technical or operational changes in the same repository as the application-level configuration that joins the client and responder together.

The purpose is to retain enough context to understand why a change was requested, how it affects the complete Diaries system, what was implemented, and how the result was verified. The process should remain proportionate to the risk and size of the change.

## System Context

Diaries is an end-to-end system comprising:

- the Angular/TypeScript `diaries-client`;
- the Java `diaries-responder`;
- MQTT RPC request/reply messaging;
- a retained MQTT topic tree used as the live application model;
- PostgreSQL with JPA/Hibernate as the durable source of truth;
- HTTP/static file serving for diary images and uploaded files;
- Docker and Docker Compose for local execution;
- Jenkins pipelines for component builds and images;
- Ansible and Docker Compose for production deployment.

The central architectural principles are:

```text
RPC requests express intent.
Retained MQTT topics publish reality.
The database is the durable source of truth.
```

A change that appears isolated to the client or responder may affect the contract between them. Change records should consider the complete path whenever authentication, MQTT, retained state, persistence, files, configuration, or deployment is involved.

## Directory Structure

The active change-control states are:

```text
change-control/
├── README.md
├── todo/
├── in-progress/
├── complete/
└── will-not-fix/
```

- `todo` contains accepted changes that have not started.
- `in-progress` contains changes currently being investigated or implemented.
- `complete` contains finished changes retained as a permanent record.
- `will-not-fix` contains changes that were considered but intentionally not implemented.

Each change has its own directory. Move the entire change directory between state directories; do not create disconnected copies of the same change record.

Completed and will-not-fix records are historical evidence. They may contain snapshots or implementation packages, but those copies are not the current source of truth. The active application tree takes precedence.

## Change Identifiers

Assign each change a permanent numeric identifier and a type:

- `DEF` — defect;
- `FEAT` — feature;
- `CHG` — technical, maintenance, deployment, or operational change.

For new items, use this folder naming convention:

```text
NNNN-TYPE - short description
```

Examples:

```text
0042-DEF - fragment unlock leaves stale retained state
0043-FEAT - display uploaded files by diary
0044-CHG - improve published image smoke testing
```

Use the next available identifier across all state directories. Preserve identifiers and existing folder names when moving or updating older records, even where an older naming style differs from the current convention.

## Change Record Contents

Every change directory should contain a `README.md` as its primary record. Add supporting files only when they improve traceability or make the work reproducible.

Useful supporting directories include:

```text
evidence/
designs/
logs/
notes/
screenshots/
sql/
validation/
```

Supporting evidence may include:

- browser console or network output;
- MQTT request, reply, and retained-topic observations;
- Mosquitto logs;
- responder logs;
- PostgreSQL queries and results with secrets removed;
- Docker or Compose configuration and health output;
- screenshots;
- test and build results;
- deployment output;
- migration, backup, or rollback notes.

Do not store credentials, tokens, signing secrets, private keys, or sensitive diary content in a change record.

## Change Record Template

Use the following template as a starting point. Remove sections that genuinely do not apply and add detail where the change has significant risk.

```markdown
# NNNN-TYPE - Short description

## Type

Defect | Feature | Change

## Status

To do | In progress | Complete | Will not fix

## Priority

Low | Medium | High | Critical

## Opened

YYYY-MM-DD

## Summary

Describe the problem or requested outcome.

## Background

Explain why the change is needed and record relevant history or constraints.

## Observed Behaviour

For a defect, describe what currently happens and distinguish observed evidence from assumptions.

## Expected Behaviour

Describe the intended result in terms that can be verified.

## Reproduction or User Workflow

Provide repeatable steps, including the execution mode and actual component/image versions where relevant.

## Evidence

List supporting files, logs, screenshots, queries, or MQTT observations.

## Analysis

Record the investigation, confirmed root cause, constraints, and cross-component interactions.

## Scope

### Diaries Client

Describe Angular, browser configuration, authentication, MQTT, model, or presentation changes.

### Diaries Responder

Describe handler, validation, authentication, persistence, locking, retained publication, or file-serving changes.

### MQTT Contract and Retained State

Describe request/reply payloads, topics, correlation, error handling, subscriptions, and retained publications.

### Database

Describe PostgreSQL, JPA/Hibernate, schema, migration, compatibility, backup, or restore implications.

### Images and Static Files

Describe metadata, URL construction, nginx/static routing, volume mounts, NAS paths, or filesystem implications.

### Build, Configuration, and Deployment

Describe Docker, Compose, environment variables, scripts, Jenkins, image tags, Ansible, nginx, systemd, or production implications.

### Documentation

List documentation that must be updated.

## Implementation Steps

- [ ] Confirm or reproduce the current behaviour.
- [ ] Identify affected components and contracts.
- [ ] Add or update appropriate tests.
- [ ] Implement the smallest coherent change.
- [ ] Update configuration and documentation where required.
- [ ] Run the relevant tests, builds, and smoke checks.
- [ ] Review the final Git diff for unrelated changes.
- [ ] Record validation evidence and remaining risks.

## Acceptance Criteria

- [ ] The required behaviour is unambiguous.
- [ ] Client and responder behaviour remain compatible.
- [ ] Database and retained MQTT state remain consistent.
- [ ] Authentication and authorisation remain correct.
- [ ] Relevant tests and builds pass.
- [ ] Applicable local or production modes have been considered.
- [ ] Documentation and configuration match the implemented behaviour.
- [ ] Deployment and rollback implications are understood.

## Validation

### Client Tests and Build

Command:
Result:

### Responder Tests and Build

Command:
Result:

### MQTT and Database Verification

Method:
Result:

### Docker or Application Smoke Test

Mode:
Command or method:
Result:

### Manual Verification

Method:
Result:

## Git References

Record only repositories affected by the change.

### Diaries Parent Repository

- Branch:
- Commits:
- Pull request:

### Diaries Client

- Branch:
- Commits:
- Pull request:

### Diaries Responder

- Branch:
- Commits:
- Pull request:

### Pipelines or Production Playbooks

- Repository:
- Branch:
- Commits:
- Pull request:

## Deployment and Rollback Notes

Describe configuration changes, database compatibility, image selection, deployment order, rollback steps, and any backup requirements.

## Completion Summary

Summarise what changed, why the solution was chosen, the validation performed, limitations, and follow-up work.

## Completed Date

YYYY-MM-DD
```

## Workflow

### 1. Create the Change

Create the change directory under `todo` using the next available identifier. Add a `README.md` that defines at least the summary, expected behaviour, scope, and acceptance criteria.

### 2. Investigate and Define

Before implementation:

- identify the execution mode involved;
- establish the actual client and responder source or image versions;
- separate observed evidence from suspected causes;
- inspect both sides of an MQTT or authentication contract;
- compare database, responder, retained-topic, and client state where relevant;
- identify data safety, compatibility, and deployment risks;
- define proportionate validation.

### 3. Start Work

Move the complete change directory from `todo` to `in-progress`. Update its status and record any relevant branch references.

Do not discard existing local work. Inspect Git status and relevant diffs before editing files.

### 4. Implement and Verify

Make the smallest coherent change that satisfies the acceptance criteria. Consider all affected layers rather than treating a visible symptom as the complete problem.

For MQTT RPC changes, verify:

1. the Angular request payload and request topic;
2. responder handler registration and implementation;
3. authentication and authorisation;
4. reply payload, reply topic, correlation, timeout, and error handling;
5. resulting retained-topic publication;
6. the client subscription and state transition.

For state consistency changes, compare:

```text
PostgreSQL state
        |
Responder state and transaction result
        |
Retained MQTT topic state
        |
Angular client state
```

For image or static-file changes, verify the metadata, generated URL, browser response, routing, container mount, host/NAS path, and file permissions.

### 5. Complete the Change

Before moving an item to `complete`:

- confirm every acceptance criterion;
- record tests, builds, smoke tests, and manual checks actually performed;
- identify checks that could not be performed;
- record affected repositories and Git references;
- document deployment, compatibility, backup, and rollback implications;
- update affected documentation;
- inspect the final diff for accidental or unrelated changes;
- complete the summary and completion date.

Move the whole change directory to `complete` only when the requested outcome has been achieved.

### 6. Close Without Implementing

Move an item to `will-not-fix` when the decision not to implement it is deliberate. Record:

- the reason for the decision;
- evidence considered;
- risks accepted;
- alternatives or mitigations;
- conditions that would justify revisiting the decision;
- the decision date.

## End-to-End Investigation Guidance

### Authentication and Sessions

Check client access and refresh tokens, expiry and refresh behaviour, MQTT connection state, ACL permissions, responder validation, request retries, browser refresh, and sign-out behaviour.

### MQTT and Retained Topics

Check exact topic names, payload properties, IDs, null handling, user properties, response topics, correlation data, timeouts, retries, retained flags, deletion/tombstone behaviour, reconnects, and stale retained messages.

### Database

Distinguish between an empty database, an inaccessible database, the wrong database or volume, incorrect credentials, schema mismatch, missing rows, and retained-state inconsistency. Do not use destructive database operations without explicit approval and an appropriate backup plan.

### Docker and Configuration

Identify the Compose file, environment files and overrides, project name, service, running container, image ID and tag, volume mounts, ports, and health state. Do not infer a running version from the local source tree.

### Production

Consider both standalone and shared frontend modes. Where production configuration lives in a separate playbooks repository, record the repository and inspect role defaults, inventory overrides, generated Compose configuration, nginx configuration, scripts, and systemd units as applicable.

## Validation by Change Type

Use validation proportional to the change:

- Angular presentation changes: relevant client tests and a production client build.
- Java internal changes: relevant responder tests and a responder build using the Gradle wrapper.
- MQTT contract changes: client tests/build, responder tests/build, request/reply compatibility, and retained-topic behaviour.
- Database changes: responder tests/build, schema or migration review, existing-data compatibility, and backup/restore implications.
- Docker or configuration changes: `docker compose config`, the appropriate local mode, health/status checks, and an application smoke test.
- Production or Ansible changes: syntax/configuration review, generated configuration review, idempotency consideration, and both frontend topologies where applicable.

Do not report a test or build as passing unless it was actually run successfully.

## Local Execution Modes

Record which mode was used for reproduction and verification:

### development-infrastructure

PostgreSQL and Mosquitto run in Docker while the client and responder normally run directly from the Windows development environment.

### local-docker-build

The complete application runs in containers built from the current local source. Confirm that stale published images are not being used.

### local-published-smoke

The application runs from published client and responder images. Record the resolved image names, tags, and embedded build information.

### production

Production is deployed using Ansible and Docker Compose and may use either a standalone or shared frontend. Inventory variables can override normal role defaults, including component image tags.

## Repository and Documentation References

The main active documentation is:

- `README.md`;
- `ARCHITECTURE.md`;
- `diaries-client/README.md`;
- `diaries-responder/README.md`;
- applicable documents under `docs/`;
- current Compose, environment, script, and configuration files.

Diaries client and responder source are separate Git repositories/submodules. Pipeline and production changes may involve additional repositories. A single change record should link every affected repository while retaining the end-to-end explanation in one place.

Do not use source snapshots inside historical change-control implementation packages as the current application source.

## Safety

Change-control work does not authorise destructive operations. Obtain explicit approval before:

- deleting, resetting, or replacing a database;
- removing Docker volumes;
- deleting or modifying NAS diary content;
- overwriting uncommitted changes;
- running destructive Git cleanup or reset operations;
- modifying a separate deployment repository outside the task scope.

Use supplied backup and restore scripts where appropriate, preserve secrets outside version control, and redact sensitive values from recorded evidence.

## Principles

Every change should have:

- a stable identifier;
- a clear outcome;
- enough evidence to distinguish symptoms from root cause;
- an explicit scope across affected components;
- testable acceptance criteria;
- proportionate validation;
- deployment and data-safety consideration;
- relevant Git references;
- a completion or will-not-fix rationale.

The process exists to make each Diaries change understandable, safe, verifiable, and traceable without adding unnecessary ceremony.

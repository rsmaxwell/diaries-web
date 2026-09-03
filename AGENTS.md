# diaries-web guardrails

`diaries-web` is a read-only, server-rendered projection. It complements and
must not replace `diaries-client`, which remains the interactive editor.

- Consume only the responder's canonical retained Diary, Page, Fragment and
  Marquee lookup topics.
- Do not add MQTT publishing, MQTT RPC, PostgreSQL/JPA, filesystem mutation,
  authentication editing flows, or content-changing HTTP routes without an
  explicit approved architecture change.
- Keep the responder authoritative for validation, persistence, locking and
  retained publication.
- Rebuild an empty staging projection after every MQTT reconnect and expose
  content only after an atomic ready swap.
- Sanitize stored fragment HTML before rendering and preserve GET/HEAD-only
  content routes.
- Build this Git submodule as the `:diaries-web` Gradle subproject using the
  wrapper and version catalog in the parent `diaries` repository. Do not add a
  nested wrapper, `settings.gradle`, or version catalog.

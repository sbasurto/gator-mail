# Repository Guidelines

## Project Structure & Module Organization

Application code lives in `src/main/java/gator/mail`; the standalone filter service is under the `filter` package. Web assets and servlet configuration are in `web/`, while screen definitions are in `src/main/resources/gator-mail/screens/`. The `keycloak-provider/` subproject builds the custom Keycloak user-storage provider, and `keycloak-theme/` contains its login theme. Database installation, migration, benchmark, and reversible test scripts live in `db/`. Deployment helpers are in `deploy/`, with operational documentation in `docs/`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper and Java 21. Keep `gator-lib` and `gator-lib-web-gui` as sibling directories when developing locally; `settings.gradle` includes them automatically.

- `./gradlew clean check war` — compile all projects, run self-checks, and create `dist/gator-mail.war`.
- `./gradlew check` — run the main, filter, and Keycloak assertion-based checks.
- `./gradlew selfTest` — run the main application self-check only.
- `./gradlew filterSelfTest` — verify filter-service behavior.
- `./gradlew :keycloak-provider:selfTest` — verify password-provider behavior.

Run SQL checks such as `db/mail_cache_test.sql` against a disposable PostgreSQL database with `psql -v ON_ERROR_STOP=1 -f ...`. Follow each script's transaction and rollback instructions.

## Coding Style & Naming Conventions

Use four-space indentation and UTF-8. Follow existing Java style: packages are lowercase (`gator.mail`), types use `UpperCamelCase`, methods and variables use `lowerCamelCase`, and constants use `UPPER_SNAKE_CASE`. Keep servlet handlers and helpers focused; reuse JDK APIs and existing project utilities before adding dependencies. JavaScript uses two-space-style visual nesting, `const`/`let`, and semicolons. No formatter or linter is configured, so match neighboring code.

## Testing Guidelines

Tests are executable `*SelfCheck` classes under `src/test/java` and `keycloak-provider/src/test/java`; assertions must be enabled, which Gradle tasks already do. Add the smallest regression assertion to the relevant self-check. Database tests use `*_test.sql`; keep them reversible and safe for disposable environments. There is no coverage threshold.

## Commit & Pull Request Guidelines

Prefer concise imperative commits, following recent scoped Conventional Commit subjects such as `fix(mail): recover from expired OAuth callbacks` and `feat(mail): enable personal contact management`. Keep each commit focused. Pull requests should explain user-visible behavior, list verification commands, identify database/configuration/deployment changes, link the relevant issue when available, and include screenshots for JSP, theme, or CSS changes. Never commit credentials, tokens, generated `dist/` artifacts, or environment-specific secrets.

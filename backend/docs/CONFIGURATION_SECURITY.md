# Local and deployment configuration safety

The production application configuration now requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` from external configuration. It has no database-creation JDBC option and uses Hibernate `ddl-auto: validate`; startup will fail if the schema is absent or mismatched rather than creating or altering it. An operator-supplied `DB_URL` must not add `createDatabaseIfNotExist=true`.

Mail credentials and the Google OAuth client ID remain optional environment values, matching the existing ability to run without those integrations configured. H2 tests continue to use `application-test.yml`, whose in-memory datasource and `create-drop` setting are isolated to the `test` profile.

Copy `.env.example` to an ignored `.env`, replace placeholders locally, and never commit `.env`. For VS Code, launch Spring Boot from `backend/` so the existing optional `.env` import is resolved from that directory. Deployment secrets belong in the deployment secret manager/environment.

## Credential history review

The baseline commit `746978dfa242d47aefb3ba1f14ea235911e9080e` contains credential-like literal fallbacks in `backend/src/main/resources/application.yaml` for database username/password and the JWT signing key; their values are intentionally not reproduced here. The current commit removes them from the working configuration, but does not rewrite history. Treat the historical database password and JWT signing key as exposed: rotate them if they were ever used by a reachable environment, and ensure no deployed environment relied on the fallback. The OAuth client ID is an identifier rather than a secret. No credential rotation or external service change was performed.

# Isolated MySQL schema integration tests

`MySqlSchemaCompatibilityIT` is an opt-in test for MySQL-specific behavior. The normal H2 suite does not discover or run it. The `mysql-it` Maven profile invokes it through Failsafe and enables a pre-context guard.

## Target safety

The only accepted target is a separately approved, disposable database named `drawing_course_it` on the local MySQL server at `127.0.0.1:3306`. The test requires all of the following before Spring creates a datasource:

- Maven profile property `mysql.it.enabled=true` (provided only by `-Pmysql-it`)
- `MYSQL_IT_URL=jdbc:mysql://127.0.0.1:3306/drawing_course_it`
- `MYSQL_IT_DATABASE_ACK=drawing_course_it`
- nonblank `MYSQL_IT_USERNAME` and a defined `MYSQL_IT_PASSWORD`
- effective Spring datasource URL exactly equal to the expected URL
- active `mysql-it` Spring profile, Hibernate `ddl-auto=validate`, and Flyway clean disabled

Do not set `MYSQL_IT_URL` to `drawing_course_dev`, another database, a remote host, or a URL with a different port. Do not reuse `DB_URL`; it is deliberately not used by this test profile. Do not run the test until a separate disposable database has been explicitly approved and prepared. The test profile enables Flyway, so its first context startup creates the application schema in that test database. It never drops the schema; test data is transaction-rolled back.

## Run after isolated target approval

After separately approving and preparing an empty `drawing_course_it` database, have a local DBA create a dedicated test account scoped only to `drawing_course_it`. It needs schema/data privileges for Flyway and test fixtures (`CREATE`, `ALTER`, `INDEX`, `REFERENCES`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`); it does not need global privileges or `DROP`. The DBA should set its password interactively and grant access only from the local loopback client.

In PowerShell, from `backend`, set the variables only for the current shell. The password prompt is hidden; do not put the password in a command line, tracked file, or shell history. Then run:

```powershell
$env:MYSQL_IT_URL = 'jdbc:mysql://127.0.0.1:3306/drawing_course_it'
$env:MYSQL_IT_DATABASE_ACK = 'drawing_course_it'
$env:MYSQL_IT_USERNAME = Read-Host 'Dedicated local test username'
$dbPassword = Read-Host 'Dedicated local test password' -AsSecureString
$env:MYSQL_IT_PASSWORD = [Net.NetworkCredential]::new('', $dbPassword).Password
try {
    .\mvnw.cmd -Pmysql-it verify
} finally {
    Remove-Item Env:MYSQL_IT_URL, Env:MYSQL_IT_DATABASE_ACK, Env:MYSQL_IT_USERNAME, Env:MYSQL_IT_PASSWORD -ErrorAction SilentlyContinue
    $dbPassword.Dispose()
    $dbPassword = $null
}
```

The suite verifies schema startup with Flyway plus Hibernate validation, InnoDB foreign keys, uniqueness and CHECK constraints, VARCHAR enum columns, email case-insensitive/accent-sensitive uniqueness, case-sensitive voucher/payment references, and UTC converter round trips against `DATETIME(6)` with the JVM timezone set to `Asia/Ho_Chi_Minh`.

## Verification record

The isolated `drawing_course_it` target on MySQL 8.0.46 completed all five integration tests successfully. The suite verified schema validation, foreign keys, unique and CHECK constraints, enum/VND column types, collations, and UTC `DATETIME(6)` round trips under `Asia/Ho_Chi_Minh`. This target is separate from `drawing_course_dev`; the tests must continue to use only `drawing_course_it`. The normal `mvnw test` and `mvnw compile` commands use the isolated H2 test profile and do not need MySQL credentials.

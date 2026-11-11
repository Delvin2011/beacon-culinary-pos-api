# Running Flyway locally

The application runs its own migrations at startup (`spring.flyway.enabled: true` in `application.yaml`), so you rarely need the Maven plugin. Use it to inspect or migrate a **local or test** database by hand.

## Configuration

The plugin in `pom.xml` no longer contains a URL, user or password. Flyway reads them from environment variables:

| Variable | Example (no real values) |
|---|---|
| `FLYWAY_URL` | `jdbc:sqlserver://localhost:1433;databaseName=<db>;encrypt=false;trustServerCertificate=true` |
| `FLYWAY_USER` | your local SQL login |
| `FLYWAY_PASSWORD` | your local SQL password |

Maven does not read `.env`. Set the variables in your shell session first.

PowerShell:

```powershell
$env:FLYWAY_URL = "jdbc:sqlserver://localhost:1433;databaseName=BeaconculinaryTest;encrypt=false;trustServerCertificate=true"
$env:FLYWAY_USER = "<user>"
$env:FLYWAY_PASSWORD = "<password>"
./mvnw flyway:info
```

Git Bash:

```bash
FLYWAY_URL='jdbc:sqlserver://...' FLYWAY_USER='<user>' FLYWAY_PASSWORD='<password>' ./mvnw flyway:info
```

## Safe commands

| Command | Use |
|---|---|
| `./mvnw flyway:info` | List applied and pending migrations |
| `./mvnw flyway:validate` | Check that applied checksums match the files |
| `./mvnw flyway:migrate` | Apply pending migrations |

## Never

- `flyway:clean`. It drops every object in the schema. It is now disabled in the plugin (`cleanDisabled=true`) and must stay disabled.
- Editing a migration that has already been applied (V1 to the current highest). Its checksum changes and every database that applied it fails validation. Add a new migration instead.
- Pointing the plugin at a shared Azure database from a developer machine.

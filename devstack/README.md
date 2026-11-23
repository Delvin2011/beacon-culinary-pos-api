# devstack: the local stack (T1-L0)

The production layout on one Windows machine: a gateway (Caddy, standing in for Front Door) on
`https://<customer>.pos.localhost`, the backend from this repository, the frontend from its repository, and SQL Server
with two customer databases (`beaconculinary` with the demo data, `testcustomer` built the T0-7 way).

Everything (what to install, first-time set-up, daily use, troubleshooting, how each part maps to Azure) is in
[docs/2026-11-23-local-stack.md](../docs/2026-11-23-local-stack.md).

Quick reference (PowerShell, from the repository root):

| Command | Does |
|---|---|
| `devstack\scripts\up.ps1` | Builds the backend jar, starts the stack, creates any missing database, waits until healthy |
| `devstack\scripts\status.ps1` | Containers, databases, health through the proxy, hosts file and certificate |
| `devstack\scripts\smoke.ps1` | The end-to-end check through the proxy (`-Quick` skips the 2-minute stream wait) |
| `devstack\scripts\down.ps1` | Stops the stack; data is kept (`-Volumes` deletes it, after a confirmation) |
| `devstack\scripts\reset.ps1` | Drops and recreates the stack's customer databases, after a confirmation |
| `devstack\scripts\new-customer.ps1 <name>` | Another clean customer database, for the multi-tenancy slices |

Settings are in `devstack/stack.env` (git-ignored; copy `stack.env.example`). Never commit it.

# Accounts — contract additions

Two changes: account capture now holds a system-generated PM account number, department and
contact number; and charging an order to an account at the till needs an admin's approval, like
a discount or a complimentary meal. Migrations: `V94__account_number_department_contact.sql`,
`V95__account_payment_authorization.sql`.

## Account capture (`/admin/accounts`)
`POST /admin/accounts` and `PUT /admin/accounts/{id}` take:
```json
{ "name": "Acme", "department": "Finance", "contactNumber": "021 555 0100", "contactEmail": "billing@acme.test" }
```
(`PUT` also takes `active`.) All four are required. `contactNumber`: digits, spaces, `()` and
`-`, optional leading `+`, 7–20 characters. The account number is never sent; it can't be set or
changed.

Responses (`GET`/`POST`/`PUT /admin/accounts`):
`id`, `accountNumber`, `name`, `department`, `contactNumber`, `contactEmail`, `active`.

`accountNumber` is `PM` + the id, zero-padded to 4 digits: `PM0001`, `PM0002`, … (past `PM9999`
it widens: `PM10000`). Existing accounts already have one; their `department` and
`contactNumber` are `null` until the account is next edited.

The till picker (`GET /accounts`) returns `id`, `accountNumber`, `name`, `department`,
`contactNumber`, `contactEmail`.
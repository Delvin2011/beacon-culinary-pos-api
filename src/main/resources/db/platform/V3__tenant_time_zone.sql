-- T1-6: a customer's time zone (docs/2026-11-24-routing-data-source.md, background work). An IANA zone id such as
-- Africa/Johannesburg; NULL = the app's site.time-zone. For now it times only the daily jobs (each customer's 03:30
-- in its own zone); reports stay in South Africa Standard Time (docs/2026-11-19-site-locale.md). The app checks the
-- id: an invalid one is logged once (WARN) and the default is used.
ALTER TABLE [tenants] ADD [time_zone] VARCHAR(64) NULL
    CONSTRAINT [CK_tenants_time_zone] CHECK ([time_zone] IS NULL OR LEN([time_zone]) >= 1);
GO

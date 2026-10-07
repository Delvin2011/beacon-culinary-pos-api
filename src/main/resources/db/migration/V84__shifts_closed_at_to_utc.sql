-- POS Oversight: closed_at was written with the Africa/Johannesburg clock (local time) while
-- opened_at defaults to SYSUTCDATETIME(), so every closed_at sat 2 hours ahead of its
-- opened_at. ShiftService now writes UTC; shift historic values back by the fixed offset
-- (South Africa observes no daylight saving, so UTC+2 holds for every stored row).
UPDATE [shifts] SET [closed_at] = DATEADD(HOUR, -2, [closed_at]) WHERE [closed_at] IS NOT NULL;

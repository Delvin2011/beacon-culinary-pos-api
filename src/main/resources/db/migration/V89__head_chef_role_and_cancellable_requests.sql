-- Head Chef role: plans daily menus, monitors ingredient stock, requests stock issues and waste,
-- submits stock takes, uses the kitchen display, and reviews food sales. No approval rights, no
-- manager-PIN authority, no purchase-order requests (enforced in the application).
ALTER TABLE [users] DROP CONSTRAINT [CK_users_role];
ALTER TABLE [users] WITH CHECK ADD CONSTRAINT [CK_users_role]
    CHECK ([role] IN ('USER', 'CASHIER', 'ADMIN', 'KITCHEN', 'STOCK_CLERK', 'STOCK_ADMIN', 'HEAD_CHEF'));

-- Whoever submitted a stock request or stock take can cancel it while it is still pending.
ALTER TABLE [stock_requests] DROP CONSTRAINT [CK_stock_requests_status];
ALTER TABLE [stock_requests] WITH CHECK ADD CONSTRAINT [CK_stock_requests_status]
    CHECK ([status] IN ('REQUESTED', 'PARTIALLY_ACTIONED', 'ACTIONED', 'REJECTED', 'CANCELLED'));

ALTER TABLE [stock_takes] DROP CONSTRAINT [CK_stock_takes_status];
ALTER TABLE [stock_takes] WITH CHECK ADD CONSTRAINT [CK_stock_takes_status]
    CHECK ([status] IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED'));

-- One HEAD_CHEF for exercising the role locally.
--   Head Chef — email: headchef@canteen.local   password: 654321   PIN: 654321 (kitchen display)
-- Reuses the existing BCrypt hashes of "654321" (same convention as V69).
INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active]) VALUES
    ('Head Chef', 'headchef@canteen.local',
     '$2a$10$.b8cbkPEmPqaUgpZGR2YkubXAqbaUcN8XxycgzoVqj0QdtguXvMnC',
     'HEAD_CHEF',
     '$2a$10$Puj4pEQxfA54SfrUdNBcd.q6Bwdw2MSMeaT.d.pWF1jrUCgtBJCC2',
     1);

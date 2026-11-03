-- Server-managed Cart content revision for optimistic Checkout validation.
-- Existing Carts begin at revision zero; V1-V5 remain unchanged.

ALTER TABLE carts
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_carts_revision_nonnegative CHECK (revision >= 0);

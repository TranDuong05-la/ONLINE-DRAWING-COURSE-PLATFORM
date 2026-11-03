-- Additive B Combo metadata; preserves V1-V15 and existing BIGINT identities.
ALTER TABLE course_bundles
    ADD COLUMN definition_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE bundle_items
    ADD COLUMN position INT NOT NULL DEFAULT 1;

CREATE TEMPORARY TABLE tmp_bundle_item_positions (
    id BIGINT NOT NULL PRIMARY KEY,
    ranked_position INT NOT NULL
);

INSERT INTO tmp_bundle_item_positions (id, ranked_position)
SELECT id, ROW_NUMBER() OVER (PARTITION BY bundle_id ORDER BY id)
FROM bundle_items;

UPDATE bundle_items bi
JOIN tmp_bundle_item_positions ranked ON ranked.id = bi.id
SET bi.position = ranked.ranked_position;

DROP TEMPORARY TABLE tmp_bundle_item_positions;

ALTER TABLE bundle_items
    ADD CONSTRAINT uk_bundle_items_bundle_position UNIQUE (bundle_id, position),
    ADD CONSTRAINT ck_bundle_items_position CHECK (position > 0);

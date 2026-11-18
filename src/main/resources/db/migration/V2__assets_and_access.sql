-- Generic file assets: public profile images and private documents such as coach
-- certificates. The program-file tables from V1 are untouched; they model
-- coach-to-athlete deliverables, not arbitrary uploaded documents.
CREATE TABLE assets (
    asset_id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    object_key VARCHAR(80) NOT NULL UNIQUE,
    storage_backend VARCHAR(20) NOT NULL CHECK (storage_backend IN ('filesystem','s3')),
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    checksum_sha256 VARCHAR(64) NOT NULL,
    visibility VARCHAR(10) NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
    status VARCHAR(12) NOT NULL CHECK (status IN ('ACTIVE','DELETED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX assets_owner ON assets (owner_user_id, created_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX assets_public ON assets (visibility, created_at DESC) WHERE status = 'ACTIVE';

-- Explicit per-user grants. Owner and ADMIN access is implied by policy and is
-- never represented here, so revoking a grant cannot strip ownership rights.
CREATE TABLE asset_access (
    asset_id UUID NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    permission VARCHAR(20) NOT NULL CHECK (permission IN ('READ','READ_WRITE')),
    granted_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (asset_id, user_id)
);

CREATE INDEX asset_access_user ON asset_access (user_id, asset_id);

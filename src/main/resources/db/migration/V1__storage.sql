create table files (
 file_id uuid primary key, coach_id uuid not null, athlete_id uuid not null,
 category varchar(20) not null check (category in ('WORKOUT','DIET','SUPPLEMENT','OTHER')),
 title varchar(100) collate "C" not null, description varchar(500),
 latest_version integer not null check(latest_version > 0),
 created_at timestamptz not null, updated_at timestamptz not null, deleted_at timestamptz,
 check(coach_id <> athlete_id)
);
create unique index files_active_identity on files(athlete_id,category,title) where deleted_at is null;
create index files_coach on files(coach_id,created_at desc) where deleted_at is null;
create index files_athlete on files(athlete_id,created_at desc) where deleted_at is null;
create table file_versions (
 file_id uuid not null references files(file_id), version integer not null check(version>0),
 blob_key varchar(80) not null unique, original_name varchar(255) not null,
 content_type varchar(150) not null, size_bytes bigint not null check(size_bytes>0 and size_bytes<=20971520),
 checksum_sha256 varchar(64) not null, change_note varchar(300), uploaded_by uuid not null,
 uploaded_at timestamptz not null, primary key(file_id,version)
);
create table subscriptions (
 subscription_id uuid primary key, coach_id uuid not null, athlete_id uuid not null, plan_id uuid not null,
 status varchar(20) not null check(status in ('ACTIVE','EXPIRED','CANCELLED')),
 starts_at timestamptz not null, ends_at timestamptz not null,
 aggregate_version bigint not null check(aggregate_version>0), updated_at timestamptz not null
);
create index subscriptions_pair on subscriptions(coach_id,athlete_id,status,ends_at);
create table processed_events (
 consumer varchar(80) not null, event_id uuid not null, processed_at timestamptz not null default now(),
 primary key(consumer,event_id)
);
create table outbox (
 sequence bigint generated always as identity primary key, event_id uuid not null unique,
 aggregate_id uuid not null, aggregate_version bigint not null, event_type varchar(30) not null,
 payload bytea not null, created_at timestamptz not null default now(), published_at timestamptz,
 attempts integer not null default 0, next_attempt_at timestamptz not null default now(),
 unique(aggregate_id,aggregate_version)
);
create index outbox_pending on outbox(sequence) where published_at is null;

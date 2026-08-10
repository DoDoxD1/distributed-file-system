create table dfs_stored_objects (
    object_id varchar(32) primary key,
    owner_user_id varchar(32) not null,
    checksum_sha256 varchar(64) not null,
    size_bytes bigint not null,
    object_key varchar(1024) not null,
    reference_count bigint not null,
    created_at timestamp not null,
    constraint fk_dfs_stored_objects_owner
        foreign key (owner_user_id) references dfs_users(user_id) on delete cascade,
    constraint uq_dfs_stored_objects_owner_hash_size
        unique (owner_user_id, checksum_sha256, size_bytes),
    constraint uq_dfs_stored_objects_object_key unique (object_key)
);

create table dfs_upload_sessions (
    session_id varchar(32) primary key,
    owner_user_id varchar(32) not null,
    logical_path varchar(1024) not null,
    expected_checksum_sha256 varchar(64) not null,
    expected_size_bytes bigint not null,
    content_type varchar(255),
    idempotency_key varchar(255),
    staging_object_key varchar(1024),
    status varchar(32) not null,
    resolved_object_id varchar(32),
    created_at timestamp not null,
    expires_at timestamp not null,
    constraint fk_dfs_upload_sessions_owner
        foreign key (owner_user_id) references dfs_users(user_id) on delete cascade,
    constraint fk_dfs_upload_sessions_object
        foreign key (resolved_object_id) references dfs_stored_objects(object_id),
    constraint uq_dfs_upload_sessions_owner_path_idempotency
        unique (owner_user_id, logical_path, idempotency_key)
);

create index idx_dfs_stored_objects_owner
    on dfs_stored_objects(owner_user_id);

create index idx_dfs_upload_sessions_owner
    on dfs_upload_sessions(owner_user_id);

create index idx_dfs_upload_sessions_status_expires
    on dfs_upload_sessions(status, expires_at);

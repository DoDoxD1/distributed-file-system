create table dfs_files (
    file_id varchar(32) primary key,
    logical_path varchar(1024) not null unique
);

create table dfs_file_versions (
    version_id varchar(32) primary key,
    file_id varchar(32) not null,
    version_number bigint not null,
    size_bytes bigint not null,
    checksum varchar(128) not null,
    created_at timestamp not null,
    idempotency_key varchar(255),
    deleted_at timestamp,
    constraint fk_dfs_file_versions_file
        foreign key (file_id) references dfs_files(file_id),
    constraint uq_dfs_file_versions_file_order unique (file_id, version_number)
);

create table dfs_version_chunks (
    version_id varchar(32) not null,
    chunk_order integer not null,
    chunk_id varchar(128) not null,
    primary key (version_id, chunk_order),
    constraint fk_dfs_version_chunks_version
        foreign key (version_id) references dfs_file_versions(version_id)
);

create table dfs_chunks (
    chunk_id varchar(128) primary key,
    checksum varchar(128) not null,
    size_bytes integer not null,
    last_unreferenced_at timestamp
);

create table dfs_chunk_replicas (
    chunk_id varchar(128) not null,
    node_id varchar(128) not null,
    primary key (chunk_id, node_id),
    constraint fk_dfs_chunk_replicas_chunk
        foreign key (chunk_id) references dfs_chunks(chunk_id) on delete cascade
);

create table dfs_idempotency_keys (
    logical_path varchar(1024) not null,
    idempotency_key varchar(255) not null,
    version_id varchar(32) not null,
    primary key (logical_path, idempotency_key),
    constraint fk_dfs_idempotency_keys_version
        foreign key (version_id) references dfs_file_versions(version_id)
);

create index idx_dfs_file_versions_file_active
    on dfs_file_versions(file_id, deleted_at, version_number);

create index idx_dfs_version_chunks_chunk
    on dfs_version_chunks(chunk_id);

create index idx_dfs_chunk_replicas_chunk
    on dfs_chunk_replicas(chunk_id);

alter table dfs_upload_sessions
    add column committed_version_id varchar(32);

alter table dfs_upload_sessions
    add constraint fk_dfs_upload_sessions_committed_version
        foreign key (committed_version_id) references dfs_file_versions(version_id);

create table dfs_file_version_objects (
    version_id varchar(32) primary key,
    object_id varchar(32) not null,
    constraint fk_dfs_file_version_objects_version
        foreign key (version_id) references dfs_file_versions(version_id) on delete cascade,
    constraint fk_dfs_file_version_objects_object
        foreign key (object_id) references dfs_stored_objects(object_id)
);

create index idx_dfs_file_version_objects_object
    on dfs_file_version_objects(object_id);

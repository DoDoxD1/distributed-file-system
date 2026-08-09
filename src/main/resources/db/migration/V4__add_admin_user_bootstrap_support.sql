alter table dfs_users
    add column is_admin boolean not null default false;

alter table dfs_users
    add column admin_singleton_key integer;

alter table dfs_users
    add constraint chk_dfs_users_admin_singleton
    check ((is_admin = true and admin_singleton_key = 1) or (is_admin = false and admin_singleton_key is null));

create unique index uq_dfs_users_admin_singleton
    on dfs_users(admin_singleton_key);

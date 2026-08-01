create table dfs_users (
    user_id varchar(32) primary key,
    email varchar(320) not null unique,
    password_hash varchar(512) not null,
    created_at timestamp not null
);

create table dfs_user_sessions (
    token_hash varchar(64) primary key,
    user_id varchar(32) not null,
    created_at timestamp not null,
    expires_at timestamp not null,
    constraint fk_dfs_user_sessions_user
        foreign key (user_id) references dfs_users(user_id) on delete cascade
);

create index idx_dfs_user_sessions_user
    on dfs_user_sessions(user_id);

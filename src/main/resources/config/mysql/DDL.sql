create table tbl_user(
    id bigint(20) auto_increment,
    login varchar(50) not null,
    password_hash varchar(60) not null,
    email varchar(254) not null,
    created_by varchar(50),
    created_data timestamp,
    last_modified_by varchar(50),
    last_modified_date timestamp,
    constraint pk_user primary key(id)
)
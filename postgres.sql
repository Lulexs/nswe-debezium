create table app_user (
    id int generated always as identity primary key,
    first_name varchar(50) not null,
    last_name varchar(50) not null,
    birthday date not null
);

create table user_attributes (
    user_id int not null references app_user(id),
    attribute_name varchar(50) not null,
    attribute_value varchar(50),
    primary key (user_id, attribute_name)
);

alter table app_user replica identity full;
alter table user_attributes replica identity full;

create role debezium with login password 'debezium' replication;

grant connect on database postgres to debezium;

grant usage on schema public to debezium;

grant select on table public.app_user, public.user_attributes to debezium;

create publication dbz_publication for table public.app_user, public.user_attributes;

alter publication dbz_publication owner to debezium;

select pg_create_logical_replication_slot(
	'debezium_slot',
	'pgoutput'
);

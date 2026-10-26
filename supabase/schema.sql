-- Puente Mihon. Pegar en el SQL Editor de Supabase.
-- Una biblioteca por usuario. Las apps entran con la clave publicable
-- y la sesión de ese usuario. La clave service_role no se usa.
--
-- El CLI de Supabase no está en esta máquina, así que este archivo es el
-- esquema para aplicar a mano. Cuando el proyecto esté enlazado, se puede
-- volcar a una migración con `supabase db pull`.

create table public.library_head (
    user_id uuid primary key references auth.users (id) on delete cascade,
    state_version bigint not null,
    library_generation bigint not null default 0,
    library_path text,
    library_sha256 text,
    clocks_path text,
    updated_at timestamptz not null default now()
);

create table public.devices (
    user_id uuid not null references auth.users (id) on delete cascade,
    device_key text not null,
    name text not null,
    platform text not null check (platform in ('android', 'mac')),
    created_at timestamptz not null default now(),
    primary key (user_id, device_key)
);

alter table public.library_head enable row level security;
alter table public.devices enable row level security;

revoke all on public.library_head from anon;
revoke all on public.devices from anon;
grant select, insert, update on public.library_head to authenticated;
grant select, insert, update on public.devices to authenticated;

create policy library_head_select
on public.library_head
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy library_head_insert
on public.library_head
for insert
to authenticated
with check ((select auth.uid()) = user_id);

create policy library_head_update
on public.library_head
for update
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy devices_select
on public.devices
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy devices_insert
on public.devices
for insert
to authenticated
with check ((select auth.uid()) = user_id);

create policy devices_update
on public.devices
for update
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

-- Guardado atómico. security invoker: vale el RLS de arriba.
-- Inserta la fila la primera vez. Si ya existe, actualiza solo cuando
-- state_version sigue siendo expected_version. Si alguien escribió en el
-- medio, no devuelve fila y la app vuelve a fusionar.
create or replace function public.advance_head(
    expected_version bigint,
    next_version bigint,
    next_library_generation bigint,
    next_library_path text,
    next_library_sha text,
    next_clocks_path text
) returns setof public.library_head
language plpgsql
security invoker
set search_path = public
as $$
begin
    return query
    insert into public.library_head as head (
        user_id,
        state_version,
        library_generation,
        library_path,
        library_sha256,
        clocks_path,
        updated_at
    ) values (
        auth.uid(),
        next_version,
        next_library_generation,
        next_library_path,
        next_library_sha,
        next_clocks_path,
        now()
    )
    on conflict (user_id) do update
    set state_version = excluded.state_version,
        library_generation = excluded.library_generation,
        library_path = excluded.library_path,
        library_sha256 = excluded.library_sha256,
        clocks_path = excluded.clocks_path,
        updated_at = now()
    where head.state_version = expected_version
    returning *;
end;
$$;

revoke all on function public.advance_head(bigint, bigint, bigint, text, text, text) from public, anon;
grant execute on function public.advance_head(bigint, bigint, bigint, text, text, text) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit)
values ('puente', 'puente', false, 104857600)
on conflict (id) do nothing;

create policy puente_storage_select
on storage.objects
for select
to authenticated
using (
    bucket_id = 'puente'
    and (storage.foldername(name))[1] = (select auth.jwt() ->> 'sub')
);

create policy puente_storage_insert
on storage.objects
for insert
to authenticated
with check (
    bucket_id = 'puente'
    and (storage.foldername(name))[1] = (select auth.jwt() ->> 'sub')
);

create policy puente_storage_update
on storage.objects
for update
to authenticated
using (
    bucket_id = 'puente'
    and (storage.foldername(name))[1] = (select auth.jwt() ->> 'sub')
)
with check (
    bucket_id = 'puente'
    and (storage.foldername(name))[1] = (select auth.jwt() ->> 'sub')
);

notify pgrst, 'reload schema';

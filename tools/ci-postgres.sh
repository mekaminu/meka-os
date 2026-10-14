#!/usr/bin/env bash
# Starts the Postgres the backend tests use on a CI runner, at localhost:5432 (user/password postgres).
# A job service container fails the whole job when its one image can't be pulled (Docker Hub's and then AWS's public
# mirror's anonymous limits both hit, 2026-10-09 and 2026-10-14), so this tries several mirrors of the same official
# image, then falls back to the PostgreSQL that GitHub's Ubuntu runner image already has installed.
set -uo pipefail

wait_ready() { # $1 = command that succeeds once Postgres takes connections
  for _ in $(seq 1 60); do
    if eval "$1" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

for image in public.ecr.aws/docker/library/postgres:17 mirror.gcr.io/library/postgres:17 docker.io/library/postgres:17; do
  for attempt in 1 2; do
    if docker pull -q "$image"; then
      docker run -d --name meka-test-pg -e POSTGRES_PASSWORD=postgres -p 5432:5432 "$image" >/dev/null
      if wait_ready "docker exec meka-test-pg pg_isready -U postgres -h localhost"; then
        echo "Postgres from $image"
        exit 0
      fi
      docker logs meka-test-pg || true
      docker rm -f meka-test-pg >/dev/null 2>&1 || true
      break
    fi
    sleep $((attempt * 5))
  done
done

echo "::warning title=Postgres::No Postgres image could be pulled; using the runner's own PostgreSQL"
sudo systemctl start postgresql.service
wait_ready "sudo -u postgres pg_isready -h localhost" || { echo "::error::the runner's PostgreSQL didn't start"; exit 1; }
sudo -u postgres psql -v ON_ERROR_STOP=1 -c "ALTER USER postgres PASSWORD 'postgres';" >/dev/null
echo "Postgres from the runner: $(sudo -u postgres psql -tAc 'SHOW server_version')"

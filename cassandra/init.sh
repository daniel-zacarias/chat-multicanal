#!/bin/bash
# Creates the application keyspace once Cassandra is ready.
# The superuser (CASSANDRA_USER/CASSANDRA_PASSWORD) is already created by the
# official cassandra image entrypoint when those env vars are set on the service.
set -e

HOST="${CASSANDRA_HOST:-cassandra}"
KEYSPACE="${CASSANDRA_KEYSPACE:-chat}"

echo "Waiting for Cassandra to accept authenticated connections..."
until cqlsh "$HOST" -u "$CASSANDRA_USER" -p "$CASSANDRA_PASSWORD" \
      -e 'DESCRIBE KEYSPACES;' > /dev/null 2>&1; do
  echo "  Not ready yet, retrying in 3s..."
  sleep 3
done

echo "Creating keyspace '$KEYSPACE'..."
cqlsh "$HOST" -u "$CASSANDRA_USER" -p "$CASSANDRA_PASSWORD" -e \
  "CREATE KEYSPACE IF NOT EXISTS $KEYSPACE
   WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"

echo "Cassandra initialization complete."

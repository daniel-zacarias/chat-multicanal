#!/bin/bash
set -e

HOST="${CASSANDRA_HOST:-cassandra}"
KEYSPACE="${CASSANDRA_KEYSPACE:-chat}"
APP_USER="${CASSANDRA_USER}"
APP_PASS="${CASSANDRA_PASSWORD}"

# The cassandra:5 image always boots with the built-in cassandra/cassandra superuser.
# CASSANDRA_USER/CASSANDRA_PASSWORD env vars are NOT processed by the official image.
SUPER_USER="cassandra"
SUPER_PASS="cassandra"

echo "Waiting for Cassandra to accept connections..."
until cqlsh "$HOST" -u "$SUPER_USER" -p "$SUPER_PASS" -e 'DESCRIBE KEYSPACES;' > /dev/null 2>&1; do
  echo "  Not ready yet, retrying in 3s..."
  sleep 3
done

echo "Creating keyspace '$KEYSPACE'..."
cqlsh "$HOST" -u "$SUPER_USER" -p "$SUPER_PASS" -e \
  "CREATE KEYSPACE IF NOT EXISTS $KEYSPACE
   WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"

if [ "$APP_USER" != "$SUPER_USER" ]; then
  echo "Creating role '$APP_USER'..."
  cqlsh "$HOST" -u "$SUPER_USER" -p "$SUPER_PASS" -e \
    "CREATE ROLE IF NOT EXISTS '$APP_USER' WITH PASSWORD = '$APP_PASS' AND LOGIN = true;"

  echo "Granting permissions to '$APP_USER' on keyspace '$KEYSPACE'..."
  cqlsh "$HOST" -u "$SUPER_USER" -p "$SUPER_PASS" -e \
    "GRANT ALL PERMISSIONS ON KEYSPACE $KEYSPACE TO '$APP_USER';"
fi

echo "Cassandra initialization complete."

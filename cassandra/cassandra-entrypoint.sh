#!/bin/bash
# Patch cassandra.yaml before handing off to the official entrypoint.
# The official cassandra:5 image does not expose authenticator/authorizer
# as environment variables, so we sed them in directly.
sed -i 's/^authenticator:.*/authenticator: PasswordAuthenticator/' /etc/cassandra/cassandra.yaml
sed -i 's/^authorizer:.*/authorizer: CassandraAuthorizer/' /etc/cassandra/cassandra.yaml

exec docker-entrypoint.sh "$@"

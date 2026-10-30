# The published platform package carries the tursodb binary for the pinned
# Turso version. Keep the version equal to TURSO_SDK_KIT_VERSION in
# src/main/resources/io/github/magnusp/turso/native/linux-x86_64/native.properties;
# the Java client and the server share a sync protocol.
FROM node:22-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g @tursodatabase/cli-linux-x64-gnu@0.9.0-pre.1 \
 && ln -s /usr/local/lib/node_modules/@tursodatabase/cli-linux-x64-gnu/tursodb /usr/local/bin/tursodb

ENTRYPOINT ["tursodb"]

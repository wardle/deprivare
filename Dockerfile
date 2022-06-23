FROM clojure:openjdk-11-tools-deps-1.11.1.1113-buster as build

# Prepare dependencies
COPY deps.edn /usr/src/deprivare/
WORKDIR /usr/src/deprivare
RUN clojure -P

# Add sources and run from source
COPY server /usr/src/deprivare/server
COPY src /usr/src/deprivare/src
RUN clojure -X:install-all :db depriv.db
EXPOSE 8080
CMD clojure -X:server :db depriv.db :port 8080 :bind-address '"0.0.0.0"'
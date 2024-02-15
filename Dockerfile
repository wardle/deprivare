FROM clojure:openjdk-11-tools-deps-1.11.1.1113-buster as builder

# Prepare dependencies
COPY deps.edn /usr/src/deprivare/
WORKDIR /usr/src/deprivare
RUN clojure -P

# Add sources and build uberjar and datafile
COPY server /usr/src/deprivare/server
COPY src /usr/src/deprivare/src
COPY deps.edn /usr/src/deprivare/src
COPY build.clj /usr/src/deprivare/
COPY resources /usr/src/deprivare/resources
RUN clojure -T:build uber :out '"depriv.jar"' && clojure -X:install-all :db depriv.db

# Create minimal server environment
FROM bellsoft/liberica-openjdk-alpine-musl
RUN mkdir -p /service
COPY --from=builder /usr/src/deprivare/depriv.jar /service/depriv.jar
COPY --from=builder /usr/src/deprivare/depriv.db /service/depriv.db
RUN addgroup -S depriv && adduser -S depriv -G depriv
RUN chown -R depriv /service
USER depriv

EXPOSE 8080
WORKDIR /service
CMD java -jar depriv.jar depriv.db 8080
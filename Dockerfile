FROM clojure:latest
COPY . /usr/src/app
WORKDIR /usr/src/app
ARG aws_secret_access_key=
ARG aws_access_key_id=
ARG uberjar_path=./target/datomic-curation-tools_uber.jar
ARG db_uri=datomic:dev://some/db
RUN apt-get update && \
    apt-get install -y openjdk-8-jre-headless
ADD $uberjar_path /srv/datomic-curation-tools.jar
ENV AWS_ACCESS_KEY_ID=$aws_access_key_id
ENV AWS_SECRET_ACCESS_KEY=$aws_secret_access_key
ENV TRACE_DB $db_uri
EXPOSE 3000 43210 43211
CMD ["java", "-Dcom.sun.management.jmxremote", "-Dcom.sun.management.jmxremote.ssl=false", "-Dcom.sun.management.jmxremote.authenticate=false", "-Dcom.sun.management.jmxremote.port=43210", "-Dcom.sun.management.jmxremote.rmi.port=43211", "-Ddatomic.objectCacheMax=500m", "-Djava.rmi.server.hostname=172.17.0.2", "-jar", "/srv/datomic-curation-tools.jar"]

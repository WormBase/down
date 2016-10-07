FROM openjdk:8-jre-alpine

MAINTAINER matthew.russell@wormbase.org

ARG aws_secret_access_key=
ARG aws_access_key_id=
ARG uberjar_path=

ADD $uberjar_path /srv/app.jar
ENV AWS_ACCESS_KEY_ID=$aws_access_key_id \
    AWS_SECRET_ACCESS_KEY=$aws_secret_access_key

EXPOSE 3000

CMD ["java", "-jar", "/srv/app.jar"]

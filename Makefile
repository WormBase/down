SHELL := /bin/sh
NAME := wormbase/datomic-curation-tools
VERSION ?= $(shell git describe --abbrev=0 --tags)
EBX_CONFIG = .ebextensions/.config
DB_URI ?= $(shell sed -rn 's|value:(.*)|\1|p' ${EBX_CONFIG} | tr -d " ")
DEPLOY_JAR := docker/app.jar
FQ_TAG := ${WB_ACC_NUM}.dkr.ecr.us-east-1.amazonaws.com/${NAME}:${VERSION}
APP_CONTAINER_NAME := dct-app

define print-help
        $(if $(need-help),$(warning $1 -- $2))
endef

need-help := $(filter help,$(MAKECMDGOALS))

help: ; @echo $(if $(need-help),,\
	Type \'$(MAKE)$(dash-f) help\' to get help)

${DEPLOY_JAR}: $(call print-help,docker/app.jar, "Build the jar file")
	@./scripts/build-appjar.sh ${DEPLOY_JAR}

.PHONY: build-nginx-proxy-server
build-nginx-proxy-server:
	@docker build -t nginx-proxy ./docker-nginx-proxy

.PHONY: build-app
build-app: $(call print-help,build-app,"Build the application container")
	@docker build -t ${NAME}:${VERSION} \
		--build-arg uberjar_path=app.jar \
		--build-arg \
			aws_secret_access_key=${AWS_SECRET_ACCESS_KEY} \
		--build-arg aws_access_key_id=${AWS_ACCESS_KEY_ID} \
		--rm docker

.PHONY: docker-ecr-login
docker-ecr-login: $(call print-help,docker-ecr-login,"Login to ECR")
	@eval $(shell aws ecr get-login)


.PHONY: build
build: $(call print-help,build,"Build all docker containers") \
	build-nginx-proxy-server build-app

.PHONY: docker-tag
docker-tag: $(call print-help,docker-tag,\
	     "Tag the image with current git revision \
	      and ':latest' alias")
	@docker tag ${NAME}:${VERSION} ${FQ_TAG}
	@docker tag ${NAME}:${VERSION} \
		    ${WB_ACC_NUM}.dkr.ecr.us-east-1.amazonaws.com/${NAME}

.PHONY: docker-push-ecr
docker-push-ecr: $(call print-help,docker-push-ecr,\
	           "Push the image tagged with the current git revision\
	 	    to ECR")
	@docker push ${FQ_TAG}


run-app: $(call print-help,run-app,\
	  "Run the application in docker (locally).")
	@docker run \
		--name ${APP_CONTAINER_NAME} \
		--publish-all=true \
		--publish 3000:3000 \
		--detach \
		-e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
		-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
		-e TRACE_DB=${DB_URI} \
		-e TRACE_PORT=3000 \
		-e TRACE_ACCEPT_REST_QUERY=1 \
		-e TRACE_REQUIRE_LOGIN=0 \
		-v /home/matt/docker-data:/tmp/docker-data \
		 ${NAME}:${VERSION}

run-nginx:
	@docker run \
		--link ${APP_CONTAINER_NAME} \
	        --name nginx-container \
		--detach \
		-v `pwd`/resources/public:/var/www/static:ro \
		-v `pwd`/docker-nginx-proxy/nginx.conf:/etc/nginx/nginx.conf:ro \
		-p 80:80 \
		-e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACESS_KEY} \
		-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
		nginx-proxy

run: $(call print-help,run,"Run the application in docker (locally).") \
     run-app run-nginx


clean: $(call print-help,clean,"Remove the locally built JAR file.")
	@rm -f ${DEPLOY_JAR}

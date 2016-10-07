SHELL := /bin/sh
APP_CONTAINER_NAME := wormbase/datomic-curation-tools
PROXY_CONTAINER_NAME := ${APP_CONTAINER_NAME}_nginx-proxy
VERSION ?= $(shell git describe --abbrev=0 --tags)
EBX_CONFIG = .ebextensions/.config
DB_URI ?= $(shell sed -rn 's|value:\s+(datomic.*)|\1|p' ${EBX_CONFIG} | \
            tr -d " ")
DEPLOY_JAR := docker/app.jar
WB_ACC_NUM := 357210185381
FQ_PREFIX := ${WB_ACC_NUM}.dkr.ecr.us-east-1.amazonaws.com
APP_FQ_TAG := ${FQ_PREFIX}/${APP_CONTAINER_NAME}:${VERSION}
PROXY_FQ_TAG := ${FQ_PREFIX}/${PROXY_CONTAINER_NAME}:${VERSION}
APP_SHORT_NAME := dct-app
APP_ECR_REPOSITORY := ${FQ_PREFIX}/${APP_CONTAINER_NAME}
PROXY_ECR_REPOSITORY := ${FQ_PREFIX}/${PROXY_CONTAINER_NAME}

define print-help
        $(if $(need-help),$(warning $1 -- $2))
endef

need-help := $(filter help,$(MAKECMDGOALS))

help: ; @echo $(if $(need-help),,\
	Type \'$(MAKE)$(dash-f) help\' to get help)

${DEPLOY_JAR}: $(call print-help,docker/app.jar, "Build the jar file")
	@./scripts/build-appjar.sh prod ${DEPLOY_JAR}

.PHONY: build-nginx-proxy
build-nginx-proxy:
	@docker build \
		-f ./docker-nginx-proxy/Dockerfile \
		-t ${PROXY_CONTAINER_NAME}:${VERSION} .

.PHONY: build-app
build-app: $(call print-help,build-app,"Build the application container")
	@docker build -t ${APP_CONTAINER_NAME}:${VERSION} \
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
	build-nginx-proxy build-app

.PHONY: docker-tag
docker-tag: $(call print-help,docker-tag,\
	     "Tag the images with the current git revision")
	@docker tag ${APP_CONTAINER_NAME}:${VERSION} ${APP_FQ_TAG}
	@docker tag ${APP_CONTAINER_NAME}:${VERSION} ${APP_ECR_REPOSITORY}
	@docker tag ${PROXY_CONTAINER_NAME}:${VERSION} ${PROXY_FQ_TAG}
	@docker tag ${PROXY_CONTAINER_NAME}:${VERSION} ${PROXY_ECR_REPOSITORY}

.PHONY: docker-push-ecr
docker-push-ecr: $(call print-help,docker-push-ecr,\
			"Push the image tagged with the current git \
			revision to ECR") docker-ecr-login
	@docker push ${APP_FQ_TAG}
	@docker push ${PROXY_FQ_TAG}

.PHONY: run-app
run-app: $(call print-help,run-app,\
	  "Run the application in docker (locally).")
	@docker run \
		--name ${APP_SHORT_NAME} \
		--publish 3000:3000 \
		--detach \
		-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
		-e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
		-e TRACE_DB=${DB_URI} \
		-e TRACE_ACCEPT_REST_QUERY="1" \
		-e TRACE_REQUIRE_LOGIN="0" \
		 ${APP_CONTAINER_NAME}:${VERSION}

.PHONY: run-nginx-proxy
run-nginx-proxy: $(call print-help,run-nginx-proxy,\
                   "Run the nginx-proxy in docker locally")
	@docker run \
		--link ${APP_SHORT_NAME} \
	        --name nginx-proxy \
		--detach \
		-p 80:80 \
		${PROXY_CONTAINER_NAME}:${VERSION}

.PHONY: run
run: $(call print-help,run,"Run the application in docker (locally).") \
     run-app run-nginx-proxy

.PHONY: clean
clean: $(call print-help,clean,"Remove the locally built JAR file.")
	@rm -f ${DEPLOY_JAR}
	@if [ -d target ]; then find target -type f -delete; fi
	@find . -type f -name '*-init.clj' -delete

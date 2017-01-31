SHELL := /bin/sh
APP_CONTAINER_NAME := wormbase/down
PROXY_CONTAINER_NAME := ${APP_CONTAINER_NAME}_nginx-proxy
VERSION ?= $(shell git describe --always --abbrev=0 --tags)
EBX_CONFIG = .ebextensions/.config
DB_URI ?= $(shell sed -rn 's|value:\s+(datomic.*)|\1|p' ${EBX_CONFIG} | \
	          tr -d " ")
WS_VERSION ?= $(shell echo ${DB_URI} | \
                      sed -rn 's|datomic.*(WS\d*)|\1|p' | \
                tr -d " ")
DEPLOY_JAR := docker/app.jar
WB_ACC_NUM := 357210185381
FQ_PREFIX := ${WB_ACC_NUM}.dkr.ecr.us-east-1.amazonaws.com
APP_FQ_TAG := ${FQ_PREFIX}/${APP_CONTAINER_NAME}:${VERSION}
PROXY_FQ_TAG := ${FQ_PREFIX}/${PROXY_CONTAINER_NAME}:${VERSION}
APP_SHORT_NAME := down-app
APP_ECR_REPOSITORY := ${FQ_PREFIX}/${APP_CONTAINER_NAME}
PROXY_ECR_REPOSITORY := ${FQ_PREFIX}/${PROXY_CONTAINER_NAME}

# AWS Settings
AWS_VPC_ID := "vpc-8e0087e9"
AWS_VPC_EC2SUBNETS := "subnet-a33a2bd5"
AWS_VPC_SECGROUPS := "sg-2c332257"
EC2_INSTANCE_TYPE := "m3.xlarge"

# Makefile help system

define print-help
        $(if $(need-help),$(warning $1 -- $2))
endef

need-help := $(filter help,$(MAKECMDGOALS))

help: ; @echo $(if $(need-help),,\
	Type \'$(MAKE)$(dash-f) help\' to get help)

# Targets

.PHONY: cljs-build-dev
cljs-build-dev:
	@./scripts/cljsbuild.sh dev

.PHONY: cljs-build-prod
cljs-build-prod:
	@./scripts/cljsbuild.sh prod


${DEPLOY_JAR}: cljs-build-prod \
               $(call print-help,${DEPLOY_JAR}, "Build the jar file")
	@./scripts/build-appjar.sh prod ${DEPLOY_JAR}

.PHONY: print-ws-version
print-ws-version:
	@echo ${WS_VERSION}

.PHONY: build-nginx-proxy
build-nginx-proxy:
	@docker build \
		-f ./docker-nginx-proxy/Dockerfile \
		-t ${PROXY_CONTAINER_NAME}:${VERSION} .

.PHONY: build-app
build-app:
	@docker build -t ${APP_CONTAINER_NAME}:${VERSION} \
		--build-arg uberjar_path=app.jar \
		--build-arg \
			aws_secret_access_key=${AWS_SECRET_ACCESS_KEY} \
		--build-arg aws_access_key_id=${AWS_ACCESS_KEY_ID} \
		--rm docker

.PHONY: docker-ecr-login
docker-ecr-login:
	@eval $(shell aws ecr get-login)

.PHONY: build
build: $(call print-help,build,"Builds all docker containers") \
	build-nginx-proxy build-app

.PHONY: docker-tag
docker-tag:
	@docker tag ${APP_CONTAINER_NAME}:${VERSION} ${APP_FQ_TAG}
	@docker tag ${APP_CONTAINER_NAME}:${VERSION} ${APP_ECR_REPOSITORY}
	@docker tag ${PROXY_CONTAINER_NAME}:${VERSION} ${PROXY_FQ_TAG}
	@docker tag ${PROXY_CONTAINER_NAME}:${VERSION} \
                    ${PROXY_ECR_REPOSITORY}

.PHONY: docker-push-ecr
docker-push-ecr: $(call print-help,docker-push-ecr,\
			"Push the image tagged with the current git \
			revision to ECR") \
                 docker-tag \
                 docker-ecr-login
	@docker push ${APP_FQ_TAG}
	@docker push ${PROXY_FQ_TAG}

.PHONY: run-app
run-app:
	@docker run \
		--name ${APP_SHORT_NAME} \
		--publish 3000:3000 \
		--detach \
		-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
		-e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
		-e TRACE_DB=${DB_URI} \
		-e TRACE_REQUIRE_LOGIN="0" \
		 ${APP_CONTAINER_NAME}:${VERSION}

.PHONY: run-nginx-proxy
run-nginx-proxy:
	@docker run \
		--link ${APP_SHORT_NAME} \
	        --name nginx-proxy \
		--detach \
		-p 80:80 \
		${PROXY_CONTAINER_NAME}:${VERSION}

.PHONY: run
run: $(call print-help,run, \
       "Runs the composite application via docker run.") \
     run-app run-nginx-proxy


.PHONY: pre-release-test
pre-release-test: $(call print-help,pre-release-test,\
                    "Builds and runs the application in docker, \
                     intended to be used as a release check.") \
                  ${DEPLOY_JAR} build run

.PHONY: eb-create
eb-create: $(call print-help,eb-create,\
             "Create an ElasticBeanStalk environment using \
              the Docker platform.")
	@eb create down-${WS_VERSION} \
               --region=${AWS_DEFAULT_REGION} \
               --tags="CreatedBy=${AWS_EB_PROFILE},Role=WebService" \
               --instance-type=${EC2_INSTANCE_TYPE} \
               --cname="down=${WS_VERSION}" \
               --vpc.id=${AWS_VPC_ID} \
               --vpc.ec2subnets=${AWS_VPC_EC2SUBNETS} \
               --vpc.securitygroups=${AWS_VPC_SECGROUPS} \
               --single

.PHONY: clean
clean: $(call print-help,clean,"Cleans compiled state.")
	@rm -f ${DEPLOY_JAR}
	@lein clean

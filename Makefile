SHELL := /bin/sh
APP_CONTAINER_NAME := wormbase/down
APP_SHORT_NAME := down
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
APP_ECR_REPOSITORY := ${FQ_PREFIX}/${APP_CONTAINER_NAME}

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

.PHONY: docker-build
docker-build: $(call print-help,docker-build,\
                "Build application docker container") \
               clean ${DEPLOY_JAR}
	@docker build -t ${APP_CONTAINER_NAME}:${VERSION} \
		--build-arg uberjar_path=app.jar \
		--build-arg \
			aws_secret_access_key=${AWS_SECRET_ACCESS_KEY} \
		--build-arg aws_access_key_id=${AWS_ACCESS_KEY_ID} \
		--rm docker

.PHONY: docker-ecr-login
docker-ecr-login:
	@eval $(shell aws ecr get-login)

.PHONY: docker-tag
docker-tag:
	@docker tag ${APP_CONTAINER_NAME}:${VERSION} ${APP_FQ_TAG}
	@docker tag ${APP_CONTAINER_NAME}:${VERSION} ${APP_ECR_REPOSITORY}

.PHONY: docker-push-ecr
docker-push-ecr: $(call print-help,docker-push-ecr,\
			"Push the image tagged with the current git \
			revision to ECR") \
                 docker-tag \
                 docker-ecr-login
	@docker push ${APP_FQ_TAG}

.PHONY: docker-run
docker-run:
	@docker run \
		--name ${APP_SHORT_NAME} \
		--publish 3000:3000 \
		--detach \
		-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
		-e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
		-e WB_DB_URI=${DB_URI} \
		-e WB_REQUIRE_LOGIN="0" \
		 ${APP_CONTAINER_NAME}:${VERSION}

.PHONY: docker-clean
docker-clean: $(call print-help,docker-clean,\
               "Stops and removes arunning docker application container.")
	@docker stop down
	@docker rm down

.PHONY: run
run: $(call print-help,run, \
       "Runs the composite application via docker run.") \
     run-app run-nginx-proxy


.PHONY: pre-release-test
pre-release-test: $(call print-help,pre-release-test,\
                    "Builds and runs the application in docker, \
                     intended to be used as a release check.") \
                  docker-build docker-run

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

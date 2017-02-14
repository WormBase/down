# Deployment

This application is deployed via docker to [elasticbeanstalk][1] on AWS.

First, ensure to set your [AWS Credentials][2].

## Deployment procedure

### Preparation

1. Package the uberjar file and create the docker container
   ```bash
   make pre-release-test
   ```
2. Test the application runs locally in Docker
   ```bash
   make docker-run
   python -m webbrowser http://172.17.0.1:3000
   ```
3. Ensure to update version numbers to reflect intended release tag in
   top-level files:
   3.1 `CHANGES.md`
   3.2 `project.clj`
   3.3 `Dockerrun.aws.json`
4. With git, merge develop to master, checkout master create and push tag.
5. Create the docker-container afresh:
   ```bash
   make docker-build
   ```
6. Create docker tags and push to [ECR][3]

   ```bash
   make docker-tag
   make docker-push-ecr
   ```
7. Test locally
   ```bash
   eb local run --envvars=WB_DB_URI="${WB_DB_URI}",WB_REQIORE_LOGIN"=0"

### Execution

For first-time deployment (new application in elasticbeanstalk), use:

```make eb-create```

N.B: You can safely ignore any errors from the Makefile command when you
issue a Ctrl-C command.

For subsequent deployments, use `eb deploy`.

Use the `eb` command to display status of the deployment.
e.g:

```bash
eb status
eb events -f
```

[1]: https://aws.amazon.com/elasticbeanstalk/
[2]: https://github.com/WormBase/wormbase-architecture/wiki/AWS-Credentials
[3]: https://aws.amazon.com/ecr/

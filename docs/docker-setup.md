## Docker setup

The following describes how to run "down" under [docker][1].

Note that the top-level Makefile in this project has targets that
automate the commands below; the below is left here for reference.

Use `make help` to see the various docker commands.

### Setup of ECR repositories

Ensure the following two repositories are created in
the [ECR][Elastic Container Registry]:

  * wormbase/down
  * wormbase/down_nginx-proxy

```bash
aws ecr describe-repositories
```

If they are not, then create them with:

```bash

aws ecr create-repository wormbase/down
aws ecr create-repository wormbase/down_nginx-proxy
```

### Build the application artefact

```bash
make clean && make docker/app.jar
```

### Build docker images

N.B The Makefile currently hard-codes the use of `prod` as the
    `TARGET_PLATFORM`.  If you'd like to build the docker image in
    development, temporarily edit the Makefile accordingly.

```bash
make build
```

Inspect the output of `docker images` and check it makes sense.

### Run docker images locally to test

```bash
make run
```

Test the web interface locally:

```bash
python -m webbrowser http://localhost/colonnade/
```

### Deploy the images to ECR

```bash
# Tag first
make docker-tag

# Push the images to ECR
make docker-push-ecr
```

### Test locally with Elastic Bean Stalk configuration

*Important:*

The docker images referenced by the `Dockerrun.aws.json`
must have previously been pushed to the ECR repositories.

## Updating the Datomic URI for a new release

The Makefile obtains the datomic URI from the file:

Change the URI in the file `.ebextensions/.config` - this is the single
location the URI should be defined within the project.

### Cloudwatch w/Elasticbeanstalk
Cloudwatch has been confiugured using as per the official [guide][3].

[1]: https://www.docker.com/
[3]: http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/AWSHowTo.cloudwatchlogs.html#AWSHowTo.cloudwatchlogs.files
[4]: https://console.developers.google.com/apis/credentials/oauthclient?project=wb-test-trace
[5]: /WormBase/pseudoace
[6]: /WormBase/db-migration

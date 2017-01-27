# down

Web app(s) for exploring and querying the WormBase database (datomic).

## Web Application(s)

Web applications which use the [pseduoace][5]
representation of the [Wormbase database][6]

This repository comprises of two conceptually separate applications:

* trace
  A view of the raw data in the database.
  Each object has it's own uri, e.g: `/view/WBGene00000001`

* colonnade
  A tool for querying the database. Functionality loosely resembles
  that of [BioMart][1] or "Table Maker".
  By default, links to `trace` views of the search results.  Results
  can be exported in CSV, ACeDB (with or without timestamps) and ACeDB
  "KeySets".

These web applications are written in Clojure(Script).

### Development quick-start

```bash
lein clean && \
lein cljsbuild once dev && \
lein ring server-headless
```

By default, the server will run on port 3000.

To view in a web browser:
```bash
python -m webbrowser http://localhost:3000/colonnade/
```

In the examples below, please use `dev` or `prod` for the value of
`TARGET_PLATFORM`.

    1. Download and install [leiningen](http://leiningen.org/)

    2. TBD: authentication
       The application supports authentication, but is currently not
       working.  When desiring authentication,
       we need set the environment variables:

       `TRACE_OAUTH2_CLIENT_SECRET` and `TRACE_OAUTH2_CLIENT_ID`

       to their respective values, obtained from
       the
       [google developers console][4]

    3. Build the application, checking for old dependencies

       ```bash
       lein do deps, ancient
       lein cljsbuild once "${TARGET_PLATFORM}"
       ```

    4. Run the web applications

       ```bash
        lein with-profile "${TARGET_PLATFORM}" \
            ring server-headless "${TRACE_PORT}"
       ```
       or

      ```bash
      lein with-profile "${TARGET_PLATFORM}" \
          ring server-headless "${TRACE_PORT}"
      ```

## Docker

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

[1]: http://parasite.wormbase.org/biomart/
[2]: https://github.com/WormBase/wormbase-architecture/wiki/AWS-Credentials
[3]: http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/AWSHowTo.cloudwatchlogs.html#AWSHowTo.cloudwatchlogs.files
[4]: https://console.developers.google.com/apis/credentials/oauthclient?project=wb-test-trace
[5]: /WormBase/pseudoace
[6]: /WormBase/db-migration


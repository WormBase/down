# Datomic Curation Tools

A set of tools for use with the WormBase Datomic database.

## Clojure Script Web Application(s) ##

Web applications which use the [pseduoace](https://github.com/WormBase/pseudoace)
representation of the [Wormbase database migration](https://github.com/WormBase/db-migration)

Application are named:

* trace: Querying and viewing of the objects in the migrated database
   using a "Table Maker" style interface.

* colonnnade: Editing objects in the migrated database (links to
   trace)


## Dependencies ##

* Datomic
* pseudoace

## Environment setup

It is assumed you have already configured the `aws` cli, and set the
following environment variables somewhere in your shell.  Values can
be provided by an adminstrator of AWS WormBase account if you do not
know them.

    AWS_DEFAULT_PROFILE
    AWS_DEFAULT_REGION
    AWS_ACCESS_KEY_ID
    AWS_SECRET_ACCESS_KEY
    AWS_EB_PROFILE

### Quick start

If you already have leiningen installed and are used to running
clojure projects, then the following command chain will start an
instance of this application (locally) for development, pointing at
the production (read-only) datomic database:


```bash
lein cljsbuild once prod dev && \
lein minify-assets prod dev && \
lein with-profile prod ring server-headless
```

The server will be running on port 3000 (jetty default).
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
       [google developers console](https://console.developers.google.com/apis/credentials/oauthclient?project=wb-test-trace)

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

Ensure the following two repositories are created in the [ECR][Elastic Container Registry]:

  * wormbase/datomic-curaiton-tools
  * wormbase/datomic-curaiton-tools_nginx-proxy

```bash
aws ecr describe-repositories
```

If they are not, then create them with:

```bash

aws ecr create-repository wormbase/datomic-curation-tools
aws ecr create-repository wormbase/datomic-curation-tools_nginx-proxy
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

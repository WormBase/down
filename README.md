# Hinxton Curation Tools

A set of tools for use with the Datomic database.

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

1. Download and install [leiningen](http://leiningen.org/)
2. Configure the environment
   Use the following script.

   ```bash

    source env.sh
    ```
  Set TRACE_OAUTH2_CLIENT_SECRET and TRACE_OAUTH2_CLIENT_ID to the
  respective values.  The values for these variables are obtained from
  the
  [google developers console](https://console.developers.google.com/apis/credentials/oauthclient?project=wb-test-trace)


3. Build the application, checking for old dependencies

   ```bash
   lein do deps, ancient, cljsbuild once
   ```

4. Run the web applications
  ```bash

  lein run
  ```

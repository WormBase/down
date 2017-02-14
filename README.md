# down

Web app(s) for exploring and querying the WormBase database (datomic).

## Web Application(s)

Web applications which use the [pseduoace][5]
representation of the [Wormbase database][6]

This repository comprises of two conceptually separate applications:

* TrACeViewer
  A view of the raw data in the database.
  Each object has it's own uri, e.g: `/view/WBGene00000001`

* Colonnade
  A tool for querying the database. Functionality loosely resembles
  that of [BioMart][1] or "Table Maker".
  By default, links to `trace` views of the search results.  Results
  can be exported in CSV, ACeDB (with or without timestamps) and ACeDB
  "KeySets".

These web applications are written in Clojure(Script).

### Development quick-start

To run the web-server:

```bash
lein clean && lein minify-assets dev && lein ring server-headless
```

By default, the server will run on port 3000:

`http://localhost:3000/colonnade`

Topics:

 * [Development](/docs/develop.md)
 * [Deployment](/docs/deploy.md)
 * All [documentation](/docs)

[1]: http://parasite.wormbase.org/biomart/
[2]: /docs


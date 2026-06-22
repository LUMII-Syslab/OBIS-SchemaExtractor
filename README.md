# OBIS-SchemaExtractor

OBIS Schema Extractor is a Java-based web application — a REST service for extracting and analyzing data schemas from SPARQL endpoints. It queries an endpoint, infers the structure of the underlying RDF data, and returns a structured JSON schema model.

Full documentation, execution parameter descriptions, example runs, and guidance on using the produced schemas in data visualization and analysis pipelines is available in the [OBIS Schema Extractor wiki](https://github.com/LUMII-Syslab/OBIS-SchemaExtractor/wiki).

**Contents**
- [Running the Application](#running-the-application)
  - [Option 1: Executable JAR](#option-1-executable-jar)
  - [Option 2: Docker](#option-2-docker)
- [Available Services](#available-services)
  - [V2: Build Schema from Endpoint](#v2-build-schema-from-endpoint-post-schema-extractor-restv2endpointbuildfullschema)
  - [V2: Build Schema from Config File](#v2-build-schema-from-config-file-post-schema-extractor-restv2endpointbuildfullschemafromconfigfile)
- [Archive / Deprecated (V1 Services)](#archive--deprecated-v1-services)
  - [V1: SPARQL Endpoint](#v1-sparql-endpoint-get-schema-extractor-restv1endpointbuildfullschema)
  - [V1: RDF/OWL File](#v1-rdfowl-file-post-schema-extractor-restv1owlfilebuildfullschema)
- [Development](#development)

---

## Running the Application

### Option 1: Executable JAR

The application is packaged as an executable JAR and requires [Java 17](https://www.java.com/en/).

1. Install Java 17.
2. Copy [`build/schema-extractor-exec.jar`](build/schema-extractor-exec.jar) to any folder along with the other files in the `build` directory.
3. Run the JAR:
   ```sh
   java -jar schema-extractor-exec.jar
   ```
4. Open Swagger UI at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

To use a different port:
```sh
java -jar schema-extractor-exec.jar --server.port=1234
```

### Option 2: Docker

Pull and run the latest image from the GitHub Container Registry:

```sh
docker pull ghcr.io/lumii-syslab/obis-schemaextractor:latest
docker run -p 8080:8080 ghcr.io/lumii-syslab/obis-schemaextractor:latest
```

Or use the provided `docker-compose.yml` to build and run locally (maps container port 8080 to host port 3030):

```sh
docker compose up
```

Once running, Swagger UI is available at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) (or the port you configured).

---

## Available Services

All current (V2) services are available under the base path `/schema-extractor-rest/v2` and are documented interactively via Swagger UI at `http://server:port/swagger-ui.html`. The Swagger page describes all request parameters, their types, default values, and expected response models, and allows running requests directly from the browser.

### V2: Build Schema from Endpoint — `POST /schema-extractor-rest/v2/endpoint/buildFullSchema`

Connects to a SPARQL endpoint, queries it to extract and analyze the RDF data structure, and returns a full schema model as JSON.

Key parameters include the endpoint URL, optional graph name, cardinality calculation mode, subclass and property-relation analysis flags, and optional CSV files to restrict which classes or properties are analyzed. When `saveThisConfig=true` (default), the effective configuration is also saved as a YAML file on the server for later reuse.

```sh
curl -X POST "http://localhost:8080/schema-extractor-rest/v2/endpoint/buildFullSchema?endpointUrl=http%3A%2F%2Flocalhost%3A8890%2Fsparql&graphName=MiniUniv&calculateSubClassRelations=true&calculatePropertyPropertyRelations=true&calculateDomainAndRangePairs=true&calculateDataTypes=true&calculateCardinalitiesMode=propertyLevelAndClassContext&minimalAnalyzedClassSize=0&enableLogging=true" \
  -H "accept: application/json" \
  -H "Content-Type: multipart/form-data"
```

Example JSON response: [SampleExtractedSchemaV2.json](build/SampleExtractedSchemaV2.json)

All parameters are described in detail in Swagger UI.

### V2: Build Schema from Config File — `POST /schema-extractor-rest/v2/endpoint/buildFullSchemaFromConfigFile`

Runs the same schema extraction as above, but reads all parameters from an uploaded YAML configuration file instead of query parameters. This is useful for automating extractions, reproducing a previous run, or managing complex parameter sets.

Upload a YAML configuration file as `configurationFile`. An example configuration file with all available parameters and their descriptions is provided at [`build/example-config.yml`](build/example-config.yml).

```sh
curl -X POST "http://localhost:8080/schema-extractor-rest/v2/endpoint/buildFullSchemaFromConfigFile" \
  -H "accept: application/json" \
  -F "configurationFile=@build/example-config.yml"
```

---

## Archive / Deprecated (V1 Services)

The V1 services under `/schema-extractor-rest/v1` are deprecated and kept for backwards compatibility only. New integrations should use the V2 services above.

### V1: SPARQL Endpoint — `GET /schema-extractor-rest/v1/endpoint/buildFullSchema`

Extracts and analyzes data from a SPARQL endpoint and builds a full schema model (version 1). All parameters are passed as query string arguments.

```sh
curl -X GET "http://localhost:8080/schema-extractor-rest/v1/endpoint/buildFullSchema?endpointUrl=http%3A%2F%2Flocalhost%3A8890%2Fsparql&graphName=MiniUniv&version=fewComplexQueries&mode=full&enableLogging=false&excludeSystemClasses=true&excludeMetaDomainClasses=false&excludePropertiesWithoutClasses=true" \
  -H "accept: application/json"
```

Example JSON response: [SampleExtractedSchemaV1.json](build/SampleExtractedSchemaV1.json)

### V1: RDF/OWL File — `POST /schema-extractor-rest/v1/owlFile/buildFullSchema`

Reads an uploaded OWL/RDF ontology file and converts it to JSON schema format. Optionally enhances the result with instance data from a SPARQL endpoint if an `endpointUrl` is provided.

```sh
curl -X POST "http://localhost:8080/schema-extractor-rest/v1/owlFile/buildFullSchema?abstractPropertyThreshold=10&propertyInstanceCountThreshold=1000&calculateCardinalities=false" \
  -H "accept: application/json" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@SampleTestOntology.owl;type="
```

Example RDF schema: [SampleTestOntology.owl](build/SampleTestOntology.owl)

Example JSON response: [SampleTestOntology_Schema.json](build/SampleTestOntology_Schema.json)

---

## Development

Clone the repository and build with Maven:

```sh
git clone https://github.com/LUMII-Syslab/OBIS-SchemaExtractor.git
cd OBIS-SchemaExtractor
mvn clean install
cd target
java -jar schema-extractor-exec.jar
```

If you are using IntelliJ IDEA, you can start the application by running `SchemaExtractorApplication.java` directly.

After startup, Swagger UI is available at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

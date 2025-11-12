# OBIS-SchemaExtractor

OBIS Schema Extractor is Java based web application - REST controller to process data schema extraction from SPARQL endpoints or OWL/RDF files.
The structure of the response JSON data schema format is described at http://viziquer.lumii.lv/schema-extractor.

**Contents**
- [Deployment and Running](#deployment-and-running)
- [Available Services and Documentation](#available-services-and-documentation)
  - [SPARQL Endpoint Usage V1](#sparql-endpoint-usage-v1)
  - [SPARQL Endpoint Usage V2](#sparql-endpoint-usage-v2)
  - [RDF/OWL File Usage](#rdfowl-file-usage)
- [Development](#development)

## Deployment and Running

OBIS Schema Extractor artifact is packaged executable JAR file and needs [Java 17](https://www.java.com/en/) to run.

1. install Java
2. copy [build/schema-extractor-exec.jar](build/schema-extractor-exec.jar) to any folder
3. run executable JAR file `java -jar schema-extractor-exec.jar`
4. navigate to http://server:port/swagger-ui.html (default URL - http://localhost:8080/swagger-ui.html)
5. configure the parameters, as described on the browser page

If you need different port, run executable JAR file `java -jar schema-extractor-exec.jar --server.port=1234`

## Available Services and Documentation

Schema Extractor RESTful APIs are expressed using JSON services and described in Swagger http://server:port/swagger-ui.html with requests and response models examples.

| Service Type and Version  | Description |
| ------------- | ------------- |
| [V1 SPARQL Endpoint](#sparql-endpoint-usage-v1)  | Extract and analyze data from SPARQL endpoint and build full schema model (version 1)  |
| [V1 OWL/RDF File](#rdfowl-file-usage) | Extract and analyze schema from OWL ontology file and then enhance with data from SPARQL endpoint (if provided)  |
| [V2 SPARQL Endpoint](#sparql-endpoint-usage-v2) | Extract and analyze data from SPARQL endpoint and build full schema model (version 2)  |

### SPARQL Endpoint Usage V1

GET http://server:port/schema-extractor-rest/v1/endpoint/buildFullSchema

```sh
curl -X GET "http://localhost:8080/schema-extractor-rest/v1/endpoint/buildFullSchema?endpointUrl=http%3A%2F%2Flocalhost%3A8890%2Fsparql&graphName=MiniUniv&version=fewComplexQueries&mode=full&enableLogging=false&excludeSystemClasses=true&excludeMetaDomainClasses=false&excludePropertiesWithoutClasses=true" -H "accept: application/json"
```
Example JSON response - [SampleExtractedSchemaV1.json](build/SampleExtractedSchemaV1.json)

### SPARQL Endpoint Usage V2

POST http://server:port/schema-extractor-rest/v2/endpoint/buildFullSchema
  
```sh
curl -X POST "http://localhost:8080/schema-extractor-rest/v2/endpoint/buildFullSchema?endpointUrl=http%3A%2F%2Flocalhost%3A8890%2Fsparql&graphName=MiniUniv&calculateSubClassRelations=true&calculatePropertyPropertyRelations=true&calculateDomainAndRangePairs=true&calculateDataTypes=true&calculateCardinalitiesMode=propertyLevelAndClassContext&minimalAnalyzedClassSize=0&enableLogging=true" -H "accept: application/json" -H "Content-Type: multipart/form-data"
```
Example JSON response - [SampleExtractedSchemaV2.json](build/SampleExtractedSchemaV2.json)

### RDF/OWL File Usage

POST http://server:port/schema-extractor-rest/v1/owlFile/buildFullSchema

```sh
curl -X POST "http://localhost:8080/schema-extractor-rest/v1/owlFile/buildFullSchema?abstractPropertyThreshold=10&propertyInstanceCountThreshold=1000&calculateCardinalities=false" -H "accept: application/json" -H "Content-Type: multipart/form-data" -F "file=@SampleTestOntology.owl;type="
```

Uploaded RDF/OWL schema information is converted to JSON format with data schema information.

Example RDF schema - [SampleTestOntology.owl](build/SampleTestOntology.owl)

Example JSON response - [SampleTestOntology_Schema.json](build/SampleTestOntology_Schema.json)



## Development

Checkout the project from the GIT

```sh
$ cd OBIS-SchemaExtractor
$ mvn clean install
$ cd target
$ java -jar schema-extractor-exec.jar
```

If you are using IDEA, you can start services by directly running *SchemaExtractorApplication.java*

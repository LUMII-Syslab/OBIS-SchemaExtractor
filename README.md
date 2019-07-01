# OBIS-SchemaExtractor

OBIS Schema Extractor is Java based web application - REST controller to process data schema extraction from SPARQL endpoints.
The structure of the response JSON data schema format is described at http://viziquer.lumii.lv/schema-extractor .

**REST API DESCRIPTION URL:** http://server:port/swagger-ui.html

## Installation

OBIS Schema Extractor artifact is packaged executable JAR file and needs [Java 1.8](https://www.java.com/en/) to run.

1. install Java
2. copy [build/schema-extractor-exec.jar](build/schema-extractor-exec.jar) to any folder
3. run executable JAR file `java -jar schema-extractor-exec.jar`
4. navigate to *http://server:port/swagger-ui.html* (default URL - http://localhost:8080/swagger-ui.html)
5. configure the parameters, as described on the browser page

If you need different port, run executable JAR file `java -jar schema-extractor-exec.jar --server.port=1234`

If you want to use local SPARQL endpoint then install and configure it accordingly to the software official instructions. For example, [Virtuoso](http://virtuoso.openlinksw.com/)

## SPARQL Endpoint Usage

**Available Services:**
- GET http://server:port/schema-extractor-rest/endpoint/buildClasses
- GET http://server:port/schema-extractor-rest/endpoint/buildFullSchema
  
**Available Service Path Parameters:**
- **endpointUrl** (mandatory) - SPARQL endpoint URL
- **graphName** (optional, recommended) - named graph used for schema extraction. If no graph name provided, the search will involve all graphs from the endpoints. Note - it may impact performance, thus it is recommended to provide required graph.
- **mode** (optional) - extraction complexity. If no mode provided, it is processed as *full* by default. If the data source is large or complex, full mode may impact performance.
  - **full** analyzed items: classes, class hierarchy (superclass/subclass), data type properties, object type properties with domain/range associations, property data types, property cardinalities
  - **data** analyzed items: classes, class hierarchy (superclass/subclass), data type properties, object type properties with domain/range associations, property data types
  - **simple** - analyzed items: classes, class hierarchy (superclass/subclass), data type properties, object type properties with domain/range associations
- **enableLogging** (optional) - SPARQL query logging to the file
  - **false** (default): executed queries are not logged
  - **true**: executed queries are logged to the file /logs/obis-extractor.log (in web application server root folder)
- **excludeSystemClasses** (optional) - indicator whether Virtuoso system classes (namespace http://www.openlinksw.com/schemas/virtrdf) should be added to the response
  - **false**: Virtuoso system classes are added to the final list of schema classes
  - **true** (default): Virtuoso system classes are skipped
- **excludeMetaDomainClasses** (optional) - indicator whether meta domain classes (namespaces http://www.w3.org/2002/07/owl, http://www.w3.org/2000/01/rdf-schema, http://www.w3.org/1999/02/22-rdf-syntax-ns) should be added to the response
  - **false** (default): OWL/RDF domain classes are considered as real data classes and are added to the final list of schema classes
  - **true**: OWL/RDF domain classes are skipped, however, OWL/RDF properties are assigned to data domain/range pairs if real data classes have these properties

**Request URL Example:**
- http://localhost:8080/schema-extractor-rest/endpoint/buildFullSchema?endpointUrl=http://localhost:8890/sparql&graphName=MiniUniv&mode=simple&enableLogging=true&excludeSystemClasses=true&excludeMetaDomainClasses=true

**Response Example:**
- JSON file with extracted schema information, example [SampleExtractedSchema.json](build/SampleExtractedSchema.json)


## RDF/OWL File Usage

**URL:** http://server:port/schema-extractor-rest/owlFile/buildFullSchema

**curl:**
```sh
curl -X POST "http://localhost:8080/schema-extractor-rest/owlFile/buildFullSchema" -H "accept: application/json" -H "Content-Type: multipart/form-data" -F "file=@SampleTestOntology.owl;type="
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
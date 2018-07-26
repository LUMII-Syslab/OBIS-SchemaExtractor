# OBIS-SchemaExtractor

OBIS Schema Extractor is Java based web application - REST controller to process RDF schema extraction from SPARQL endpoints.

#### SPARQL endpoint usage

**URL:** *http://server:port/obis-rest/services/schema*
  
**Path parameters:**
- **endpoint** (mandatory) - SPARQL endpoint URL
- **graph** (optional, recommended) - named graph used for schema extraction. If no graph name provided, the search will involve all graphs from the endpoints. Note - it may impact performance, thus it is recommended to provide required graph.
- **mode** (optional) - extraction complexity. If no mode provided, it is processed as *full* by default. If the data source is large or complex, full mode may impact performance.
  - **full** analyzed items: classes, class hierarchy (superclass/subclass), data type properties, object type properties with domain/range associations, property data types, property cardinalities
  - **data** analyzed items: classes, class hierarchy (superclass/subclass), data type properties, object type properties with domain/range associations, property data types
  - **simple** - analyzed items: classes, class hierarchy (superclass/subclass), data type properties, object type properties with domain/range associations

**Example:**
- http://localhost:8080/obis-rest/services/schema?endpoint=http://localhost:8890/sparql&graph=MiniUniv&mode=simple

**Response:**
- JSON file with schema information, example [SampleSchema.json](build/SampleSchema.json)


#### RDF/OWL file upload

**URL:** *http://server:port/obis-rest/schemaFromOwl.html*

Uploaded RDF/OWL schema information is converted to JSON format with schema information.

Example RDF schema - [SampleTestOntology.owl](build/SampleTestOntology.owl)
Example JSON response - [SampleSchema.json](build/SampleSchema.json)


### Installation

OBIS Schema Extractor needs [Java 1.8](https://www.java.com/en/) and [Apache Tomcat 8.x](https://tomcat.apache.org/index.html) to run. 
(*Note* - OBIS Schema Extractor artifact is packaged war file, thus you can use also other Java web application servers. Apache Tomcat 8.x is officially tested by the developers.)

If you want to use local SPARQL endpoint then install and configure it according to the software official instructions. For example, [Virtuoso](http://virtuoso.openlinksw.com/)

1. Install the dependencies
2. copy [build/obis-rest.war](build/obis-rest.war) to server web root
3. start the server
4. navigate to *http://server:port/obis-rest* (default URL - http://localhost:8080/obis-rest)


### Development

Additionally to Java and Web application server, install [Apache Maven](https://maven.apache.org/)

Checkout the project from the GIT

```sh
$ cd OBIS-SchemaExtractor
$ mvn clean install
```
Copy **target/obis-rest.war** to web application server

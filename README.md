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
- **log** (optional) - SPARQL query logging to the file
  - **false** (default): executed queries are not logged
  - **true**: executed queries are logged to the file /logs/obis-extractor.log (in web application server root folder)
- **excludeSystemClasses** (optional) - indicator whether Virtuoso system classes (namespace http://www.openlinksw.com/schemas/virtrdf) should be added to the response
  - **false**: Virtuoso system classes are added to the final list of schema classes
  - **true** (default): Virtuoso system classes are skipped
- **excludeMetaDomainClasses** (optional) - indicator whether meta domain classes (namespaces http://www.w3.org/2002/07/owl, http://www.w3.org/2000/01/rdf-schema, http://www.w3.org/1999/02/22-rdf-syntax-ns) should be added to the response
  - **false** (default): OWL/RDF domain classes are considered as real data classes and are added to the final list of schema classes
  - **true**: OWL/RDF domain classes are skipped, however, OWL/RDF properties are assigned to data domain/range pairs if real data classes have these properties

**Example:**
- http://localhost:8080/obis-rest/services/schema?endpoint=http://localhost:8890/sparql&graph=MiniUniv&mode=simple&log=true&excludeSystemClasses=true&excludeMetaDomainClasses=true

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

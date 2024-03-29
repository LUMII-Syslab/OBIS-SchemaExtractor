package lv.lumii.obis.schema.services.reader;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.model.v1.Schema;
import lv.lumii.obis.schema.model.v1.SchemaParameter;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderProcessingData;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.io.InputStream;

@Service
@Slf4j
public class OWLOntologyReader {

    @Autowired
    @Setter
    private OWLOntologyProcessor ontologyProcessor;
    @Autowired
    @Setter
    private OWLClassProcessor classProcessor;
    @Autowired
    @Setter
    private OWLDataTypePropertyProcessor dataTypePropertyProcessor;
    @Autowired
    @Setter
    private OWLObjectTypePropertyProcessor objectTypePropertyProcessor;
    @Autowired
    @Setter
    private OWLInversePropertyProcessor inversePropertyProcessor;
    @Autowired
    @Setter
    private OWLPrefixesProcessor prefixesProcessor;

    @Nonnull
    public Schema readOWLOntology(@Nonnull InputStream inputStream, @Nonnull OWLOntologyReaderRequest readerRequest) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = null;
        try {
            ontology = manager.loadOntologyFromOntologyDocument(inputStream);
        } catch (OWLOntologyCreationException e) {
            log.error("Cannot read OWLOntology from the file", e);
        }
        if (ontology == null || ontology.isEmpty() || ontology.classesInSignature().count() <= 0) {
            log.error("Empty ontology object or no defined classes");
            return new Schema();
        }
        return processOWLOntology(ontology, readerRequest);
    }

    @Nonnull
    private Schema processOWLOntology(@Nonnull OWLOntology inputOntology, @Nonnull OWLOntologyReaderRequest readerRequest) {

        // initialize result schema
        IRI ontologyIRI = inputOntology.getOntologyID().getOntologyIRI().orElse(null);
        Schema schema = new Schema();
        schema.setName((ontologyIRI != null) ? ontologyIRI.getIRIString() : null);
        buildReaderProperties(readerRequest, schema);

        // initialize intermediate processing data
        OWLOntologyReaderProcessingData processingData = new OWLOntologyReaderProcessingData();

        // invoke all OWL processors
        ontologyProcessor.process(inputOntology, schema, processingData, readerRequest);
        classProcessor.process(inputOntology, schema, processingData, readerRequest);
        dataTypePropertyProcessor.process(inputOntology, schema, processingData, readerRequest);
        objectTypePropertyProcessor.process(inputOntology, schema, processingData, readerRequest);
        inversePropertyProcessor.process(inputOntology, schema, processingData, readerRequest);
        prefixesProcessor.process(inputOntology, schema, processingData, readerRequest);

        return schema;
    }

    protected void buildReaderProperties(@Nonnull OWLOntologyReaderRequest request, @Nonnull Schema schema) {
        if (request.getExcludedNamespaces() != null && !request.getExcludedNamespaces().isEmpty()) {
            schema.getParameters().add(
                    new SchemaParameter(SchemaParameter.PARAM_NAME_EXCLUDED_NAMESPACES, request.getExcludedNamespaces().toString()));
        }
    }

}

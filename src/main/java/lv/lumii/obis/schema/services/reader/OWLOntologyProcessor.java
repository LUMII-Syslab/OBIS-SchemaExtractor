package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;

@Service
public class OWLOntologyProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData) {
        // do nothing by default
    }

}

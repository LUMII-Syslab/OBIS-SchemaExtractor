package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.v1.Schema;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderProcessingData;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;

@Service
public class OWLOntologyProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull OWLOntologyReaderProcessingData processingData,
                        @Nonnull OWLOntologyReaderRequest readerRequest) {
        // do nothing by default
    }

}

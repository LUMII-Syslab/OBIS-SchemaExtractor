package lv.lumii.obis.schema.services.enhancer;

import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.services.enhancer.dto.OWLOntologyEnhancerRequest;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;

@Service
public class OWLOntologyEnhancer {

    @Nonnull
    public Schema enhanceSchema(@Nonnull Schema inputSchema, @Nonnull OWLOntologyEnhancerRequest readerRequest) {
        System.out.println(readerRequest.getEndpointUrl() + ' ' + readerRequest.getGraphName());
        return inputSchema;
    }

}

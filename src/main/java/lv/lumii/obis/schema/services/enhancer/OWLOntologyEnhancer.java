package lv.lumii.obis.schema.services.enhancer;

import lombok.Setter;
import lv.lumii.obis.schema.model.Schema;
import lv.lumii.obis.schema.services.SchemaUtil;
import lv.lumii.obis.schema.services.common.QueryResult;
import lv.lumii.obis.schema.services.common.SparqlEndpointConfig;
import lv.lumii.obis.schema.services.common.SparqlEndpointProcessor;
import lv.lumii.obis.schema.services.enhancer.dto.OWLOntologyEnhancerRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.List;

@Service
public class OWLOntologyEnhancer {

    @Autowired
    @Setter
    private SparqlEndpointProcessor sparqlEndpointProcessor;

    @Nonnull
    public Schema enhanceSchema(@Nonnull Schema inputSchema, @Nonnull OWLOntologyEnhancerRequest enhancerRequest) {
        SparqlEndpointConfig endpointConfig = new SparqlEndpointConfig(enhancerRequest.getEndpointUrl(), enhancerRequest.getGraphName(), false);

        // find each class instance count
        inputSchema.getClasses().forEach(schemaClass -> {
            String query = SchemaEnhancerQueries.FIND_CLASS_INSTANCE_COUNT;
            query = query.replace(SchemaEnhancerQueries.SPARQL_QUERY_BINDING_NAME_CLASS_A, schemaClass.getFullName());
            List<QueryResult> queryResults = sparqlEndpointProcessor.read(endpointConfig, "FIND_CLASS_INSTANCE_COUNT", query);
            if (!queryResults.isEmpty()) {
                String instancesCountStr = queryResults.get(0).get(SchemaEnhancerQueries.SPARQL_QUERY_BINDING_NAME_INSTANCES_COUNT);
                schemaClass.setInstanceCount(SchemaUtil.getLongValueFromString(instancesCountStr));
                if (schemaClass.getInstanceCount() == 0) {
                    schemaClass.setIsAbstract(true);
                }
            }
        });

        return inputSchema;
    }

}

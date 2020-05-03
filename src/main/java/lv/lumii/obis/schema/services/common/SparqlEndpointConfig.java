package lv.lumii.obis.schema.services.common;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SparqlEndpointConfig {

    private String endpointUrl;
    private String graphName;
    private boolean enableLogging;

    public SparqlEndpointConfig(String endpointUrl, String graphName, boolean enableLogging) {
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
    }
}

package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SparqlEndpointConfig {

    private String endpointUrl;
    private String graphName;
    private boolean enableLogging;
    private boolean isPostRequest;

    public SparqlEndpointConfig(String endpointUrl, String graphName, boolean enableLogging, boolean isPostRequest) {
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = isPostRequest;
    }

    public SparqlEndpointConfig(String endpointUrl, String graphName, boolean enableLogging) {
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = false;
    }
}

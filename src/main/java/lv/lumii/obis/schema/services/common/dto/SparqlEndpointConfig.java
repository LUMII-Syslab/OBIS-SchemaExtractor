package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SparqlEndpointConfig {

    private String endpointUrl;
    private String graphName;
    private boolean enableLogging;
    private boolean isPostRequest;
    private Long timeout;

    public SparqlEndpointConfig(String endpointUrl, String graphName, boolean enableLogging, boolean isPostRequest, Long timeout) {
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = isPostRequest;
        this.timeout = timeout;
    }

    public SparqlEndpointConfig(String endpointUrl, String graphName, boolean enableLogging, Long timeout) {
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = false;
        this.timeout = timeout;
    }
}

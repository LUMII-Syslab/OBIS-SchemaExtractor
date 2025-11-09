package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SparqlEndpointConfig {

    private String correlationId;
    private String endpointUrl;
    private String graphName;
    private boolean enableLogging;
    private boolean isPostRequest;
    private Long timeout;

    private Long delayOnFailure;
    private Long waitingTimeForEndpoint;

    public SparqlEndpointConfig(String correlationId, String endpointUrl, String graphName, boolean enableLogging, boolean isPostRequest,
                                Long timeout, Long delayOnFailure, Long waitingTimeForEndpoint) {
        this.correlationId = correlationId;
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = isPostRequest;
        this.timeout = timeout;
        this.delayOnFailure = delayOnFailure;
        this.waitingTimeForEndpoint = waitingTimeForEndpoint;
    }

    public SparqlEndpointConfig(String correlationId, String endpointUrl, String graphName, boolean enableLogging,
                                boolean isPostRequest, Long timeout) {
        this.correlationId = correlationId;
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = isPostRequest;
        this.timeout = timeout;
    }

    public SparqlEndpointConfig(String correlationId, String endpointUrl, String graphName, boolean enableLogging, Long timeout) {
        this.correlationId = correlationId;
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = false;
        this.timeout = timeout;
    }
}

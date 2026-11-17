package lv.lumii.obis.schema.services.common.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SparqlEndpointConfig {

    private String correlationId;
    private String endpointUrl;
    private String graphName;
    private boolean enableLogging;
    private boolean isPostRequest;
    private Boolean getMethodAvailable;
    private Boolean postMethodAvailable;
    // Failure that made the retry switch HTTP method; kept only while the switched retry succeeds.
    private String methodSwitchCause;
    private String acceptHeaderForSparqlResults;
    private Long timeout;
    private Long delayOnFailure;
    private Long waitingTimeForEndpoint;

    public SparqlEndpointConfig(String correlationId, String endpointUrl, String graphName, boolean enableLogging, boolean isPostRequest,
                                String acceptHeaderForSparqlResults, Long timeout, Long delayOnFailure, Long waitingTimeForEndpoint) {
        this.correlationId = correlationId;
        this.endpointUrl = endpointUrl;
        this.graphName = graphName;
        this.enableLogging = enableLogging;
        this.isPostRequest = isPostRequest;
        this.acceptHeaderForSparqlResults = acceptHeaderForSparqlResults;
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

    // Health-check copy: same endpoint, graph, logging, Accept header and waiting settings, with its own method and timeout.
    public SparqlEndpointConfig forHealthCheck(boolean isPostRequest, Long timeout) {
        return new SparqlEndpointConfig(correlationId, endpointUrl, graphName, enableLogging, isPostRequest,
                acceptHeaderForSparqlResults, timeout, delayOnFailure, waitingTimeForEndpoint);
    }
}

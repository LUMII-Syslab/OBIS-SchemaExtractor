package lv.lumii.obis.schema.services;

public class SparqlEndpointException extends RuntimeException {

    public SparqlEndpointException() {
    }

    public SparqlEndpointException(String message) {
        super(message);
    }

    public SparqlEndpointException(String message, Throwable cause) {
        super(message, cause);
    }
}

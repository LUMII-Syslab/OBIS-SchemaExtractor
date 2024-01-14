package lv.lumii.obis.rest.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

@Getter
@Setter
public class ApiError {

    private HttpStatus status;
    private String message;
    private Throwable exception;

    public ApiError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public ApiError(HttpStatus status, String message, Throwable exception) {
        this(status, message);
        this.exception = exception;
    }
}

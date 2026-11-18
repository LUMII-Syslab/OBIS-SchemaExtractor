package lv.lumii.obis.schema.services.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import lombok.Setter;

import java.util.Map;

/**
 * Logback filter that separates the log lines of schema extraction runs (with {@link ExtractionLog#MDC_KEY} in MDC)
 * from all other log lines.
 * <ul>
 *     <li>{@code insideExtraction = true} - accepts only the lines written during an extraction run;</li>
 *     <li>{@code insideExtraction = false} - accepts only the lines written outside of extraction runs.</li>
 * </ul>
 */
@Setter
public class ExtractionLogFilter extends Filter<ILoggingEvent> {

    private boolean insideExtraction = true;

    @Override
    public FilterReply decide(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        boolean isExtractionEvent = mdc != null && mdc.get(ExtractionLog.MDC_KEY) != null;
        return isExtractionEvent == insideExtraction ? FilterReply.NEUTRAL : FilterReply.DENY;
    }

}

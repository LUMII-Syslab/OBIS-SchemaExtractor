package lv.lumii.obis.schema.services.logging;

import ch.qos.logback.classic.ClassicConstants;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.sift.SiftingAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.sift.AppenderTracker;
import ch.qos.logback.core.spi.AbstractComponentTracker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Routes all log lines of one schema extraction run into a dedicated log file.
 * <p>
 * The log file name is put into the logback MDC under {@link #MDC_KEY}; the SiftingAppender defined in
 * {@code logback-spring.xml} creates a separate log file for every distinct value.
 * The name consists of the endpoint, the named graph and the start date and time of the run, for example
 * {@code vocab.nerc.ac.uk_2026-11-17_140533} or {@code localhost_8890_MiniUniv_2026-11-17_140533}.
 * If the log of the run does not fit in one file, it is continued in the files {@code <name>_2.log}, {@code <name>_3.log}, ...
 * (see {@link ExtractionLogRollingPolicy}).
 * <p>
 * Usage:
 * <pre>
 * try (ExtractionLog ignored = ExtractionLog.start(endpointUrl, graphName)) {
 *     ... extraction ...
 * }
 * </pre>
 * The extraction runs synchronously on the calling thread, so the MDC value is visible to all its log statements.
 */
@Slf4j
public final class ExtractionLog implements AutoCloseable {

    /** MDC key used by the logback SiftingAppender discriminator (must match logback-spring.xml). */
    public static final String MDC_KEY = "extractionLog";
    /** Name of the SiftingAppender in logback-spring.xml. */
    private static final String SIFTING_APPENDER_NAME = "EXTRACTION_LOG";

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern TRAILING_SPARQL = Pattern.compile("(?i)/sparql/?$");
    private static final Pattern UNSAFE_FILE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern REPEATED_UNDERSCORES = Pattern.compile("_{2,}");
    /** Maximum length of the endpoint + graph part of the name (the date and time are always kept). */
    private static final int MAX_SOURCE_NAME_LENGTH = 150;

    /** Log names of the runs in progress - two runs started in the same second must not share a log file. */
    private static final Set<String> ACTIVE_LOG_NAMES = ConcurrentHashMap.newKeySet();

    private final String logName;

    private ExtractionLog(@Nonnull String logName) {
        this.logName = logName;
        MDC.put(MDC_KEY, logName);
    }

    /**
     * Starts routing the log of the current thread into a new run-specific log file.
     */
    @Nonnull
    public static ExtractionLog start(@Nullable String endpointUrl, @Nullable String graphName) {
        String logName = reserveUniqueLogName(buildLogName(LocalDateTime.now(), endpointUrl, graphName));
        String graphInfo = StringUtils.isNotBlank(graphName) ? " and the named graph [ " + graphName + " ]" : "";
        // logged before switching the MDC, so the common log tells where the run-specific log is
        log.info("Schema extraction log for the endpoint [ {} ]{} is written to the file [ {} ]", endpointUrl, graphInfo, logName);
        ExtractionLog extractionLog = new ExtractionLog(logName);
        log.info("Schema extraction log for the endpoint [ {} ]{}", endpointUrl, graphInfo);
        return extractionLog;
    }

    /**
     * Closes the run-specific log file; the following log lines of the thread go to the common log again.
     */
    @Override
    public void close() {
        // the special marker tells the SiftingAppender that the nested file appender can be closed and released
        log.info(ClassicConstants.FINALIZE_SESSION_MARKER, "Schema extraction request processing finished, closing this log file");
        releaseLogFile();
        ACTIVE_LOG_NAMES.remove(logName);
        MDC.remove(MDC_KEY);
        log.info("Schema extraction log [ {} ] is closed", logName);
    }

    /**
     * The FINALIZE_SESSION_MARKER only marks the nested file appender as finished; logback closes it later, when some
     * other event reaches the SiftingAppender. Close it right away, so the file is released (e.g. can be moved on Windows)
     * also when the server stays idle after the run.
     */
    private void releaseLogFile() {
        try {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
                return;
            }
            Appender<ILoggingEvent> appender = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(SIFTING_APPENDER_NAME);
            if (appender instanceof SiftingAppender siftingAppender && siftingAppender.getAppenderTracker() != null) {
                AppenderTracker<ILoggingEvent> tracker = siftingAppender.getAppenderTracker();
                tracker.endOfLife(logName);
                // only the finished (lingering) appenders and the ones idle for longer than the sift timeout are removed
                tracker.removeStaleComponents(System.currentTimeMillis() + AbstractComponentTracker.LINGERING_TIMEOUT + 1);
            }
        } catch (RuntimeException e) {
            log.warn("Cannot close the schema extraction log file [ {} ]: {}", logName, e.getMessage());
        }
    }

    /**
     * Normally the name itself is used. If a run with the same endpoint and graph, started in the same second, is still
     * in progress in this application, a number is added: {@code <name>_2}, {@code <name>_3}, ...
     * Existing files are not checked here - {@link ExtractionLogRollingPolicy} never writes into an existing file.
     */
    @Nonnull
    private static String reserveUniqueLogName(@Nonnull String baseName) {
        if (ACTIVE_LOG_NAMES.add(baseName)) {
            return baseName;
        }
        for (int number = 2; ; number++) {
            String candidate = baseName + "_" + number;
            if (ACTIVE_LOG_NAMES.add(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Builds the log file name (without directory and extension): {@code <endpoint>[_<graph>]_<date>_<time>}.
     * <ul>
     *     <li>the URL scheme (http://, https://) is removed from the endpoint and from the graph name;</li>
     *     <li>the trailing "/sparql" is removed from the endpoint;</li>
     *     <li>':', '/' and any other character not allowed in file names are replaced with '_',
     *     several '_' in a row are replaced with one '_';</li>
     *     <li>if the graph name is the same as the endpoint, only the endpoint is used.</li>
     * </ul>
     */
    @Nonnull
    static String buildLogName(@Nonnull LocalDateTime startTime, @Nullable String endpointUrl, @Nullable String graphName) {
        String endpointPart = toEndpointIdentifier(endpointUrl);
        String graphPart = toGraphIdentifier(graphName);

        StringBuilder sourceName = new StringBuilder(endpointPart);
        if (!graphPart.isEmpty() && !graphPart.equalsIgnoreCase(endpointPart)) {
            if (!sourceName.isEmpty()) {
                sourceName.append('_');
            }
            sourceName.append(graphPart);
        }
        if (sourceName.length() > MAX_SOURCE_NAME_LENGTH) {
            // very long graph or endpoint names - keep the beginning and add a hash to keep the names distinct
            String hash = String.format("%08x", sourceName.toString().hashCode());
            sourceName.setLength(MAX_SOURCE_NAME_LENGTH - hash.length() - 1);
            sourceName.append('_').append(hash);
        }

        String timestamp = TIMESTAMP_FORMAT.format(startTime);
        String name = sourceName.isEmpty() ? timestamp : sourceName + "_" + timestamp;
        return REPEATED_UNDERSCORES.matcher(name).replaceAll("_");
    }

    @Nonnull
    static String toEndpointIdentifier(@Nullable String endpointUrl) {
        if (StringUtils.isBlank(endpointUrl)) {
            return "";
        }
        String endpoint = URL_SCHEME.matcher(endpointUrl.trim()).replaceFirst("");
        endpoint = TRAILING_SPARQL.matcher(endpoint).replaceFirst("");
        return sanitize(endpoint);
    }

    @Nonnull
    static String toGraphIdentifier(@Nullable String graphName) {
        if (StringUtils.isBlank(graphName)) {
            return "";
        }
        return sanitize(URL_SCHEME.matcher(graphName.trim()).replaceFirst(""));
    }

    /**
     * Replaces the characters not allowed in file names with '_', several '_' in a row with one '_',
     * and removes '_' from the beginning and the end.
     */
    @Nonnull
    private static String sanitize(@Nonnull String value) {
        String result = UNSAFE_FILE_NAME_CHARS.matcher(value).replaceAll("_");
        result = REPEATED_UNDERSCORES.matcher(result).replaceAll("_");
        return StringUtils.strip(result, "_");
    }

}

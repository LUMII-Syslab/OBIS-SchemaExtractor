package lv.lumii.obis.schema.services.logging;

import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingPolicy;
import ch.qos.logback.core.rolling.RolloverFailure;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import ch.qos.logback.core.spi.ContextAwareBase;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

/**
 * Rolling policy for the run-specific schema extraction log files: the log is written to {@code <fileBaseName>.log}
 * and, when it is full (as decided by the triggering policy), continued in {@code <fileBaseName>_2.log},
 * {@code <fileBaseName>_3.log}, ... The files are not renamed, compressed or deleted, so the numbers follow the order of
 * the log and no part of the log is lost. The policy never writes into an existing file: a number already used by
 * an existing file is skipped, also for the first file (e.g. a run of the same endpoint started in the same second
 * before an application restart).
 * <p>
 * The RollingFileAppender must be configured without the {@code <file>} property - it then writes to
 * {@link #getActiveFileName()} and opens it again after every rollover.
 */
public class ExtractionLogRollingPolicy extends ContextAwareBase implements RollingPolicy {

    /** File name without the ".log" extension, e.g. {@code logs/vocab.nerc.ac.uk_2026-11-17_140533}. */
    @Getter
    @Setter
    private String fileBaseName;

    private int fileNumber = 1;
    private boolean started;

    @Override
    public void start() {
        if (fileBaseName == null || fileBaseName.isBlank()) {
            addError("The property fileBaseName is not set for " + getClass().getSimpleName());
            return;
        }
        skipExistingFiles();
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void rollover() throws RolloverFailure {
        // the previous file is kept as it is, the log continues in the next numbered file
        fileNumber++;
        skipExistingFiles();
    }

    private void skipExistingFiles() {
        while (new File(getActiveFileName()).exists()) {
            fileNumber++;
        }
    }

    @Override
    public String getActiveFileName() {
        return fileNumber == 1 ? fileBaseName + ".log" : fileBaseName + "_" + fileNumber + ".log";
    }

    @Override
    public CompressionMode getCompressionMode() {
        return CompressionMode.NONE;
    }

    @Override
    public void setParent(FileAppender<?> appender) {
        // not needed - the appender asks for the active file name
    }

}

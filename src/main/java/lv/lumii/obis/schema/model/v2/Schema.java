package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorMessage;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class Schema {

    @JsonProperty("SchemaName")
    private String name;

    @JsonProperty("Classes")
    private List<SchemaClass> classes;

    @JsonProperty("Properties")
    private List<SchemaProperty> properties;

    @JsonProperty("namespace")
    private String defaultNamespace;

    @JsonProperty("Prefixes")
    private List<NamespacePrefixEntry> prefixes;

    @JsonProperty("Parameters")
    private SchemaExtractorRequestDto executionParameters;

    @JsonProperty("StartDateTime")
    private String startTime;
    @JsonProperty("EndDateTime")
    private String endTime;

    @JsonProperty("HasBlankNodeObjects")
    private Boolean hasBlankNodeObjects;
    @JsonProperty("HasBlankNodeSubjects")
    private Boolean hasBlankNodeSubjects;

    @JsonProperty("HasErrors")
    private Boolean hasErrors;

    @JsonProperty("HasWarnings")
    private Boolean hasWarnings;

    @JsonProperty("HasNotes")
    private Boolean hasNotes;

    @JsonProperty("Messages")
    private List<SchemaExtractorMessage> messages;

    @Nonnull
    public List<SchemaClass> getClasses() {
        if (classes == null) {
            classes = new ArrayList<>();
        }
        return classes;
    }

    @Nonnull
    public List<SchemaProperty> getProperties() {
        if (properties == null) {
            properties = new ArrayList<>();
        }
        return properties;
    }

    @Nonnull
    public List<NamespacePrefixEntry> getPrefixes() {
        if (prefixes == null) {
            prefixes = new ArrayList<>();
        }
        return prefixes;
    }

    @Nonnull
    public List<SchemaExtractorMessage> getMessages() {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        return messages;
    }

    @Nonnull
    public Boolean getHasErrors() {
        hasErrors = hasMessageFlag(SchemaExtractorMessage.MessageLevel.ERROR);
        return hasErrors;
    }

    @Nonnull
    public Boolean getHasWarnings() {
        hasWarnings = hasMessageFlag(SchemaExtractorMessage.MessageLevel.WARNING) || hasMessageFlag(SchemaExtractorMessage.MessageLevel.WARNING_LOW);
        return hasWarnings;
    }

    @Nonnull
    public Boolean getHasNotes() {
        hasNotes = hasMessageFlag(SchemaExtractorMessage.MessageLevel.INFO);
        return hasNotes;
    }

    @Nonnull
    public Boolean hasMessageFlag(SchemaExtractorMessage.MessageLevel messageLevel) {
        if (getMessages().stream().anyMatch(message -> messageLevel.equals(message.getMessageLevel()))) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

}

package lv.lumii.obis.schema.model.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorError;
import lv.lumii.obis.schema.services.extractor.dto.SchemaExtractorRequestDto;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
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

    @JsonProperty("HasErrors")
    private Boolean hasErrors;

    @JsonProperty("HasWarnings")
    private Boolean hasWarnings;

    @JsonProperty("HasNotes")
    private Boolean hasNotes;

    @JsonIgnore
    private Boolean hasBlankNodeObjects;
    @JsonIgnore
    private Boolean hasBlankNodeSubjects;

    @JsonProperty("Errors")
    private List<SchemaExtractorError> errors;

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
    public List<SchemaExtractorError> getErrors() {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        return errors;
    }

    @Nonnull
    public Boolean getHasErrors() {
        hasErrors = hasErrorFlag(SchemaExtractorError.ErrorLevel.ERROR);
        return hasErrors;
    }

    @Nonnull
    public Boolean getHasWarnings() {
        hasWarnings = hasErrorFlag(SchemaExtractorError.ErrorLevel.WARNING);
        return hasWarnings;
    }

    @Nonnull
    public Boolean getHasNotes() {
        hasNotes = hasErrorFlag(SchemaExtractorError.ErrorLevel.INFO);
        return hasNotes;
    }

    @Nonnull
    public Boolean hasErrorFlag(SchemaExtractorError.ErrorLevel errorLevel) {
        if (getErrors().stream().anyMatch(error -> errorLevel.equals(error.getErrorLevel()))) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

}

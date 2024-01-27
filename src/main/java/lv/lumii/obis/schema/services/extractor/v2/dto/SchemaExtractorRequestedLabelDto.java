package lv.lumii.obis.schema.services.extractor.v2.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class SchemaExtractorRequestedLabelDto {

    @JsonIgnore
    private String labelProperty;
    private String labelPropertyFullOrPrefix;
    private List<String> languages;

    public SchemaExtractorRequestedLabelDto(String labelPropertyFullOrPrefix) {
        this.labelPropertyFullOrPrefix = labelPropertyFullOrPrefix;
    }

    public SchemaExtractorRequestedLabelDto(String labelPropertyFullOrPrefix, List<String> languages) {
        this.labelPropertyFullOrPrefix = labelPropertyFullOrPrefix;
        this.languages = languages;
    }

    @Nonnull
    public List<String> getLanguages() {
        if (languages == null) {
            languages = new ArrayList<>();
        }
        return languages;
    }

    @Override
    public String toString() {
        return "{" + labelPropertyFullOrPrefix + "@" + getLanguages() + "}";
    }
}

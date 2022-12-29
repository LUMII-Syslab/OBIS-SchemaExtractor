package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class SchemaExtractorRequestedLabelDto {

    private String labelProperty;
    private List<String> languages;

    public SchemaExtractorRequestedLabelDto(String labelProperty) {
        this.labelProperty = labelProperty;
    }

    public SchemaExtractorRequestedLabelDto(String labelProperty, List<String> languages) {
        this.labelProperty = labelProperty;
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
        return "{" + labelProperty + "@" + languages + "}";
    }
}

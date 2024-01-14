package lv.lumii.obis.schema.services.extractor.v2.dto;

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

    private String labelProperty;
    private List<String> languages;

    public SchemaExtractorRequestedLabelDto(String labelProperty) {
        this.labelProperty = labelProperty;
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

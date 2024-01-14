package lv.lumii.obis.schema.services.extractor.v2.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter @Getter
@AllArgsConstructor
@NoArgsConstructor
public class SchemaExtractorPredefinedNamespaces {

    @JsonProperty("Prefixes")
    private List<NamespaceItem> namespaceItems;

}

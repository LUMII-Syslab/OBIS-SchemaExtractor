package lv.lumii.obis.schema.services.extractor.v2.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class SchemaExtractorPredefinedNamespaces {

    @JsonProperty("Prefixes")
    private List<NamespaceItem> namespaceItems;

}

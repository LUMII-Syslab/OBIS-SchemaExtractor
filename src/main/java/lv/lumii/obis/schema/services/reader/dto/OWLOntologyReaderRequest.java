package lv.lumii.obis.schema.services.reader.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class OWLOntologyReaderRequest {

    @Schema(description = "List of excluded namespaces")
    private List<String> excludedNamespaces;

}

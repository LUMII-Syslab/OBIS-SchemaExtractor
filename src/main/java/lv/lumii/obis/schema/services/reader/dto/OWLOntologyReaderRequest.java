package lv.lumii.obis.schema.services.reader.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter @Getter
public class OWLOntologyReaderRequest {

    @Parameter(description = "List of excluded namespaces",
            array = @ArraySchema(schema = @Schema(example = "http://www.openlinksw.com/schemas/virtrdf#")))
    private List<String> excludedNamespaces;

}

package lv.lumii.obis.schema.services.extractor.v2.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter @Getter
@AllArgsConstructor
@NoArgsConstructor
public class NamespaceItem {

    private String prefix;
    private String namespace;

}

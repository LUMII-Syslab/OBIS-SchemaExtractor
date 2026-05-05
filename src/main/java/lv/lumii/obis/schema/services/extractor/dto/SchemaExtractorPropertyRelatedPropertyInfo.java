package lv.lumii.obis.schema.services.extractor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchemaExtractorPropertyRelatedPropertyInfo {

    public enum LinkType {follower, incoming, outgoing}

    private String propertyName;
    private Long tripleCount;
    private Long tripleCountBase;

    @JsonIgnore
    private LinkType linkType;

    public SchemaExtractorPropertyRelatedPropertyInfo(String propertyName, Long tripleCount, Long tripleCountBase, LinkType linkType) {
        this.propertyName = propertyName;
        this.tripleCount = tripleCount;
        this.tripleCountBase = tripleCountBase;
        this.linkType = linkType;
    }
}

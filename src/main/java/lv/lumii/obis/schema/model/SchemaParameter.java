package lv.lumii.obis.schema.model;

import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class SchemaParameter {

    public static final String PARAM_NAME_MODE = "mode";
    public static final String PARAM_NAME_EXCLUDE_SYSTEM_CLASSES = "excludeSystemClasses";
    public static final String PARAM_NAME_EXCLUDE_META_DOMAIN_CLASSES = "excludeMetaDomainClasses";

    private String name;
    private String value;

    public SchemaParameter() {}

    public SchemaParameter(String name, String value) {
        this.name = name;
        this.value = value;
    }
}

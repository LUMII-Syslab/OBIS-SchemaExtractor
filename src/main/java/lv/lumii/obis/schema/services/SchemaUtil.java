package lv.lumii.obis.schema.services;

public class SchemaUtil {

    private SchemaUtil() {}

    public static boolean isEmpty(Object str) {
        return str == null || "".equals(str);
    }

}

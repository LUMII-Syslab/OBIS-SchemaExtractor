package lv.lumii.obis.schema.services;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static lv.lumii.obis.schema.constants.SchemaConstants.RDFS_NAMESPACE;
import static lv.lumii.obis.schema.constants.SchemaConstants.RDF_NAMESPACE;
import static lv.lumii.obis.schema.constants.SchemaConstants.XSD_NAMESPACE;
import static lv.lumii.obis.schema.constants.SchemaConstants.DATA_TYPE_LITERAL;

@Slf4j
public class SchemaUtil {

    private SchemaUtil() {
    }

    public static boolean isEmpty(Object str) {
        return str == null || "".equals(str);
    }

    public static String parseDataType(String dataType) {
        String resultDataType = dataType;

        int lastIndex = dataType.lastIndexOf("#");
        if (lastIndex == -1) {
            lastIndex = dataType.lastIndexOf("/");
        }
        if (lastIndex != -1 && lastIndex < dataType.length()) {
            resultDataType = dataType.substring(lastIndex + 1);
        }

        if (!resultDataType.startsWith("xsd") && dataType.startsWith(XSD_NAMESPACE)) {
            resultDataType = "xsd:" + resultDataType;
        } else if (!resultDataType.startsWith("rdf") && dataType.startsWith(RDF_NAMESPACE)) {
            resultDataType = "rdf:" + resultDataType;
        } else if (!resultDataType.startsWith("rdfs") && dataType.startsWith(RDFS_NAMESPACE)) {
            resultDataType = "rdfs:" + resultDataType;
        } else if (resultDataType.equalsIgnoreCase(DATA_TYPE_LITERAL)) {
            resultDataType = "rdfs:" + resultDataType;
        } else {
            resultDataType = "xsd:" + resultDataType;
        }

        return resultDataType;
    }

    @Nonnull
    public static Long getLongValueFromString(@Nullable String longString) {
        Long longValue = 0L;
        if(longString != null){
            try {
                longValue = Long.valueOf(longString);
            } catch (NumberFormatException e){
                log.error("Cannot parse string to long " + longString, e);
            }
        }
        return longValue;
    }

}

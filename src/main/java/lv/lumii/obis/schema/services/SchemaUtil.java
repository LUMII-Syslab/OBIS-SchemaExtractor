package lv.lumii.obis.schema.services;

import lombok.extern.slf4j.Slf4j;
import lv.lumii.obis.schema.model.v1.SchemaElement;

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

    public static void setLocalNameAndNamespace(@Nonnull String fullName, @Nonnull SchemaElement entity){
        String localName = fullName;
        String namespace = "";

        int localNameIndex = fullName.lastIndexOf("#");
        if(localNameIndex == -1){
            localNameIndex = fullName.lastIndexOf("/");
        }
        if(localNameIndex != -1 && localNameIndex < fullName.length()){
            localName = fullName.substring(localNameIndex + 1);
            namespace = fullName.substring(0, localNameIndex + 1);
        }

        entity.setLocalName(localName);
        entity.setFullName(fullName);
        entity.setNamespace(namespace);
    }

    @Nullable
    public static String addQuotesToString(@Nullable String str) {
        return (str == null) ? null : "\"" + str + "\"";
    }

}

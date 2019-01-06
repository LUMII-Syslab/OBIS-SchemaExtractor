package lv.lumii.obis.schema.services;

import static lv.lumii.obis.schema.constants.SchemaConstants.RDFS_NAMESPACE;
import static lv.lumii.obis.schema.constants.SchemaConstants.RDF_NAMESPACE;
import static lv.lumii.obis.schema.constants.SchemaConstants.XSD_NAMESPACE;
import static lv.lumii.obis.schema.constants.SchemaConstants.DATA_TYPE_LITERAL;

public class SchemaUtil {

    private SchemaUtil() {}

    public static boolean isEmpty(Object str) {
        return str == null || "".equals(str);
    }

    public static String parseDataType(String dataType){
        String resultDataType = dataType;

        int lastIndex = dataType.lastIndexOf("#");
        if(lastIndex == -1){
            lastIndex = dataType.lastIndexOf("/");
        }
        if(lastIndex != -1 && lastIndex < dataType.length()){
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

}

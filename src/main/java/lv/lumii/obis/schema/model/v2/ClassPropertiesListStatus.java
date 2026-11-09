package lv.lumii.obis.schema.model.v2;

/**
 * Status of the class level property list retrieval (outgoing or incoming properties of the class).
 * The value null means that the class level property list has not been requested yet.
 */
public enum ClassPropertiesListStatus {
    /** The full property list was retrieved (all properties of the class are known). */
    FULL,
    /** Only a partial property list was retrieved (based on the limited sample of class instances). */
    PARTIAL,
    /** The property list could not be retrieved (all queries failed or no applicable limit was found). */
    FAILED
}

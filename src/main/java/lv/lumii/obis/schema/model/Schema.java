package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lv.lumii.obis.schema.constants.SchemaConstants;

public class Schema extends AnnotationElement {
	
	@JsonProperty("SchemaName")
	private String name;

	@JsonProperty("OntologyMode")
	private String ontologyMode = "false";
	
	@JsonProperty("Classes")
	private List<SchemaClass> classes;
	
	@JsonProperty("Attributes")
	private List<SchemaAttribute> attributes;
	
	@JsonProperty("Associations")
	private List<SchemaRole> associations;

	@JsonProperty("namespace")
	private String defaultNamespace;

	@JsonIgnore
	private boolean multipleNamespaces;
	
	@JsonIgnore
	private Map<String, String> namespaceMap;

	@JsonIgnore
	private Map<String, String> usedNamespacePrefixMap;

	@JsonIgnore
	private Map<String, Integer> resourceNames;

	public List<SchemaClass> getClasses() {
		if(classes == null){
			classes = new ArrayList<>();
		}
		return classes;
	}

	public List<SchemaAttribute> getAttributes() {
		if(attributes == null){
			attributes = new ArrayList<>();
		}
		return attributes;
	}

	public List<SchemaRole> getAssociations() {
		if(associations == null){
			associations = new ArrayList<>();
		}
		return associations;
	}

	public Map<String, String> getNamespaceMap() {
		if(namespaceMap == null){
			namespaceMap = new HashMap<>();
		}
		return namespaceMap;
	}

	public Map<String, String> getUsedNamespacePrefixMap() {
		if(usedNamespacePrefixMap == null){
			usedNamespacePrefixMap = new HashMap<>();
		}
		return usedNamespacePrefixMap;
	}

	public Map<String, Integer> getResourceNames() {
		if(resourceNames == null){
			resourceNames = new HashMap<>();
		}
		return resourceNames;
	}

	public void addUsedNamespace(String namespace){
		if(namespace == null){
			return;
		}
		if(!getUsedNamespacePrefixMap().containsKey(namespace)){
			Map.Entry<String,String> namespacePrefixEntry = getNamespaceMap().entrySet().stream()
					.filter(e -> e.getValue().equalsIgnoreCase(namespace) && !e.getKey().equalsIgnoreCase(SchemaConstants.DEFAULT_NAMESPACE_PREFIX))
					.findFirst().orElse(null);
			String namespacePrefix = SchemaConstants.DEFAULT_NAMESPACE_PREFIX;
			if(namespacePrefixEntry != null){
				namespacePrefix = namespacePrefixEntry.getKey();
			}
			getUsedNamespacePrefixMap().put(namespace, namespacePrefix);
		}
	}

	public String getUniqueLocalName(SchemaEntity element) {
		if(isMultipleNamespaces()){
			if(getResourceNames().containsKey(element.getLocalName())){
				Integer count = getResourceNames().get(element.getLocalName());
				if(count > 1){
					String namespacePrefix = getUsedNamespacePrefixMap().get(element.getNamespace());
					if(namespacePrefix == null){
						namespacePrefix = SchemaConstants.DEFAULT_NAMESPACE_PREFIX;
					}
					return namespacePrefix + element.getLocalName();
				}
			}
		}
		return element.getLocalName();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getOntologyMode() {
		return ontologyMode;
	}

	public void setOntologyMode(String ontologyMode) {
		this.ontologyMode = ontologyMode;
	}

	public void setClasses(List<SchemaClass> classes) {
		this.classes = classes;
	}

	public void setAttributes(List<SchemaAttribute> attributes) {
		this.attributes = attributes;
	}

	public void setAssociations(List<SchemaRole> associations) {
		this.associations = associations;
	}

	public String getDefaultNamespace() {
		return defaultNamespace;
	}

	public void setDefaultNamespace(String defaultNamespace) {
		this.defaultNamespace = defaultNamespace;
	}

	public boolean isMultipleNamespaces() {
		return multipleNamespaces;
	}

	public void setMultipleNamespaces(boolean multipleNamespaces) {
		this.multipleNamespaces = multipleNamespaces;
	}

	public void setNamespaceMap(Map<String, String> namespaceMap) {
		this.namespaceMap = namespaceMap;
	}

	public void setUsedNamespacePrefixMap(Map<String, String> usedNamespacePrefixMap) {
		this.usedNamespacePrefixMap = usedNamespacePrefixMap;
	}

	public void setResourceNames(Map<String, Integer> resourceNames) {
		this.resourceNames = resourceNames;
	}
}

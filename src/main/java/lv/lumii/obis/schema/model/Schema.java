package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.constants.SchemaConstants;

@Setter @Getter
public class Schema extends AnnotationElement {
	
	@JsonProperty("SchemaName")
	private String name;

	@JsonProperty("OntologyMode")
	private String ontologyMode = Boolean.FALSE.toString();
	
	@JsonProperty("Classes")
	private List<SchemaClass> classes;
	
	@JsonProperty("Attributes")
	private List<SchemaAttribute> attributes;
	
	@JsonProperty("Associations")
	private List<SchemaRole> associations;

	@JsonProperty("Parameters")
	private List<SchemaParameter> parameters;

	@JsonProperty("namespace")
	private String defaultNamespace;

	@JsonProperty("Prefixes")
	private List<NamespacePrefixEntry> prefixes;

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

	public List<SchemaParameter> getParameters() {
		if(parameters == null){
			parameters = new ArrayList<>();
		}
		return parameters;
	}

	public List<NamespacePrefixEntry> getPrefixes() {
		if(prefixes == null){
			prefixes = new ArrayList<>();
		}
		return prefixes;
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

}

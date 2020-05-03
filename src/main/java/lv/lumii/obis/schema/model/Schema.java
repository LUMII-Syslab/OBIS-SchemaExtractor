package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Setter @Getter
public class Schema {
	
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

}

package lv.lumii.obis.schema.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lv.lumii.obis.schema.constants.SchemaConstants;

@Setter @Getter
public class SchemaRole extends SchemaProperty {
	
	@JsonProperty("ClassPairs")
	private List<ClassPair> classPairs;

	@JsonIgnore
	private SchemaRole inverseProperty;

	public List<ClassPair> getClassPairs() {
		if(classPairs == null){
			classPairs = new ArrayList<>();
		}
		return classPairs;
	}

}

package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.reader.dto.SchemaProcessingData;
import org.apache.commons.lang3.StringUtils;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OWLPrefixesProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData) {
        // find main namespace
        String mainNamespace = findMainNamespace(resultSchema);
        if (StringUtils.isNotEmpty(mainNamespace)) {
            resultSchema.setDefaultNamespace(mainNamespace);
        }
        // create prefix map
        OWLDocumentFormat format = inputOntology.getOWLOntologyManager().getOntologyFormat(inputOntology);
        if (format != null && Boolean.TRUE.equals(format.isPrefixOWLDocumentFormat())) {
            format.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap().forEach((key, value) -> {
                resultSchema.getPrefixes().add(new NamespacePrefixEntry(key, value));
            });
        }
    }

    @Nullable
    private String findMainNamespace(@Nonnull Schema schema) {
        List<String> namespaces = new ArrayList<>();
        for (SchemaClass item : schema.getClasses()) {
            namespaces.add(item.getNamespace());
        }
        for (SchemaAttribute item : schema.getAttributes()) {
            namespaces.add(item.getNamespace());
        }
        for (SchemaRole item : schema.getAssociations()) {
            namespaces.add(item.getNamespace());
        }
        if (namespaces.isEmpty()) {
            return null;
        }
        Map<String, Long> namespacesWithCounts = namespaces.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        return namespacesWithCounts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    }

}

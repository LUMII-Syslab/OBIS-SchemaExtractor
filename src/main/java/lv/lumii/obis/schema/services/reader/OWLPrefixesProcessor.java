package lv.lumii.obis.schema.services.reader;

import lv.lumii.obis.schema.model.*;
import lv.lumii.obis.schema.services.reader.dto.OWLOntologyReaderRequest;
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
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OWLPrefixesProcessor implements OWLElementProcessor {

    @Override
    public void process(@Nonnull OWLOntology inputOntology, @Nonnull Schema resultSchema, @Nonnull SchemaProcessingData processingData,
                        @Nonnull OWLOntologyReaderRequest readerRequest) {
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

        schema.getClasses().forEach(item -> namespaces.add(item.getNamespace()));
        schema.getAttributes().forEach(item -> namespaces.add(item.getNamespace()));
        schema.getAssociations().forEach(item -> namespaces.add(item.getNamespace()));

        Map<String, Long> namespacesWithCounts = namespaces.stream()
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));
        Optional<Map.Entry<String, Long>> entryWithMaxCount = namespacesWithCounts.entrySet().stream().max(Map.Entry.comparingByValue());
        return entryWithMaxCount.map(Map.Entry::getKey).orElse(null);
    }

}

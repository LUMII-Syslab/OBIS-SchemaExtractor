package lv.lumii.obis.schema.services;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Service
public class CsvFileReaderUtil {

    @Nonnull
    public List<String[]> readAllCsvDataWithoutHeader(@Nullable InputStream inputStream) {
        List<String[]> resultData = Collections.emptyList();
        if (inputStream == null) {
            return resultData;
        }
        CSVReader csvReader = null;
        try {
            csvReader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                    .withSkipLines(1)
                    .build();
            resultData = csvReader.readAll();
        } catch (IOException | CsvException e) {
            e.printStackTrace();
        } finally {
            if (csvReader != null) {
                try {
                    csvReader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return resultData;
    }

}

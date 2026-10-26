package edu.timetable.service;

import edu.timetable.model.Dataset;
import edu.timetable.solver.TimetableEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExporterTest {

    @Test
    void escapesSpecialCharacters() {
        assertEquals("plain", CsvExporter.escape("plain"));
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""));
        assertEquals("", CsvExporter.escape(null));
    }

    @Test
    void writesOneRowPerMeetingAndAGridPerSubject(@TempDir Path dir) throws Exception {
        Dataset data = SampleData.department();
        TimetableEngine.Result r = TimetableEngine.run(data, TimetableEngine.Options.defaults(), null, null);

        Path flat = dir.resolve("all.csv");
        CsvExporter.writeEntries(flat, data, r.entries());
        List<String> lines = Files.readAllLines(flat);
        assertEquals("Batch,Day,Period,Course,Title,Faculty,Room,Building,Type,Shared with", lines.get(0));
        // One row per meeting and attending batch: combined classes appear under each batch.
        int expected = data.courses().stream().mapToInt(c -> c.meetingsPerWeek() * c.batchIds().size()).sum();
        assertEquals(1 + expected, lines.size());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("CSE-1A,") && l.contains("HS102") && l.endsWith(",CSE-1B")));

        TimetableGrid.Subject batch = TimetableGrid.subjects(data, TimetableGrid.Perspective.BATCH).get(0);
        TimetableGrid grid = TimetableGrid.build(data, r.entries(), TimetableGrid.Perspective.BATCH, batch);
        Path view = dir.resolve("view.csv");
        CsvExporter.writeGrid(view, grid);
        List<String> rows = Files.readAllLines(view);
        assertEquals(1 + data.config().days(), rows.size());
        assertTrue(rows.get(0).startsWith("Batch: " + batch.name() + ",P1,"));
        assertEquals(23, grid.occupiedCells()); // CSE-1A: 21 own meetings + 2 of the combined HS102
    }
}

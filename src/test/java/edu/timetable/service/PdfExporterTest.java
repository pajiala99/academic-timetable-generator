package edu.timetable.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExporterTest {

    @Test
    void writesOneLandscapePagePerImage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("timetables.pdf");
        PdfExporter.write(file, List.of(new BufferedImage(2300, 1100, BufferedImage.TYPE_INT_RGB),
                new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)));
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            assertTrue(doc.getPage(0).getMediaBox().getWidth() > doc.getPage(0).getMediaBox().getHeight(), "landscape");
        }
    }
}

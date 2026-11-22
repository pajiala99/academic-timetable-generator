package edu.timetable.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes rendered timetable pages to a PDF, one A4 landscape page per image. Works without any
 * printer installed, unlike the operating system's print-to-PDF.
 */
public final class PdfExporter {
    private static final float MARGIN = 24;

    private PdfExporter() {
    }

    public static void write(Path file, List<BufferedImage> pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDRectangle a4Landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            for (BufferedImage image : pages) {
                PDPage page = new PDPage(a4Landscape);
                doc.addPage(page);
                PDImageXObject pdfImage = LosslessFactory.createFromImage(doc, image);
                float maxW = a4Landscape.getWidth() - 2 * MARGIN, maxH = a4Landscape.getHeight() - 2 * MARGIN;
                float scale = Math.min(maxW / image.getWidth(), maxH / image.getHeight());
                float w = image.getWidth() * scale, h = image.getHeight() * scale;
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    // PDF coordinates start at the bottom left: keep the page top-aligned.
                    content.drawImage(pdfImage, MARGIN, a4Landscape.getHeight() - MARGIN - h, w, h);
                }
            }
            doc.save(file.toFile());
        }
    }
}

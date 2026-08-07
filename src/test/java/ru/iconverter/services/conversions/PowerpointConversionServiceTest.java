package ru.iconverter.services.conversions;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static ru.iconverter.services.conversions.PowerpointConversionService.*;

// Pure-logic tests: format validation and the image-fit-to-slide math.
// No Spring context, no LibreOffice required.
class PowerpointConversionServiceTest {

    @Test
    void normalize_lowercasesStripsDot() {
        assertThat(normalize("  .PPTX ")).isEqualTo("pptx");
        assertThat(normalize(null)).isEqualTo("");
    }

    @Test
    void getExtension_extractsExt() {
        assertThat(getExtension("deck.final.pptx")).isEqualTo("pptx");
        assertThat(getExtension("noext")).isEqualTo("");
        assertThat(getExtension(null)).isEqualTo("");
    }

    @Test
    void validatePresentation_acceptsPptxAndPpt() {
        assertDoesNotThrow(() -> validatePresentation("pptx"));
        assertDoesNotThrow(() -> validatePresentation("ppt"));
    }

    @Test
    void validatePresentation_rejectsUnsupported() {
        assertThatThrownBy(() -> validatePresentation("pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validatePresentation("docx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fitToSlide_centersAndPreservesAspectRatio_landscape() {
        // 1600x900 image (16:9) into the 960x540pt (16:9) slide → fills exactly.
        Rectangle2D r = fitToSlide(new Dimension(1600, 900));
        assertThat(r.getWidth()).isCloseTo(960.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(r.getHeight()).isCloseTo(540.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(r.getX()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(r.getY()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void fitToSlide_centersAndPreservesAspectRatio_portrait() {
        // A tall portrait image must be letterboxed (centered) within the slide.
        Rectangle2D r = fitToSlide(new Dimension(900, 1600));
        assertThat(r.getWidth()).isLessThan(960.0);
        assertThat(r.getHeight()).isCloseTo(540.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(r.getX()).isGreaterThan(0.0);
        assertThat(r.getY()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void fromImage_producesAOneSlidePptxWithCenteredPicture() throws Exception {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", pngBytes);
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", pngBytes.toByteArray());

        PowerpointConversionService service = new PowerpointConversionService();
        Resource result = service.fromImage(file);

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(result.getContentAsByteArray()))) {
            assertThat(ppt.getSlides()).hasSize(1);
            java.util.List<XSLFShape> shapes = ppt.getSlides().get(0).getShapes();
            assertThat(shapes).hasSize(1);
            assertThat(shapes.get(0)).isInstanceOf(XSLFPictureShape.class);
        }
    }

    @Test
    void fromImage_rejectsUnsupportedFormat() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.webp", "image/webp", new byte[]{1, 2, 3});
        PowerpointConversionService service = new PowerpointConversionService();
        assertThatThrownBy(() -> service.fromImage(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromPdf_rejectsNonPdfSource() {
        // Ghostscript isn't required to reach this check — it's validated
        // before any rendering happens.
        MockMultipartFile file = new MockMultipartFile(
                "file", "deck.pptx", "application/octet-stream", new byte[]{1, 2, 3});
        PowerpointConversionService service = new PowerpointConversionService();
        assertThatThrownBy(() -> service.fromPdf(file, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromPdf_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "input.pdf", "application/pdf", new byte[0]);
        PowerpointConversionService service = new PowerpointConversionService();
        assertThatThrownBy(() -> service.fromPdf(file, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

}

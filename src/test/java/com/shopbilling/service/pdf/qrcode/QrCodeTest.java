package com.shopbilling.service.pdf.qrcode;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class QrCodeTest {

    @Test
    void testGenerateQrCode() {
        String upi = "upi://pay?pa=shop@upi&pn=My%20Shop&am=1250.00&cu=INR&tn=Invoice%20101";
        QrCode qr = QrCode.encodeText(upi, QrCode.Ecc.MEDIUM);
        assertNotNull(qr);
        assertTrue(qr.size >= 21);

        BufferedImage image = qr.toImage(4, 2);
        assertNotNull(image);
        assertEquals(image.getWidth(), image.getHeight());
        assertTrue(image.getWidth() > 0);
    }
}

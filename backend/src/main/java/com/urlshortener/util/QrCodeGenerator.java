package com.urlshortener.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Utility for generating QR codes.
 * 
 * Uses ZXing library for fast, reliable QR generation.
 * Returns QR code as base64-encoded PNG for easy embedding in API responses.
 */
@Component
public class QrCodeGenerator {
    
    @Value("${app.qr-code.size:300}")
    private int qrCodeSize;
    
    /**
     * Generate a QR code for the given URL and return as base64-encoded PNG.
     */
    public String generateQrCode(String url) throws Exception {
        MultiFormatWriter writer = new MultiFormatWriter();
        BitMatrix bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, qrCodeSize, qrCodeSize);
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
        
        byte[] qrCodeImage = outputStream.toByteArray();
        String base64Image = Base64.getEncoder().encodeToString(qrCodeImage);
        
        return "data:image/png;base64," + base64Image;
    }
}

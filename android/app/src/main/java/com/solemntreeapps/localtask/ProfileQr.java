package com.solemntreeapps.localtask;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;
import org.json.JSONObject;

public final class ProfileQr {
    private ProfileQr() {}
    public static BitMatrix encode(JSONObject profile) throws Exception {
        Map<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 4);
        return new QRCodeWriter().encode(ProfileTransfer.encode(profile), BarcodeFormat.QR_CODE, 768, 768, hints);
    }
}

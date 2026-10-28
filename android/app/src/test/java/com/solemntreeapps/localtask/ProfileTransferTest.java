package com.solemntreeapps.localtask;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileTransferTest {
    private JSONObject sample() throws Exception {
        return new JSONObject().put("id", "sender-id").put("name", "Brad’s Mac 🌳")
            .put("host", "192.168.1.20").put("port", "8765")
            .put("token", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .put("fingerprint", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }
    private void rejected(String value) {
        assertThrows(Exception.class, () -> ProfileTransfer.decode(value));
    }
    @Test public void actualQrImageRoundTripPreservesCredentialsAndUnicode() throws Exception {
        JSONObject original=sample();BitMatrix matrix=ProfileQr.encode(original);int n=matrix.getWidth();
        int[] pixels=new int[n*n];
        for(int y=0;y<n;y++)for(int x=0;x<n;x++)pixels[y*n+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        BinaryBitmap image=new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(n,n,pixels)));
        JSONObject restored=ProfileTransfer.decode(new QRCodeReader().decode(image).getText());
        for(String key:new String[]{"name","host","port","token","fingerprint"})assertEquals(original.getString(key),restored.getString(key));
        assertNotEquals("sender-id",restored.getString("id"));
    }
    @Test public void newIdsAreLocalAndUnknownFieldsAreDiscarded() throws Exception {
        JSONObject root=new JSONObject(ProfileTransfer.encode(sample()));
        root.getJSONObject("computer").put("id","malicious-id").put("command","kill");
        JSONObject a=ProfileTransfer.decode(root.toString()),b=ProfileTransfer.decode(root.toString());
        assertNotEquals(a.getString("id"),b.getString("id"));assertFalse(a.has("command"));
        assertFalse(ProfileTransfer.encode(sample()).contains("sender-id"));
    }
    @Test public void rejectsOtherCodesAndVersions() throws Exception {
        rejected("https://example.com/?token=secret");rejected("not-json");rejected(null);
        JSONObject root=new JSONObject(ProfileTransfer.encode(sample()));
        root.put("version",2);rejected(root.toString());root.put("version","1");rejected(root.toString());
        root.put("version",1).put("type","another-app");rejected(root.toString());
    }
    @Test public void rejectsOversizedInput() {rejected(new String(new char[2049]).replace('\0','x'));}
    @Test public void rejectsPublicOrInvalidAddresses() throws Exception {
        for(String host:new String[]{"8.8.8.8","127.0.0.1","192.168.1.999","localhost","192.168.1.1/path","172.32.0.1"}){
            JSONObject p=sample().put("host",host);
            assertThrows(Exception.class,()->ProfileTransfer.encode(p));
        }
    }
    @Test public void rejectsBadTokensFingerprintsAndPorts() throws Exception {
        for(String port:new String[]{"0","65536","-1","443/path"}){
            JSONObject p=sample().put("port",port);assertThrows(Exception.class,()->ProfileTransfer.encode(p));
        }
        assertThrows(Exception.class,()->ProfileTransfer.encode(sample().put("token","short")));
        assertThrows(Exception.class,()->ProfileTransfer.encode(sample().put("fingerprint","bad")));
    }
    @Test public void rejectsMissingAndWrongTypeFields() throws Exception {
        JSONObject root=new JSONObject(ProfileTransfer.encode(sample()));root.getJSONObject("computer").remove("token");rejected(root.toString());
        root=new JSONObject(ProfileTransfer.encode(sample()));root.getJSONObject("computer").put("port",8765);rejected(root.toString());
    }
    @Test public void normalizesAddressAndFingerprint() throws Exception {
        JSONObject p=sample().put("host","192.168.001.020").put("port","08765")
            .put("fingerprint", sample().getString("fingerprint").toUpperCase(java.util.Locale.ROOT));
        JSONObject restored=ProfileTransfer.decode(ProfileTransfer.encode(p));
        assertEquals("192.168.1.20",restored.getString("host"));assertEquals("8765",restored.getString("port"));
        assertEquals(sample().getString("fingerprint"),restored.getString("fingerprint"));
    }
    @Test public void limitsNames() throws Exception {
        for(String name:new String[]{"","a\nb",new String(new char[65]).replace('\0','a')})
            assertThrows(Exception.class,()->ProfileTransfer.encode(sample().put("name",name)));
    }
}

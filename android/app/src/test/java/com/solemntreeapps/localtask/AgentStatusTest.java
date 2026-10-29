package com.solemntreeapps.localtask;

import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;

public class AgentStatusTest {
    private static final Queue<FakeConnection> replies=new ArrayDeque<>();
    private static final List<String> paths=new ArrayList<>();
    @BeforeClass public static void transport() {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) {
                paths.add(url.getPath());
                FakeConnection reply=replies.remove(); reply.requested=url; return reply;
            }
        }:null);
    }
    @Before public void reset(){replies.clear();paths.clear();}
    private AgentClient client() throws Exception {
        return new AgentClient("192.168.1.20","8765",repeat('x',43),repeat('a',64));
    }
    private static String repeat(char c,int n){char[] chars=new char[n];Arrays.fill(chars,c);return new String(chars);}
    private FakeConnection reply(int code,String body) throws Exception {
        FakeConnection c=new FakeConnection(code,body);replies.add(c);return c;
    }
    @Test public void lightweightCheckUsesAuthenticatedPinnedTransport() throws Exception {
        FakeConnection c=reply(200,"{\"status\":\"ok\"}");client().checkOnline();
        assertEquals(Arrays.asList("/v1/status"),paths);
        assertEquals("Bearer "+repeat('x',43),c.getRequestProperty("Authorization"));
        assertEquals(3000,c.getConnectTimeout());assertEquals(3000,c.getReadTimeout());
        assertFalse(c.getInstanceFollowRedirects());assertTrue(c.disconnected);assertTrue(c.pinned);
    }
    @Test public void olderAgentFallsBackOnlyOnNotFound() throws Exception {
        reply(404,"{}");reply(200,"{\"processes\":[]}");client().checkOnline();
        assertEquals(Arrays.asList("/v1/status","/v1/processes"),paths);
    }
    @Test public void badTokenDoesNotFallBack() throws Exception {
        reply(401,"{}");
        try{client().checkOnline();fail();}catch(AgentClient.AgentException e){assertEquals(401,e.status);}
        assertEquals(1,paths.size());
    }
    @Test public void unexpectedSuccessBodyIsNotOnline() throws Exception {
        reply(200,"{}");
        try{client().checkOnline();fail();}catch(IOException expected){}
    }
    private static final class FakeConnection extends HttpsURLConnection {
        final int code;final byte[] body;boolean disconnected,pinned;URL requested;
        FakeConnection(int code,String body)throws Exception{super(new URL("https://192.168.1.20"));this.code=code;this.body=body.getBytes("UTF-8");}
        @Override public void setSSLSocketFactory(SSLSocketFactory factory){super.setSSLSocketFactory(factory);pinned=true;}
        @Override public int getResponseCode(){return code;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(body);}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public String getCipherSuite(){return "test";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return null;}
    }
}

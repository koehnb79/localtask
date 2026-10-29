package com.solemntreeapps.localtask;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionStatusTest {
    @Test public void authenticationFailuresAreNotOffline() {
        for(int code:new int[]{401,403})
            assertTrue(ConnectionStatus.message(new AgentClient.AgentException(code,"secret detail")).contains("Access denied"));
    }
    @Test public void certificateFailuresAreNotOffline() {
        assertTrue(ConnectionStatus.message(new javax.net.ssl.SSLHandshakeException("bad pin")).contains("Secure connection failed"));
    }
    @Test public void networkFailuresAreUnreachableNotPoweredOff() {
        assertEquals("○ Unreachable",ConnectionStatus.message(new java.net.SocketTimeoutException()));
        assertEquals("○ Unreachable",ConnectionStatus.message(new java.net.ConnectException()));
    }
    @Test public void serverFailureAndInvalidProfileRemainDistinct() {
        assertTrue(ConnectionStatus.message(new AgentClient.AgentException(503,"private detail")).contains("HTTP 503"));
        assertTrue(ConnectionStatus.message(new IllegalArgumentException("secret token")).contains("Check saved connection"));
        assertFalse(ConnectionStatus.message(new IllegalArgumentException("secret token")).contains("secret token"));
    }
}

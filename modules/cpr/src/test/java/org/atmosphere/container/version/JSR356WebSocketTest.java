/*
 * Copyright 2018 Jason Burgess
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.container.version;

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterConfig;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.atmosphere.websocket.WebSocket;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import org.atmosphere.cpr.AtmosphereFramework;

public class JSR356WebSocketTest {

    private JSR356WebSocket webSocket;
    private Session session;
    private RemoteEndpoint.Async asyncRemoteEndpoint;
    private BroadcasterCache broadcasterCache;
    private final List<Object> overrideWrites = new ArrayList<>();

    @BeforeMethod
    public void setUp() throws Exception {
        overrideWrites.clear();
        session = mock(Session.class);
        asyncRemoteEndpoint = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(asyncRemoteEndpoint);
        webSocket = new JSR356WebSocket(session, new AtmosphereFramework().getAtmosphereConfig()) {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public WebSocket write(String s) throws IOException {
                overrideWrites.add(s);
                return super.write(s);
            }

            @Override
            public WebSocket write(byte[] data, int offset, int length) throws IOException {
                overrideWrites.add(Arrays.copyOfRange(data, offset, offset + length));
                return super.write(data, offset, length);
            }
        };
    }

    @Test(timeOut = 1000)
    public void test_semaphore_is_released_in_case_of_successful_write() throws Exception {
        mockWriteResult(new SendResult());

        webSocket.write("Hello");
        webSocket.write("Hello");

        verify(asyncRemoteEndpoint, times(2)).sendText(eq("Hello"), any(SendHandler.class));
    }

    @Test(timeOut = 1000)
    public void test_semaphore_is_released_in_case_of_failing_write() throws Exception {
        mockWriteResult(new SendResult(new RuntimeException("Fails")));

        webSocket.write("Hello");
        webSocket.write("Hello");

        verify(asyncRemoteEndpoint, times(2)).sendText(eq("Hello"), any(SendHandler.class));
    }

    @Test(timeOut = 1000)
    public void test_semaphore_is_released_in_case_of_NPE_in_getAsyncRemote() throws Exception {
        when(session.getAsyncRemote()).thenThrow(new NullPointerException()).thenReturn(asyncRemoteEndpoint);
        webSocket.write("Hello1");
        webSocket.write("Hello2");

        verify(asyncRemoteEndpoint).sendText(eq("Hello2"), any(SendHandler.class));
    }

    @Test(timeOut = 1000, expectedExceptions = RuntimeException.class)
    public void test_semaphore_is_released_in_case_of_ERROR_in_getAsyncRemote() throws Exception {
        when(session.getAsyncRemote()).thenThrow(new Error("Unexpected error")).thenReturn(asyncRemoteEndpoint);
        webSocket.write("Hello1");
        webSocket.write("Hello2");

        verify(asyncRemoteEndpoint).sendText(eq("Hello2"), any(SendHandler.class));
    }

    @Test(timeOut = 1000, expectedExceptions = RuntimeException.class)
    public void test_semaphore_is_released_in_case_of_RuntimeException_in_getAsyncRemote() throws Exception {
        when(session.getAsyncRemote()).thenThrow(new IllegalArgumentException("Invalid argument")).thenReturn(asyncRemoteEndpoint);
        webSocket.write("Hello1");
        webSocket.write("Hello2");

        verify(asyncRemoteEndpoint).sendText(eq("Hello2"), any(SendHandler.class));
    }

    @Test(timeOut = 1000)
    public void failed_text_write_caches_message_before_interceptors() throws Exception {
        AtmosphereResponse response = prepareLengthPrefixingWebSocket();
        mockWriteResult(new SendResult(new RuntimeException("Fails")));

        webSocket.write(response, "Hello");
        webSocket.write(response, "xHelloy".getBytes(StandardCharsets.UTF_8), 1, 5);

        verify(asyncRemoteEndpoint, times(2)).sendText(eq("5|Hello"), any(SendHandler.class));
        assertEquals(overrideWrites, Arrays.asList("5|Hello", "5|Hello"));
        assertEquals(cachedMessages(2), Arrays.asList("Hello", "Hello"));
    }

    @DataProvider
    public Object[][] binaryWrites() {
        return new Object[][]{{false}, {true}};
    }

    @Test(timeOut = 1000, dataProvider = "binaryWrites")
    public void failed_binary_write_caches_message_before_interceptors(boolean writeString) throws Exception {
        AtmosphereResponse response = prepareLengthPrefixingWebSocket();
        webSocket.binaryWrite(true);
        mockWriteResult(new SendResult(new RuntimeException("Fails")));

        if (writeString) {
            webSocket.write(response, "Hello");
        } else {
            webSocket.write(response, "xHelloy".getBytes(StandardCharsets.UTF_8), 1, 5);
        }

        byte[] framed = "5|Hello".getBytes(StandardCharsets.UTF_8);
        verify(asyncRemoteEndpoint).sendBinary(eq(ByteBuffer.wrap(framed)), any(SendHandler.class));
        assertEquals((byte[]) overrideWrites.get(0), framed);
        assertEquals((byte[]) cachedMessages(1).get(0), "Hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test(timeOut = 1000)
    public void failed_direct_write_after_interceptor_write_caches_its_own_message() throws Exception {
        AtmosphereResponse response = prepareLengthPrefixingWebSocket();
        mockWriteResult(new SendResult(new RuntimeException("Fails")));

        webSocket.write(response, "Hello");
        webSocket.write("Other");

        assertEquals(cachedMessages(2), Arrays.asList("Hello", "Other"));
    }

    private AtmosphereResponse prepareLengthPrefixingWebSocket() {
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        response.setCharacterEncoding("UTF-8");

        broadcasterCache = mock(BroadcasterCache.class);
        BroadcasterConfig broadcasterConfig = mock(BroadcasterConfig.class);
        when(broadcasterConfig.getBroadcasterCache()).thenReturn(broadcasterCache);
        Broadcaster broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("broadcaster");
        when(broadcaster.getBroadcasterConfig()).thenReturn(broadcasterConfig);
        AtmosphereResource resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("uuid");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.getResponse()).thenReturn(response);
        webSocket.resource(resource);

        // Frames messages the way TrackMessageSizeInterceptor does
        webSocket.interceptor(new AsyncIOInterceptorAdapter() {
            @Override
            public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) {
                String text = new String(responseDraft, StandardCharsets.UTF_8);
                return (text.length() + "|" + text).getBytes(StandardCharsets.UTF_8);
            }
        });
        return response;
    }

    private List<Object> cachedMessages(int count) {
        ArgumentCaptor<BroadcastMessage> message = ArgumentCaptor.forClass(BroadcastMessage.class);
        verify(broadcasterCache, timeout(500).times(count)).addToCache(eq("broadcaster"), eq("uuid"), message.capture());
        List<Object> messages = new ArrayList<>();
        for (BroadcastMessage m : message.getAllValues()) {
            messages.add(m.message());
        }
        return messages;
    }

    private void mockWriteResult(final SendResult sendResult) {
        Answer<Void> answer = new Answer<Void>() {
            @Override
            public Void answer(final InvocationOnMock invocationOnMock) throws Throwable {
                new Thread() {
                    @Override
                    public void run() {
                        ((SendHandler) invocationOnMock.getArguments()[1]).onResult(sendResult);
                    }
                }.start();
                return null;
            }
        };
        doAnswer(answer).when(asyncRemoteEndpoint).sendText(anyString(), any(SendHandler.class));
        doAnswer(answer).when(asyncRemoteEndpoint).sendBinary(any(ByteBuffer.class), any(SendHandler.class));
    }

}
package org.logstash.javaHttpPlugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.server.MockServerClient;
import org.mockserver.junit.MockServerRule;
import org.mockserver.mock.action.ExpectationCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpStatusCode;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jayway.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.mockserver.model.HttpCallback.callback;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/*
Copyright [2018] [Medallia]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

public class LogProcessorTest {
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Rule
    public MockServerRule mockServerRule = new MockServerRule(this);

    @Rule
    public TemporaryFolder folder= new TemporaryFolder();

    private LogProcessor logProcessor;

    private static final ConcurrentHashMap<String, String> urlToLog = new ConcurrentHashMap<>();

    public static class TestExpectationCallback implements ExpectationCallback {

        @Override
        public HttpResponse handle(HttpRequest httpRequest) {
            urlToLog.put(httpRequest.getPath(), httpRequest.getBody().toString());
            return response()
                        .withStatusCode(HttpStatusCode.OK_200.code());
        }
    }

    private void clearMap() {
        urlToLog.clear();
    }

    private void initClient() throws IOException {
        if (!initialized.getAndSet(true)) {
            int httpPort = mockServerRule.getHttpPort();
            new MockServerClient("localhost", httpPort)
                    .when(
                            request()
                                    .withMethod("POST")
                    )
                    .callback(
                            callback()
                                    .withCallbackClass("org.logstash.javaHttpPlugin.LogProcessorTest$TestExpectationCallback")

                    );
            File createdFile = folder.newFile("name.txt");
            FileWriter fw = new FileWriter(createdFile.getAbsolutePath());
            BufferedWriter bw = new BufferedWriter(fw);
            String content = String.format("container1: http://localhost:%d/1\nregex@^itsatrap.*: http://localhost:%d/oops\nregex@^iron.*: http://localhost:%d/2\n",
                    httpPort,
                    httpPort,
                    httpPort);
            bw.write(content);
            bw.close();
            fw.close();
            logProcessor = new LogProcessor(10, 10, 10, 3, true, 1, 500, createdFile.getAbsolutePath(), 500, 1);
        }
    }

    @Test
    public void testNameMatch() throws IOException {
        try {
            initClient();
            logProcessor.sendLog("Hello World!", "container1");
            await().atMost(2, TimeUnit.SECONDS).until(() -> urlToLog.containsKey("/1"));
            assertEquals(urlToLog.get("/1"), "Hello World!");
        } finally {
            clearMap();
        }
    }

    @Test
    public void testRegexMatch() throws IOException {
        try {
            initClient();
            logProcessor.sendLog("Bye World", "ironman");
            await().atMost(2, TimeUnit.SECONDS).until(() -> urlToLog.containsKey("/2"));
            assertEquals(urlToLog.get("/2"), "Bye World");
        } finally {
            clearMap();
        }
    }

    @Test
    public void testNameMatchBatchingRegex() throws IOException {
        try {
            initClient();
            logProcessor.sendLog("Hello World!", "ironman10");
            logProcessor.sendLog("Bye World", "ironman11");
            await().atMost(2, TimeUnit.SECONDS).until(() -> urlToLog.containsKey("/2"));
            assertEquals("Hello World!\r\nBye World", urlToLog.get("/2"));
        } finally {
            clearMap();
        }
    }
}

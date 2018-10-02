package org.logstash.javaHttpPlugin;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/* Copyright [2018] [Medallia]

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

/**
 * This class sends logs over http to url endpoints
 * configured through a file.
 */
public class LogProcessor {
    private static class RegexTuple {
        private final static Pattern pattern = Pattern.compile("regex@(.*)");
        private final Pattern regexPattern;
        private final String url;
        RegexTuple(String regex, String url) {
            Matcher m = pattern.matcher(regex);
            if (!m.matches() || m.groupCount() != 1) {
                throw new IllegalArgumentException("Invalid regex" + regex);
            }
            regexPattern = Pattern.compile(m.group(1));
            this.url = url;
        }

        boolean matches(String container) {
            Matcher m = regexPattern.matcher(container);
            return m.matches();
        }
    }

    private static class LogEntry {
        private final StringBuilder builder;
        LogEntry(StringBuilder builder) {
            this.builder = builder;
        }
    }

    private static class LogTuple {
        private final String log;
        private final String url;
        LogTuple(String log, String url) {
            this.log = log;
            this.url = url;
        }
    }
    
    private final CloseableHttpClient httpClient;
    private final AtomicLong successfulLogAttempts = new AtomicLong(0);
    private final AtomicLong failedAttempts = new AtomicLong(0);
    private final static long MAX_LENGTH = 2048;//2K of bytes.
    private final PoolingHttpClientConnectionManager connectionManager;
    private final ExecutorService threadPool;
    private final ExecutorService asyncThreadPool;
    private final Map<String, LogEntry> collectorToLog = new HashMap<>();
    private boolean enableBatching;
    private final int retryCount;
    private final long logSendTimeoutMillis;
    private final AtomicBoolean isShutdown = new AtomicBoolean();
    private final LinkedBlockingQueue<LogTuple> logQueue;
    private final Thread logReaper;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);
    private AtomicReference<Map<String, String>> containerToUrl = new AtomicReference<>();
    private AtomicReference<List<RegexTuple>> listRegexes = new AtomicReference<>();
    private final ScheduledExecutorService reloadFileService = Executors.newScheduledThreadPool(1);
    private final RequestConfig config;

    public LogProcessor(int maxConnections, int connectionsPerRoute, int queueSize, int numThreads, boolean enableBatching,
                 int retryCount, long logSendTimeoutMillis, String filePath, long reloadMinutes, long flushIntervalSeconds) throws FileNotFoundException, IllegalArgumentException {
        RequestConfig.Builder configBuilder;
        configBuilder = RequestConfig.copy(RequestConfig.DEFAULT);
        configBuilder.setConnectionRequestTimeout((int)logSendTimeoutMillis);//changing this value
        configBuilder.setSocketTimeout((int)logSendTimeoutMillis);//changing this value.
        configBuilder.setConnectionRequestTimeout((int)logSendTimeoutMillis);
        config = configBuilder.build();
        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultMaxPerRoute(connectionsPerRoute);
        connectionManager.setMaxTotal(maxConnections);
        SocketConfig sc = SocketConfig.custom().setSoTimeout((int)logSendTimeoutMillis).build();
        connectionManager.setDefaultSocketConfig(sc);
        httpClient = HttpClients.custom().setConnectionManager(connectionManager)
                .build();
        this.enableBatching = enableBatching;
        this.retryCount = retryCount;
        this.threadPool = createThreadPool(queueSize, numThreads);
        this.asyncThreadPool = createThreadPool(numThreads, numThreads);
        this.logSendTimeoutMillis = logSendTimeoutMillis;
        this.logQueue = new LinkedBlockingQueue<>(queueSize);
        logReaper = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    //continuously poll the logQueue for new log messages.
                    LogTuple tuple = logQueue.poll(Long.MAX_VALUE, TimeUnit.DAYS);
                    String logMessageToFlush = null;
                    synchronized (collectorToLog) {
                        LogEntry entry = collectorToLog.get(tuple.url);
                        if (entry != null) {
                            entry.builder.append("\r\n");
                            entry.builder.append(tuple.log);
                        } else {
                            entry = new LogEntry(new StringBuilder(tuple.log));
                            collectorToLog.put(tuple.url, entry);
                        }
                        //flush the message if the log gets too large.
                        if (entry.builder.length() > MAX_LENGTH) {
                            //flush the logs and reset.
                            logMessageToFlush = entry.builder.toString();
                            collectorToLog.remove(tuple.url);
                        }
                    }
                    //if message needs to be flushed flush it.
                    if (logMessageToFlush != null) {
                        threadPool.submit(new TimedAsyncHttpCall(tuple.url, logMessageToFlush, retryCount, config));
                    }
                    //This check has to be moved out of this function into a separate thread.
                } catch (InterruptedException ex) {
                    System.err.println("Caught an interrupted exception" + ex.getMessage());
                    Thread.currentThread().interrupt();
                } catch (RejectedExecutionException ex) {
                    System.err.println("Pool full lost message");
                    failedAttempts.incrementAndGet();
                } catch (RuntimeException ex) {
                    System.err.println("Run-time Exception thrown" + ex.getMessage());
                }
            }
        });
        logReaper.setDaemon(false);
        logReaper.start();
        scheduler.scheduleAtFixedRate(() -> {
            synchronized (collectorToLog) {
                collectorToLog.forEach((url, logEntry) -> {
                    String logMessage = logEntry.builder.toString();
                    if (!logMessage.isEmpty()) {
                        try {
                            threadPool.submit(new TimedAsyncHttpCall(url, logMessage, 0, config));
                            System.err.println(connectionManager.getTotalStats().toString());
                        } catch (RejectedExecutionException ex) {
                            System.err.println("Async thread pool full message dropped");
                            failedAttempts.incrementAndGet();
                        }
                    }
                });
                collectorToLog.clear();
            }
            System.err.println("Stats:" + connectionManager.getTotalStats().toString());
            System.err.println("Stats Successful requests" + successfulLogAttempts.getAndSet(0L));
            System.err.println("Stats Unsuccessful requests" + failedAttempts.getAndSet(0L));
        }, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        loadConfigFile(filePath);
        reloadFileService.scheduleAtFixedRate(() -> {
            try {
                loadConfigFile(filePath);
            } catch (FileNotFoundException ex) {
                System.err.println("File could not be found" + ex.getMessage());
            } catch (Exception ex) {
                System.err.println("Exception encountered" + ex.getMessage());
            }
        }, reloadMinutes, reloadMinutes, TimeUnit.MINUTES);
    }

    private ExecutorService createThreadPool(int queueSize, int numThreads) {
        //create a thread pool with a single thread which does forwarding.
        BlockingQueue<Runnable> linkedBlockingDeque = new LinkedBlockingDeque<>(
                queueSize);
        return new ThreadPoolExecutor(1, numThreads, 30,
                                            TimeUnit.SECONDS, linkedBlockingDeque, r -> {
                                                Thread t = Executors.defaultThreadFactory().newThread(r);
                                                t.setDaemon(false);//this is for testing purposes only
                                                return t;
                                            },new ThreadPoolExecutor.AbortPolicy());
    }

    public void sendLog(String log, String url) {
        if (enableBatching)
            sendLogBatched(log, url);
        else
            sendLogNonBatched(log, url, retryCount);
    }

    private class TimedAsyncHttpCall implements Runnable {
        private final String url, log;
        private int retryCount;
        private final RequestConfig config;
        TimedAsyncHttpCall(String url, String log, int retryCount, RequestConfig config) {
            this.log = log;
            this.url = url;
            this.retryCount = retryCount;
            this.config = config;
        }

        @Override
        public void run() {
            boolean isRequestSuccessful = false;
            HttpPost httpPost = new HttpPost(url);
            try {
                httpPost.setConfig(config);
                Future<CloseableHttpResponse> future;
                httpPost.setEntity(new StringEntity(log));
                //We implement async by delegating to another thread and watching it.
                future = asyncThreadPool.submit(() -> {
                    CloseableHttpResponse response = null;
                    return httpClient.execute(httpPost);
                });
                CloseableHttpResponse response = future.get(logSendTimeoutMillis, TimeUnit.MILLISECONDS);
                if (response.getStatusLine().getStatusCode() == 200) {
                    isRequestSuccessful = true;
                }
                if (response.getEntity() != null && response.getEntity().getContent() != null) {
                    response.getEntity().getContent().close();
                }
                response.close();
            } catch (ExecutionException | TimeoutException ex) {
                //stop burning this thread.
                httpPost.abort();
                System.err.println("Timeout exception" + ex.getMessage());
            } catch (InterruptedException ex) {
                //stop burning this thread.
                httpPost.abort();
                isShutdown.set(true);
            } catch (IOException ex) {
                System.err.println("IOException is thrown" + ex.getMessage());
            } catch (Exception ex) {
                System.err.println(ex.getMessage());
            } finally{
                    if (!isRequestSuccessful && retryCount > 0) {
                        retryCount--;
                        run();
                    }
                    if (isRequestSuccessful) {
                        successfulLogAttempts.incrementAndGet();
                    } else {
                        failedAttempts.incrementAndGet();
                    }
            }
        }
    }

    //This will fire a separate http request for each log statement in a separate thread.
    private void sendLogNonBatched(String log, String url, int retryCount) {
        //If shutdown is in progress stop sending out logs for anything which is not already added to the logging pipeline.
        if (isShutdown.get()) {
            return;
        }

        threadPool.submit(new TimedAsyncHttpCall(url, log, retryCount, config));
    }

    private void sendLogBatched(String log, String containerName) {
        String url = lookupUrl(containerName);
        //this guy just puts stuff into a logQueue.
        if (url != null) {
            if (!logQueue.offer(new LogTuple(log, url))) {
                System.err.println("Dropped log message adding into the logQueue.");
                failedAttempts.incrementAndGet();
            }
        }
    }

    void close() {
        threadPool.shutdown();
        asyncThreadPool.shutdown();
        scheduler.shutdown();
        logReaper.interrupt();
        reloadFileService.shutdownNow();

        try {
            scheduler.awaitTermination(30, TimeUnit.SECONDS);
            scheduler.shutdownNow();
            asyncThreadPool.awaitTermination(30, TimeUnit.SECONDS);
            asyncThreadPool.shutdownNow();
        } catch (InterruptedException ex) {
            System.err.println("Un-clean shutdown");
            Thread.interrupted();
        }

        try {
            httpClient.close();
        } catch (IOException ex) {
            System.err.println("Exception thrown" + ex);
        }
    }

    public static void main(String args[]) throws FileNotFoundException {
        LogProcessor processor = new LogProcessor(25, 25, 1000, 25, true, 1, 30000, "/tmp/test", 5, 10);
        processor.sendLog("Finally seems to be working","test");
    }

    private void loadConfigFile(String filePath) throws FileNotFoundException, IllegalArgumentException {
        File file = new File(filePath);
        Yaml yaml = new Yaml();
        Map<String, String> mapContainerUrl = new HashMap<>();
        Map<String, String> keyValues = yaml.load(new FileInputStream(file));
        List<RegexTuple> list = new ArrayList<>();
        keyValues.forEach((key, value) -> {
            if (key.startsWith("regex@")) {
                list.add(new RegexTuple(key, value));
            } else {
                mapContainerUrl.put(key, value);
            }
        });
        containerToUrl.set(mapContainerUrl);
        listRegexes.set(list);
    }

    private String lookupUrl(String containerName) {
        String url = containerToUrl.get().get(containerName);
        //Then do a regex lookup.
        if (url == null) {
            return listRegexes.get().stream().filter(r -> r.matches(containerName)).findFirst().map(regex -> regex.url).orElse(null);
        } else {
            return url;
        }
    }
}

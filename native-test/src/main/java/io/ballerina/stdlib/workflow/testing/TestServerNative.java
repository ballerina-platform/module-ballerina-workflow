/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.workflow.testing;

import io.temporal.testserver.TestServer;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Standalone Temporal test server that can be started from Gradle.
 * <p>
 * This class provides a main method to start an embedded Temporal test server
 * that exposes a real gRPC endpoint. Gradle starts this server before running
 * Ballerina tests and stops it afterwards.
 * </p>
 * <p>
 * The server writes its connection details to a file that can be used to
 * configure the Ballerina test environment.
 * </p>
 *
 * @since 0.1.0
 */
public final class TestServerNative {

    private static final Logger LOGGER = Logger.getLogger(TestServerNative.class.getName());
    private static final String PID_FILE = "test-server.pid";
    private static final String TARGET_FILE = "test-server.target";
    private static final int DEFAULT_PORT = 7231;
    
    private static Closeable testServer;
    private static volatile boolean running = false;
    private static final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private TestServerNative() {
        // Private constructor to prevent instantiation
    }

    /**
     * Main entry point for starting the test server from Gradle.
     *
     * @param args command line arguments: command [workDir] [port]
     *             command: start, stop, or status
     *             workDir: directory for pid and target files (default: current dir)
     *             port: port to listen on (default: 7233)
     */
    @SuppressWarnings("java:S106") // System.exit is intentional for CLI application
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String workDir = args.length > 1 ? args[1] : ".";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;

        switch (command) {
            case "start":
                startServer(workDir, port);
                break;
            case "stop":
                stopServer(workDir);
                break;
            case "status":
                checkStatus(workDir);
                break;
            default:
                printUsage();
                System.exit(1);
        }
    }

    private static void printUsage() {
        LOGGER.info("Usage: TestServerNative <command> [workDir] [port]");
        LOGGER.info("Commands:");
        LOGGER.info("  start  - Start the test server (runs in foreground)");
        LOGGER.info("  stop   - Stop the test server");
        LOGGER.info("  status - Check server status");
        LOGGER.info("Options:");
        LOGGER.info("  workDir - Directory for PID and target files (default: current dir)");
        LOGGER.info("  port    - Port to listen on (default: 7231)");
    }

    private static void startServer(String workDir, int port) throws Exception {
        LOGGER.info("Starting embedded Temporal test server on port " + port + "...");
        
        // Create and start the test server with the specified port
        testServer = TestServer.createPortBoundServer(port);
        
        // The target is localhost:port
        String target = "localhost:" + port;
        
        // Write target file for Ballerina to read
        File targetFile = new File(workDir, TARGET_FILE);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8)) {
            writer.write(target);
        }
        LOGGER.info("Server target written to: " + targetFile.getAbsolutePath());
        
        // Write PID file
        File pidFile = new File(workDir, PID_FILE);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(pidFile), StandardCharsets.UTF_8)) {
            writer.write(String.valueOf(ProcessHandle.current().pid()));
        }
        
        running = true;
        LOGGER.info("Embedded Temporal test server started at: " + target);
        LOGGER.info("TEST_SERVER_TARGET=" + target);
        
        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook triggered");
            cleanup(workDir);
            shutdownLatch.countDown();
        }));
        
        // Keep running until stopped
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        cleanup(workDir);
    }

    private static void stopServer(String workDir) throws IOException {
        File pidFile = new File(workDir, PID_FILE);
        if (!pidFile.exists()) {
            LOGGER.info("No test server running (PID file not found)");
            return;
        }
        
        String pidStr = Files.readString(pidFile.toPath()).trim();
        long pid;
        try {
            pid = Long.parseLong(pidStr);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid PID in file: " + pidStr);
            deleteFile(pidFile);
            deleteFile(new File(workDir, TARGET_FILE));
            return;
        }
        
        LOGGER.info("Stopping test server with PID: " + pid);
        
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        
        // Clean up files
        deleteFile(pidFile);
        deleteFile(new File(workDir, TARGET_FILE));
        
        LOGGER.info("Test server stopped");
    }

    @SuppressWarnings("java:S106") // System.exit is intentional for CLI status command
    private static void checkStatus(String workDir) {
        File pidFile = new File(workDir, PID_FILE);
        File targetFile = new File(workDir, TARGET_FILE);
        
        if (!pidFile.exists()) {
            LOGGER.info("Test server is NOT running");
            System.exit(1);
            return;
        }
        
        try {
            String pidStr = Files.readString(pidFile.toPath()).trim();
            long pid;
            try {
                pid = Long.parseLong(pidStr);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid PID in file: " + pidStr);
                deleteFile(pidFile);
                deleteFile(targetFile);
                System.exit(1);
                return;
            }
            
            if (ProcessHandle.of(pid).isPresent()) {
                String target = "";
                if (targetFile.exists()) {
                    target = Files.readString(targetFile.toPath()).trim();
                }
                LOGGER.info("Test server is running with PID: " + pid);
                LOGGER.info("Target: " + target);
                System.exit(0);
            } else {
                LOGGER.info("Test server process not found (stale PID file)");
                deleteFile(pidFile);
                deleteFile(targetFile);
                System.exit(1);
            }
        } catch (IOException e) {
            LOGGER.warning("Error checking status: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void cleanup(String workDir) {
        if (testServer != null) {
            try {
                LOGGER.info("Shutting down test server...");
                testServer.close();
                LOGGER.info("Test server shut down");
            } catch (Exception e) {
                LOGGER.warning("Error shutting down test server: " + e.getMessage());
            }
            testServer = null;
        }
        
        running = false;
        
        // Clean up files
        deleteFile(new File(workDir, PID_FILE));
        deleteFile(new File(workDir, TARGET_FILE));
    }
    
    /**
     * Delete a file, logging any errors.
     * 
     * @param file the file to delete
     */
    private static void deleteFile(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            LOGGER.warning("Failed to delete file " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }
}

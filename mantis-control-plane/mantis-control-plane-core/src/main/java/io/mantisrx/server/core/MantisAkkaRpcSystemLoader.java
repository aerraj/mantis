/*
 * Copyright 2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mantisrx.server.core;

import io.mantisrx.server.worker.TaskExecutorGateway;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;
import java.util.UUID;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.core.classloading.ComponentClassLoader;
import org.apache.flink.runtime.rpc.RpcSystem;
import org.apache.flink.runtime.rpc.RpcSystemLoader;
import org.apache.flink.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RpcSystemLoader for mantis task executor and other services that need to expose an RPC API.
 * This particular implementation uses the akka RPC implementation under the hood.
 */
public class MantisAkkaRpcSystemLoader implements RpcSystemLoader {

    private static final RpcSystem INSTANCE = createRpcSystem();

    public static RpcSystem getInstance() {
        return INSTANCE;
    }

    @Override
    public RpcSystem loadRpcSystem(Configuration config) {
        return INSTANCE;
    }

    private static RpcSystem createRpcSystem() {
        try {
            final Logger LOG = LoggerFactory.getLogger(MantisAkkaRpcSystemLoader.class);
            final ClassLoader flinkClassLoader = RpcSystem.class.getClassLoader();
            LOG.info("[fdc-91] flink cl - " + flinkClassLoader);

            final Path tmpDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
            LOG.info("[fdc-91] flink cl - tmpdir: " + tmpDirectory);

            Files.createDirectories(tmpDirectory);
            final Path tempFile =
                Files.createFile(
                    tmpDirectory.resolve("flink-rpc-akka_" + UUID.randomUUID() + ".jar"));
            LOG.info("[fdc-91] flink cl - tempFile: " + tempFile);

            final InputStream resourceStream =
                flinkClassLoader.getResourceAsStream("flink-rpc-akka.jar");
            if (resourceStream == null) {
                throw new RuntimeException(
                    "Akka RPC system could not be found. If this happened while running a test in the IDE,"
                        + "run the process-resources phase on flink-rpc/flink-rpc-akka-loader via maven.");
            }

            IOUtils.copyBytes(resourceStream, Files.newOutputStream(tempFile));

            LOG.info("[fdc-91] flink cl - Tamaro: " + TaskExecutorGateway.class.getProtectionDomain().getCodeSource().getLocation().toURI().toURL());
            LOG.info("[fdc-91] flink cl - Biggy: " + tempFile.toUri().toURL());
//            final SubmoduleClassLoader submoduleClassLoader =
//                new SubmoduleClassLoader(
//                    new URL[] {tempFile.toUri().toURL(), TaskExecutorGateway.class.getProtectionDomain().getCodeSource().getLocation().toURI().toURL()}, flinkClassLoader);
            final ComponentClassLoader componentClassLoader = new ComponentClassLoader(
//                new URL[] {tempFile.toUri().toURL(), TaskExecutorGateway.class.getProtectionDomain().getCodeSource().getLocation().toURI().toURL()},
                new URL[] {tempFile.toUri().toURL()},
                flinkClassLoader,
                CoreOptions.parseParentFirstLoaderPatterns(
                    "org.slf4j;org.apache.log4j;org.apache.logging;org.apache.commons.logging;ch.qos.logback;io.mantisrx.server.worker", ""),
                new String[] {"org.apache.flink"});

            LOG.info("[fdc-91] flink cl - submoduleClassLoader: " + componentClassLoader);

            return new CleanupOnCloseRpcSystem(
                ServiceLoader.load(RpcSystem.class, componentClassLoader).iterator().next(),
                componentClassLoader,
                tempFile);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("Could not initialize RPC system.", e);
        }
    }

}

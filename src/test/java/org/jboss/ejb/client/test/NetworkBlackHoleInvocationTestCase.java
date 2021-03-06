/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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
package org.jboss.ejb.client.test;

import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.client.legacy.JBossEJBProperties;
import org.jboss.ejb.client.test.common.DummyServer;
import org.jboss.ejb.client.test.common.Echo;
import org.jboss.ejb.client.test.common.EchoBean;
import org.jboss.ejb.client.test.common.Result;
import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * @author <a href="ingo@redhat.com">Ingo Weiss</a>
 */
public class NetworkBlackHoleInvocationTestCase {
    private static final Logger logger = Logger.getLogger(NetworkBlackHoleInvocationTestCase.class);
    private static final String PROPERTIES_FILE = "broken-server-jboss-ejb-client.properties";

    private DummyServer server;
    private boolean serverStarted = false;

    // module
    private static final String APP_NAME = "my-foo-app";
    private static final String MODULE_NAME = "my-bar-module";
    private static final String DISTINCT_NAME = "";

    private static final String SERVER_NAME = "test-server";

    /**
     * Do any general setup here
     *
     * @throws Exception
     */
    @BeforeClass
    public static void beforeClass() throws Exception {
        // trigger the static init of the correct properties file - this also depends on running in forkMode=always
        JBossEJBProperties ejbProperties = JBossEJBProperties.fromClassPath(NetworkBlackHoleInvocationTestCase.class.getClassLoader(), PROPERTIES_FILE);
        JBossEJBProperties.getContextManager().setGlobalDefault(ejbProperties);
    }

    /**
     * Do any test specific setup here
     */
    @Before
    public void beforeTest() throws Exception {
        // start a server
        server = new DummyServer("localhost", 6999, SERVER_NAME);
        server.start();
        serverStarted = true;
        logger.info("Started server ...");
        serverStarted = true;

        server.register(APP_NAME, MODULE_NAME, DISTINCT_NAME, Echo.class.getSimpleName(), new EchoBean());
        logger.info("Registered module ...");
    }

    /**
     * Do any test-specific tear down here.
     */
    @After
    public void afterTest() {
        server.unregister(APP_NAME, MODULE_NAME, DISTINCT_NAME, Echo.class.getName());
        logger.info("Unregistered module ...");

        if (serverStarted) {
            try {
                this.server.stop();
            } catch (Throwable t) {
                logger.info("Could not stop server", t);
            }
        }
        logger.info("Stopped server ...");
    }

    /**
     * Test a failed client discovery
     */
    @Test
    public void testTakingDownServerDoesNotBreakClients() throws Exception {

        // broken-server-jboss-ejb-client.properties will have the ejb-client with 2 nodes on ports 6999 and 7099
        // it will succesfully invoke the ejb and then it will kill the 7099 port and try to invoke again
        // the expected behavior is that it will not wait more than org.jboss.ejb.client.discovery.additional-node-timeout once it has a connection to 6999 before invoking the ejb
        System.setProperty("org.jboss.ejb.client.discovery.timeout", "10");

        // This test will fail if org.jboss.ejb.client.discovery.additional-node-timeout is not set
        // assertInvocationTimeLessThan checks that the org.jboss.ejb.client.discovery.additional-node-timeout is effective
        // if org.jboss.ejb.client.discovery.additional-node-timeout is not effective it will timeout once it reaches the value of org.jboss.ejb.client.discovery.timeout
        System.setProperty("org.jboss.ejb.client.discovery.additional-node-timeout", "2");

        try (DummyServer server2 = new DummyServer("localhost", 7099, "test2")) {
            server2.start();
            server2.register(APP_NAME, MODULE_NAME, DISTINCT_NAME, Echo.class.getSimpleName(), new EchoBean());

            // create a proxy for invocation
            final StatelessEJBLocator<Echo> statelessEJBLocator = new StatelessEJBLocator<>
                    (Echo.class, APP_NAME, MODULE_NAME, Echo.class.getSimpleName(), DISTINCT_NAME);
            final Echo proxy = EJBClient.createProxy(statelessEJBLocator);
            Assert.assertNotNull("Received a null proxy", proxy);
            logger.info("Created proxy for Echo: " + proxy.toString());

            logger.info("Invoking on proxy...");
            // Invoke on the proxy. This should fail in 10 seconds or else it'll hang.
            final String message = "hello!";

            long invocationStart = System.currentTimeMillis(); 
            Result<String> echo = proxy.echo(message);
            assertInvocationTimeLessThan("org.jboss.ejb.client.discovery.additional-node-timeout ineffective", 3000, invocationStart);
            Assert.assertEquals(message, echo.getValue());
            server2.hardKill();

            final Echo proxy2 = EJBClient.createProxy(statelessEJBLocator);
            Assert.assertNotNull("Received a null proxy", proxy2);
            logger.info("Created proxy for Echo: " + proxy2.toString());

            //this is a network black hole
            //it emulates what happens if the server just disappears, and connect attempts hang
            //instead of being immediately rejected (e.g. a firewall dropping packets)
            try (ServerSocket s = new ServerSocket(7099, 100, InetAddress.getByName("localhost"))) {
                invocationStart = System.currentTimeMillis(); 
                echo = proxy2.echo(message);
                assertInvocationTimeLessThan("org.jboss.ejb.client.discovery.additional-node-timeout ineffective", 3000, invocationStart);
                Assert.assertEquals(message, echo.getValue());
            }
        }
    }

    private static void assertInvocationTimeLessThan(String message, long maximumInvocationTimeMs, long invocationStart) {
        long invocationTime = System.currentTimeMillis() - invocationStart;
        if(invocationTime > maximumInvocationTimeMs)
            Assert.fail(String.format("%s: invocation time: %d > maximum expected invocation time: %d", message, invocationTime, maximumInvocationTimeMs));
    }
}

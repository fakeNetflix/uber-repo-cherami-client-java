package com.uber.cherami.example;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.uber.cherami.ChecksumOption;
import com.uber.cherami.ConsumerGroupDescription;
import com.uber.cherami.CreateConsumerGroupRequest;
import com.uber.cherami.CreateDestinationRequest;
import com.uber.cherami.DeleteConsumerGroupRequest;
import com.uber.cherami.DeleteDestinationRequest;
import com.uber.cherami.DestinationDescription;
import com.uber.cherami.DestinationType;
import com.uber.cherami.client.CheramiClient;
import com.uber.cherami.client.ClientOptions;

/**
 * Demonstrates publishing/consuming messages to/from cherami.
 *
 * @author venkat
 */
public class Demo implements Runnable {

    private static final int CONSUMED_MESSAGES_RETENTION_SECONDS = 3600;
    private static final int UNCONSUMED_MESSAGES_RETENTION_SECONDS = 7200;

    private final Context context;

    /**
     * Constructs and returns a Demo object.
     *
     * @param config
     *            Config representing the configuration for demo.
     */
    public Demo(Config config) {
        this.context = new Context(config, buildClient(config));
    }

    @Override
    public void run() {

        final Config config = context.config;

        doSetup(context.client, config.destinationPath, config.consumergroupName);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                doTearDown(context.client, config.destinationPath, config.consumergroupName);
                try {
                    context.client.close();
                } catch (Exception e) {
                    System.out.println("Exception closing cheramiClient " + e);
                }
            }
        });

        // Incrementally generate msgIds starting from 1
        AtomicLong msgCounter = new AtomicLong(0);
        // Keeps track of unique message ids received by consumers so far
        Set<Long> receivedMsgIdsUniq = Collections.synchronizedSet(new HashSet<Long>());

        /*
         * Spin up consumers for this test. In a real world scenario, there
         * should be ONLY ONE consumer object per process. Internally, each
         * consumer object opens multiple streams to the servers and consumes
         * messages concurrently from all of them.
         */
        Daemon[] consumers = new Daemon[config.nConsumers];
        for (int i = 0; i < config.nConsumers; i++) {
            String name = "consumer-" + i;
            consumers[i] = createConsumer(name, receivedMsgIdsUniq);
            consumers[i].start();
        }

        /*
         * Spin up publishers for this test. In a real world scenario, there
         * should be ONLY ONE publisher object per process. Internally, each
         * publisher object opens multiple streams to the servers and
         * multiplexes the messages across the connections.
         */
        Daemon[] publishers = new Daemon[config.nPublishers];
        for (int i = 0; i < config.nPublishers; i++) {
            String name = "publisher-" + i;
            publishers[i] = createPublisher(name, msgCounter);
            publishers[i].start();
        }

        long startTimeMillis = System.currentTimeMillis();
        long nMessagesToReceive = config.nPublishers * config.nMessagesToSend;

        /*
         * Sleep until we receive all the published messages.
         */
        while (true) {
            // check if we consumed all messages
            if (receivedMsgIdsUniq.size() >= nMessagesToReceive) {
                break;
            }
            sleep(TimeUnit.SECONDS.toMillis(1));
        }

        System.out.println("Stopping publishers and consumers...");

        for (int i = 0; i < config.nPublishers; i++) {
            publishers[i].stop();
        }
        for (int i = 0; i < config.nConsumers; i++) {
            consumers[i].stop();
        }

        context.stats.print(System.currentTimeMillis() - startTimeMillis);
        System.exit(0);
    }

    private Daemon createPublisher(String name, AtomicLong msgCounter) {
        if (context.config.useAsync) {
            return new Async.Publisher(name, context, msgCounter);
        }
        return new Sync.Publisher(name, context, msgCounter);
    }

    private Daemon createConsumer(String name, Set<Long> receivedMsgIdsUniq) {
        if (context.config.useAsync) {
            return new Async.Consumer(name, context, receivedMsgIdsUniq);
        }
        return new Sync.Consumer(name, context, receivedMsgIdsUniq);
    }

    private static CheramiClient buildClient(Config config) {
        try {
            ClientOptions options = new ClientOptions.Builder().setDeploymentStr("staging").build();
            if (!config.ip.isEmpty()) {
                // production must also always use service discovery
                return new CheramiClient.Builder(config.ip, config.port).setClientOptions(options).build();
            }
            // production must also set the metricsClient option
            return new CheramiClient.Builder().setClientOptions(options).build();
        } catch (Exception e) {
            System.out.println("Failed to create CheramiClient:" + e);
            throw new RuntimeException(e);
        }
    }

    private static void doSetup(CheramiClient client, String dstPath, String cgName) {
        try {
            CreateDestinationRequest dstRequest = new CreateDestinationRequest();
            dstRequest.setPath(dstPath);
            dstRequest.setType(DestinationType.PLAIN);
            dstRequest.setUnconsumedMessagesRetention(UNCONSUMED_MESSAGES_RETENTION_SECONDS);
            dstRequest.setConsumedMessagesRetention(CONSUMED_MESSAGES_RETENTION_SECONDS);
            dstRequest.setOwnerEmail("cherami-client-example@uber.com");
            dstRequest.setChecksumOption(ChecksumOption.CRC32IEEE);
            DestinationDescription producer = client.createDestination(dstRequest);
            System.out.println("Created Destination:\n" + producer);
            // Create a ConsumerGroup
            CreateConsumerGroupRequest cgRequest = new CreateConsumerGroupRequest();
            cgRequest.setDestinationPath(dstPath);
            cgRequest.setConsumerGroupName(cgName);
            cgRequest.setOwnerEmail("cherami-client-example@uber.com");
            cgRequest.setMaxDeliveryCount(3);
            cgRequest.setSkipOlderMessagesInSeconds(3600);
            cgRequest.setLockTimeoutInSeconds(60);
            cgRequest.setStartFrom(System.nanoTime());
            ConsumerGroupDescription consumerGroup = client.createConsumerGroup(cgRequest);
            System.out.println("Created Consumer Group:\n" + consumerGroup);
        } catch (Exception e) {
            System.out.println("Error setting up destination and consumer group:" + e);
            throw new RuntimeException(e);
        }
    }

    private static void doTearDown(CheramiClient client, String dstPath, String cgName) {
        DeleteConsumerGroupRequest cgRequest = new DeleteConsumerGroupRequest();
        cgRequest.setDestinationPath(dstPath);
        cgRequest.setConsumerGroupName(cgName);
        try {
            client.deleteConsumerGroup(cgRequest);
            System.out.println("Deleted ConsumerGroup " + cgName);
        } catch (Exception e) {
            System.out.println("Error deleting consumer group:" + e);
        }
        DeleteDestinationRequest dstRequest = new DeleteDestinationRequest();
        dstRequest.setPath(dstPath);
        try {
            client.deleteDestination(dstRequest);
            System.out.println("Deleted Destination " + dstPath);
        } catch (Exception e) {
            System.out.println("Error deleting destination:" + e);
        }
        try {
            client.close();
        } catch (IOException e) {
            System.out.println("Error closing CheramiClient:" + e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
        }
    }

    public static void main(String[] args) {
        Config config = Config.parse(args);
        new Demo(config).run();
    }
}

/*******************************************************************************
 *  Copyright (c) 2017 Uber Technologies, Inc.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *******************************************************************************/
package com.uber.cherami.example;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.uber.cherami.client.CheramiConsumer;
import com.uber.cherami.client.CheramiDelivery;
import com.uber.cherami.client.CheramiPublisher;
import com.uber.cherami.client.CreateConsumerRequest;
import com.uber.cherami.client.CreatePublisherRequest;
import com.uber.cherami.client.PublisherMessage;
import com.uber.cherami.client.SendReceipt;
import com.uber.cherami.client.SendReceipt.ReceiptStatus;

/**
 * Demonstrates publishing / consuming from cherami using the async api.
 *
 * @author venkat
 */
public class Async {

    private static final int MAX_INFLIGHT_MESSAGES = 4 * 1024;
    private static final int MAX_PUBLISH_ATTEMPTS = 16;

    /**
     * Publishes messages to cherami using the async api.
     *
     * @author venkat
     */
    public static class Publisher implements Runnable, Daemon {

        private final String name;
        private final Context context;
        private final CountDownLatch quitter = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        /**
         * Constructs and returns a Publisher object.
         *
         * @param name
         *            String representing the unique name of the publisher.
         * @param context
         *            Context object.
         */
        public Publisher(String name, Context context) {
            this.name = name;
            this.context = context;
        }

        @Override
        public void start() {
            new Thread(this).start();
        }

        @Override
        public void stop() {
            quitter.countDown();
            try {
                if (!stopped.await(1, TimeUnit.SECONDS)) {
                    System.out.println(name + ": shutdown timed out");
                }
            } catch (InterruptedException e) {
                return;
            }
        }

        @Override
        public void run() {

            CheramiPublisher publisher = null;

            try {

                System.out.println(name + " started");

                Config config = context.config;
                CreatePublisherRequest.Builder builder = new CreatePublisherRequest.Builder(config.destinationPath);
                publisher = context.client.createPublisher(builder.build());
                publisher.open();

                Random rand = new Random(System.currentTimeMillis());

                int remaining = config.nMessagesToSend;
                Queue<InFlightMsgState> inflight = new LinkedList<>();

                while (inflight.size() > 0 || remaining > 0) {

                    int attempts = 0;
                    long retryMsgId = 0;

                    if (inflight.size() >= MAX_INFLIGHT_MESSAGES || remaining == 0) {
                        // Can't send anymore until we receive
                        // the acks for the previously sent
                        // messages. Read an ack and for every
                        // ack, send another message out
                        InFlightMsgState state = inflight.poll();
                        SendReceipt receipt = state.future.get();
                        context.stats.writeLatency.add(System.currentTimeMillis() - state.sendTime);
                        if (receipt.getStatus() != ReceiptStatus.OK) {
                            context.stats.messagesOutErrCount.incrementAndGet();
                            if (state.attempts >= MAX_PUBLISH_ATTEMPTS) {
                                System.out.println("Max attempts exceeded at sending a message");
                                return;
                            }
                            if (receipt.getStatus() == ReceiptStatus.ERR_THROTTLED) {
                                // ideally, exponential backoff and retry
                                Thread.sleep(1);
                                context.stats.messagesOutThrottledCount.incrementAndGet();
                            }

                            attempts = state.attempts;
                            retryMsgId = state.id;
                        }
                    }

                    if (retryMsgId == 0 && remaining == 0) {
                        // no new or old messages to re-send
                        continue;
                    }

                    long msgId = retryMsgId != 0 ? retryMsgId : context.msgIdCounter.incrementAndGet();
                    PublisherMessage message = createPubMessage(msgId, rand);
                    InFlightMsgState state = new InFlightMsgState(msgId, publisher.writeAsync(message), attempts + 1);
                    inflight.add(state);
                    context.stats.messagesOutCount.incrementAndGet();
                    context.stats.bytesOutCount.addAndGet(message.getData().length);
                    remaining = (retryMsgId == 0) ? (remaining - 1) : remaining;

                    if (quitter.getCount() <= 0) {
                        break;
                    }
                }

            } catch (Throwable t) {
                System.out.println(name + " caught unexpected exception:" + t);
            } finally {
                if (publisher != null) {
                    publisher.close();
                }
                System.out.println(name + " stopped");
                stopped.countDown();
            }
        }

        private PublisherMessage createPubMessage(long id, Random random) {
            byte[] payload = new byte[context.config.messageSize];
            random.nextBytes(payload);
            AppData data = new AppData(id, payload);
            return new PublisherMessage(data.serialize());
        }
    }

    /**
     * Consumes messages from cherami using the async api.
     *
     * @author venkat
     */
    public static class Consumer implements Runnable, Daemon {

        private static final long READ_TIMEOUT_MILLIS = 500;

        private final String name;
        private final Context context;
        private final CountDownLatch quitter = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        /**
         * Constructs and returns a Consumer object.
         *
         * @param name
         *            String representing the unique name of the consumer.
         * @param context
         *            Context object.
         */
        public Consumer(String name, Context context) {
            this.name = name;
            this.context = context;
        }

        @Override
        public void start() {
            new Thread(this).start();
        }

        @Override
        public void stop() {
            quitter.countDown();
            try {
                if (!stopped.await(1, TimeUnit.SECONDS)) {
                    System.out.println(name + ": shutdown timed out");
                }
            } catch (InterruptedException e) {
                return;
            }
        }

        @Override
        public void run() {

            CheramiConsumer consumer = null;

            try {

                System.out.println(name + " started");

                Config config = context.config;
                CreateConsumerRequest.Builder builder = new CreateConsumerRequest.Builder(config.destinationPath,
                        config.consumergroupName);
                CreateConsumerRequest request = builder.setPrefetchCount(MAX_INFLIGHT_MESSAGES).build();
                consumer = context.client.createConsumer(request);
                consumer.open();

                while (quitter.getCount() > 0) {

                    long startTime = System.currentTimeMillis();

                    Future<CheramiDelivery> future = consumer.readAsync();
                    CheramiDelivery delivery = null;
                    while (true) {
                        try {
                            delivery = future.get(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException e) {
                            if (quitter.getCount() <= 0) {
                                return;
                            }
                        }
                    }

                    context.stats.readLatency.add(System.currentTimeMillis() - startTime);
                    AppData appData = AppData.deserialize(delivery.getMessage().getPayload().getData());
                    if (context.consumedMsgIds.putIfAbsent(appData.id, true) != null) {
                        context.stats.messagesInDupCount.incrementAndGet();
                    }
                    delivery.ack();
                    context.stats.messagesInCount.incrementAndGet();
                    context.stats.bytesInCount.addAndGet(delivery.getMessage().getPayload().getData().length);
                }
            } catch (Throwable e) {
                System.out.println(name + " caught unexpected exception:" + e);
            } finally {
                if (consumer != null) {
                    consumer.close();
                }
                System.out.println(name + " stopped");
                stopped.countDown();
            }
        }
    }

    private static class InFlightMsgState {
        public int attempts;
        public final long id;
        public final long sendTime;
        public final Future<SendReceipt> future;

        InFlightMsgState(long id, Future<SendReceipt> future, int attempts) {
            this.id = id;
            this.future = future;
            this.attempts = attempts;
            this.sendTime = System.currentTimeMillis();
        }
    }
}

/*
Copyright 2015 Urban Airship and Contributors
*/

package com.urbanairship.connect.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.ning.http.client.AsyncHttpClient;
import com.urbanairship.connect.java8.Consumer;
import com.urbanairship.connect.client.model.responses.Event;
import com.urbanairship.connect.client.offsets.OffsetManager;
import org.apache.commons.configuration.Configuration;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class for handling {@link com.urbanairship.connect.client.MobileEventStream} interactions.
 * Includes basic stream connection/consumption, reconnection, and offset tracking.
 */
public class MobileEventConsumerService extends AbstractExecutionThreadService {

    private static final Logger log = LogManager.getLogger(MobileEventConsumerService.class);

    private final OffsetManager offsetManager;
    private final ConnectClientConfiguration config;
    private final AtomicBoolean doConsume;
    private final AsyncHttpClient asyncClient;
    private final RawEventReceiver rawEventReceiver;
    private final StreamQueryDescriptor baseStreamQueryDescriptor;
    private final StreamSupplier supplier;
    private final FatalExceptionHandler fatalExceptionHandler;
    private MobileEventStream mobileEventStream;

    /**
     * StreamHandler builder
     * @return Builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    private MobileEventConsumerService(Consumer<Event> consumer,
                                       AsyncHttpClient client,
                                       OffsetManager offsetManager,
                                       StreamQueryDescriptor baseStreamQueryDescriptor,
                                       Configuration config,
                                       StreamSupplier supplier,
                                       FatalExceptionHandler fatalExceptionHandler) {
        this.offsetManager = offsetManager;
        this.config = new ConnectClientConfiguration(config);
        this.asyncClient = client;
        this.doConsume = new AtomicBoolean(true);
        this.rawEventReceiver = new RawEventReceiver(consumer);
        this.baseStreamQueryDescriptor = baseStreamQueryDescriptor;
        this.supplier = supplier;
        this.fatalExceptionHandler = fatalExceptionHandler;
    }

    /**
     * Gets the DoConsume flag indicating whether or not the handler should
     * continue to consume / reconnect to a stream.
     *
     * @return AtomicBoolean
     */
    public AtomicBoolean getDoConsume() {
        return doConsume;
    }

    /**
     * Runs the stream handler by setting doConsume to {@code true} and creating a
     * {@link com.urbanairship.connect.client.MobileEventStream} instance.  The handler will
     * continue to consume or create new {@link com.urbanairship.connect.client.MobileEventStream} instances
     * until either the handler is stopped or the reconnect attempt limit is reached.
     */
    @Override
    public void run() {
        doConsume.set(true);
        stream();
    }

    private void stream() {

        int consumptionAttempt = 0;

        try {
            while (doConsume.get()) {
                boolean connected = false;

                // if this is not the original consumption attempt, create a new StreamDescriptor with the most recent offset
                final StreamQueryDescriptor descriptor;
                if (consumptionAttempt == 0) {
                    descriptor = baseStreamQueryDescriptor;
                } else {
                    descriptor = StreamUtils.buildNewDescriptor(baseStreamQueryDescriptor, offsetManager);
                }

                // create a new MobileEventStream
                try (MobileEventStream newMobileEventStream = supplier.get(descriptor, asyncClient, rawEventReceiver, config.mesUrl, fatalExceptionHandler)) {
                    mobileEventStream = newMobileEventStream;

                    // connect to the MobileEventStream
                    log.info("Connecting to stream for app " + baseStreamQueryDescriptor.getCreds().getAppKey());
                    connected = StreamUtils.connectWithRetries(mobileEventStream, config, baseStreamQueryDescriptor.getCreds().getAppKey());

                    // if connection attempts fail, exit the consumption loop.
                    if (!connected) {
                        fatalExceptionHandler.handle(new RuntimeException("Could not connect to stream for app " + baseStreamQueryDescriptor.getCreds().getAppKey()));
                        break;
                    }

                    // consume from the MobileEventStream
                    log.info("Consuming from stream for app " + baseStreamQueryDescriptor.getCreds().getAppKey());
                    mobileEventStream.consume(config.maxAppStreamConsumeTime, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable throwable) {
                    log.error("Error encountered while consuming stream for app " + baseStreamQueryDescriptor.getCreds().getAppKey(), throwable);
                } finally {
                    log.info("Ending stream handling for app " + baseStreamQueryDescriptor.getCreds().getAppKey());

                    // update consumption attempt
                    consumptionAttempt += 1;

                    if (connected) {
                        // update offset
                        log.debug("Updating offset for app " + baseStreamQueryDescriptor.getCreds().getAppKey());
                        offsetManager.update(rawEventReceiver.get());
                    }
                }
            }
        } finally {
            // close the HTTP client
            asyncClient.close();
        }
    }

    /**
     * Stops any stream handling by setting doConsume to {@code false}.
     */
    @Override
    public void triggerShutdown() {
        log.info("Shutting down stream handler for app " + baseStreamQueryDescriptor.getCreds().getAppKey());
        doConsume.set(false);

        if (mobileEventStream != null) {
            try {
                mobileEventStream.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class Builder{
        private Consumer<Event> consumer;
        private AsyncHttpClient client;
        private OffsetManager offsetManager;
        private StreamQueryDescriptor baseStreamQueryDescriptor;
        private Configuration config;
        private StreamSupplier supplier = new MobileEventStreamSupplier();
        private FatalExceptionHandler fatalExceptionHandler;

        private Builder() {}

        /**
         * Set the Event consumer.
         *
         * @param consumer {@code Consumer<Event>}
         * @return Builder
         */
        public Builder setConsumer(Consumer<Event> consumer) {
            this.consumer = consumer;
            return this;
        }

        /**
         * Set the HTTP client.
         *
         * @param client AsyncHttpClient
         * @return Builder
         */
        public Builder setClient(AsyncHttpClient client) {
            this.client = client;
            return this;
        }

        /**
         * Set the offset manager.
         *
         * @param offsetManager OffsetManager
         * @return Builder
         */
        public Builder setOffsetManager(OffsetManager offsetManager) {
            this.offsetManager = offsetManager;
            return this;
        }

        /**
         * Set the base stream descriptor.
         *
         * @param descriptor StreamDescriptor
         * @return Builder
         */
        public Builder setBaseStreamQueryDescriptor(StreamQueryDescriptor descriptor) {
            this.baseStreamQueryDescriptor = descriptor;
            return this;
        }

        /**
         * Set any config overrides.
         *
         * @param config Configuration
         * @return Builder
         */
        public Builder setConfig(Configuration config) {
            this.config = config;
            return this;
        }

        /**
         * Set the stream supplier.  Not intended for non-testing use.
         *
         * @param supplier Supplier
         * @return Builder
         */
        @VisibleForTesting
        public Builder setSupplier(StreamSupplier supplier) {
            this.supplier = supplier;
            return this;
        }

        /**
         * Set the fatal exception handler for connection failures.
         *
         * @param handler FatalExceptionHandler
         * @return Builder
         */
        public Builder setFatalExceptionHandler(FatalExceptionHandler handler){
            this.fatalExceptionHandler = handler;
            return this;
        }

        /**
         * Build the StreamHandler object.
         *
         * @return StreamHandler
         */
        public MobileEventConsumerService build() {
            return new MobileEventConsumerService(consumer,
                client,
                offsetManager,
                baseStreamQueryDescriptor,
                config,
                supplier,
                fatalExceptionHandler);
        }
    }

    // Straightforward Supplier implementation
    private static class MobileEventStreamSupplier implements StreamSupplier {
        @Override
        public MobileEventStream get(StreamQueryDescriptor descriptor,
                    AsyncHttpClient client,
                    Consumer<String> eventConsumer,
                    String url,
                    FatalExceptionHandler fatalExceptionHandler) {
            return new MobileEventStream(descriptor, client, eventConsumer, url, fatalExceptionHandler);
        }
    }
}

/**
 * Copyright Pravega Authors.
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
package io.pravega.connectors.flink;

import io.pravega.client.ClientConfig;
import io.pravega.client.EventStreamClientFactory;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.stream.Checkpoint;
import io.pravega.client.stream.EventRead;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.ReaderConfig;
import io.pravega.client.stream.ReaderGroup;
import io.pravega.client.stream.ReaderGroupConfig;
import io.pravega.client.stream.ReaderGroupNotFoundException;
import io.pravega.client.stream.Stream;
import io.pravega.client.stream.TruncatedDataException;
import io.pravega.connectors.flink.serialization.DeserializerFromSchemaRegistry;
import io.pravega.connectors.flink.serialization.PravegaDeserializationSchema;
import io.pravega.connectors.flink.serialization.PravegaDeserializationSchemaWithMetadata;
import io.pravega.connectors.flink.watermark.AssignerWithTimeWindows;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.serialization.RuntimeContextInitializationContextAdapters;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.ClosureCleaner;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.apache.flink.streaming.api.checkpoint.ExternallyInducedSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeCallback;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SerializedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.stream.Collectors;

import static io.pravega.connectors.flink.util.FlinkPravegaUtils.byteBufferToArray;
import static io.pravega.connectors.flink.util.FlinkPravegaUtils.createPravegaReader;
import static io.pravega.connectors.flink.util.FlinkPravegaUtils.getReaderName;

/**
 * Flink source implementation for reading from pravega storage.
 *
 * @param <T> The type of the event to be written.
 */
public class FlinkPravegaReader<T>
        extends RichParallelSourceFunction<T>
        implements ResultTypeQueryable<T>, ExternallyInducedSource<T, Checkpoint> {

    // ----- metrics field constants -----

    protected static final String PRAVEGA_READER_METRICS_GROUP = "PravegaReader";

    protected static final String READER_GROUP_METRICS_GROUP = "readerGroup";

    protected static final String STREAM_METRICS_GROUP = "stream";

    protected static final String UNREAD_BYTES_METRICS_GAUGE = "unreadBytes";

    protected static final String READER_GROUP_NAME_METRICS_GAUGE = "readerGroupName";

    protected static final String SCOPE_NAME_METRICS_GAUGE = "scope";

    protected static final String ONLINE_READERS_METRICS_GAUGE = "onlineReaders";

    protected static final String STREAM_NAMES_METRICS_GAUGE = "streams";

    protected static final String SEGMENT_POSITIONS_METRICS_GAUGE = "segmentPositions";

    protected static final String SEPARATOR = ",";

    private static final Logger LOG = LoggerFactory.getLogger(FlinkPravegaReader.class);

    private static final long serialVersionUID = 1L;

    // ----- runtime fields -----

    // Pravega Event Stream Client Factory (NOTE: MUST be closed when reader closed)
    protected transient EventStreamClientFactory eventStreamClientFactory;

    // Pravega Reader Group Manager (NOTE: MUST be closed when reader closed)
    protected transient ReaderGroupManager readerGroupManager = null;

    // ----- configuration fields -----

    // the uuid of the checkpoint hook, used to store state and resume existing state from savepoints
    final String hookUid;

    // The Pravega client config.
    final ClientConfig clientConfig;

    // The Pravega reader group config.
    final ReaderGroupConfig readerGroupConfig;

    // The scope name of the reader group.
    final String readerGroupScope;

    // The readergroup name to coordinate the parallel readers. This should be unique for a Flink job.
    final String readerGroupName;

    // The supplied event deserializer.
    final DeserializationSchema<T> deserializationSchema;

    // The supplied event timestamp and watermark assigner.
    final SerializedValue<AssignerWithTimeWindows<T>> assignerWithTimeWindows;

    // the timeout for reading events from Pravega
    final Time eventReadTimeout;

    // the timeout for call that initiates the Pravega checkpoint
    final Time checkpointInitiateTimeout;

    // flag to enable/disable metrics
    final boolean enableMetrics;

    // ----- runtime fields -----

    // Flag to terminate the source. volatile, because 'stop()' and 'cancel()'
    // may be called asynchronously
    volatile boolean running = true;

    // checkpoint trigger callback, invoked when a checkpoint event is received.
    // no need to be volatile, the source is driven by only one thread
    private transient CheckpointTrigger checkpointTrigger;

    // Pravega reader group
    private transient ReaderGroup readerGroup = null;

    // A collector that emits records in batch (bundle)
    private final PravegaCollector<T> pravegaCollector;

    // ------------------------------------------------------------------------

    /**
     * Creates a new Flink Pravega reader instance which can be added as a source to a Flink job.
     *
     * <p>The reader will use the given {@code readerName} to store its state (its positions
     * in the stream segments) in Flink's checkpoints/savepoints. This name is used in a similar
     * way as the operator UIDs ({@link SingleOutputStreamOperator#uid(String)}) to identify state
     * when matching it into another job that resumes from this job's checkpoints/savepoints.
     *
     * <p>Without specifying a {@code readerName}, the job will correctly checkpoint and recover,
     * but new instances of the job can typically not resume this reader's state (positions).
     *
     * @param hookUid                   The UID of the source hook in the job graph.
     * @param clientConfig              The Pravega client configuration.
     * @param readerGroupConfig         The Pravega reader group configuration.
     * @param readerGroupScope          The reader group scope name.
     * @param readerGroupName           The reader group name.
     * @param deserializationSchema     The implementation to deserialize events from Pravega streams.
     * @param assignerWithTimeWindows   The serialized value of the implementation to extract timestamp from deserialized events (only in event-time mode).
     * @param eventReadTimeout          The event read timeout.
     * @param checkpointInitiateTimeout The checkpoint initiation timeout.
     * @param enableMetrics             Flag to indicate whether metrics needs to be enabled or not.
     */
    protected FlinkPravegaReader(String hookUid, ClientConfig clientConfig,
                                 ReaderGroupConfig readerGroupConfig, String readerGroupScope, String readerGroupName,
                                 DeserializationSchema<T> deserializationSchema,
                                 SerializedValue<AssignerWithTimeWindows<T>> assignerWithTimeWindows,
                                 Time eventReadTimeout, Time checkpointInitiateTimeout,
                                 boolean enableMetrics) {

        this.hookUid = Preconditions.checkNotNull(hookUid, "hookUid");
        this.clientConfig = Preconditions.checkNotNull(clientConfig, "clientConfig");
        this.readerGroupConfig = Preconditions.checkNotNull(readerGroupConfig, "readerGroupConfig");
        this.readerGroupScope = Preconditions.checkNotNull(readerGroupScope, "readerGroupScope");
        this.readerGroupName = Preconditions.checkNotNull(readerGroupName, "readerGroupName");
        this.deserializationSchema = Preconditions.checkNotNull(deserializationSchema, "deserializationSchema");
        this.eventReadTimeout = Preconditions.checkNotNull(eventReadTimeout, "eventReadTimeout");
        this.checkpointInitiateTimeout = Preconditions.checkNotNull(checkpointInitiateTimeout, "checkpointInitiateTimeout");
        this.enableMetrics = enableMetrics;
        this.assignerWithTimeWindows = assignerWithTimeWindows;
        this.pravegaCollector = new PravegaCollector<T>(deserializationSchema);
    }

    /**
     * Initializes the reader.
     */
    void initialize() {
        createEventStreamClientFactory();

        // TODO: This will require the client to have access to the pravega controller and handle any temporary errors.
        //       See https://github.com/pravega/flink-connectors/issues/130.
        LOG.info("Creating reader group: {}/{} for the Flink job", this.readerGroupScope, this.readerGroupName);
        createReaderGroupManager();
        createReaderGroup();
        if (isEventTimeMode()) {
            Preconditions.checkArgument(readerGroup.getStreamNames().size() == 1,
                    "Only 1 Pravega stream is allowed in the event-time mode");
        }
    }

    private boolean isEventTimeMode() {
        return assignerWithTimeWindows != null;
    }

    private long autoWatermarkInterval() {
        return getRuntimeContext().getExecutionConfig().getAutoWatermarkInterval();
    }

    private class PeriodicWatermarkEmitter implements ProcessingTimeCallback {

        private EventStreamReader<?> pravegaReader;
        private Stream stream;
        private final SourceContext<?> ctx;
        private final ProcessingTimeService timerService;
        private long lastWatermarkTimestamp;
        private AssignerWithTimeWindows<?> userAssigner;

        protected PeriodicWatermarkEmitter(
                EventStreamReader<?> pravegaReader, SourceContext<?> ctx, ClassLoader userCodeClassLoader,
                ProcessingTimeService timerService) throws Exception {
            this.pravegaReader = Preconditions.checkNotNull(pravegaReader);
            this.stream = Stream.of(readerGroup.getStreamNames().iterator().next());
            this.ctx = Preconditions.checkNotNull(ctx);
            this.timerService = Preconditions.checkNotNull(timerService);
            this.lastWatermarkTimestamp = Long.MIN_VALUE;
            this.userAssigner = assignerWithTimeWindows.deserializeValue(userCodeClassLoader);
        }

        protected void start() {
            timerService.registerTimer(timerService.getCurrentProcessingTime() + autoWatermarkInterval(), this);
        }

        @Override
        public void onProcessingTime(long timestamp) {
            Watermark watermark = userAssigner.getWatermark(pravegaReader.getCurrentTimeWindow(stream));

            if (watermark != null && watermark.getTimestamp() > lastWatermarkTimestamp) {
                lastWatermarkTimestamp = watermark.getTimestamp();
                LOG.debug("Emit watermark with timestamp: {}", watermark.getTimestamp());
                ctx.emitWatermark(watermark);
            }

            // schedule the next watermark
            timerService.registerTimer(timerService.getCurrentProcessingTime() + autoWatermarkInterval(), this);
        }
    }

    // ------------------------------------------------------------------------
    //  source function methods
    // ------------------------------------------------------------------------

    @Override
    public void run(SourceContext<T> ctx) throws Exception {
        final RuntimeContext runtimeContext = getRuntimeContext();

        final String readerId = getReaderName(runtimeContext.getTaskName(), runtimeContext.getIndexOfThisSubtask() + 1,
                runtimeContext.getNumberOfParallelSubtasks());

        LOG.info("{} : Creating Pravega reader with ID '{}' for controller URI: {}",
                runtimeContext.getTaskNameWithSubtasks(), readerId, this.clientConfig.getControllerURI());

        try (EventStreamReader<ByteBuffer> pravegaReader = createEventStreamReader(readerId)) {

            LOG.info("Starting Pravega reader '{}' for controller URI {}", readerId, this.clientConfig.getControllerURI());

            long previousTimestamp = Long.MIN_VALUE;
            AssignerWithTimeWindows<T> assigner = null;
            // If it is event time, register a watermark emitter
            if (isEventTimeMode()) {
                assigner = assignerWithTimeWindows.deserializeValue(runtimeContext.getUserCodeClassLoader());
                PeriodicWatermarkEmitter periodicEmitter = new PeriodicWatermarkEmitter(
                        pravegaReader,
                        ctx,
                        runtimeContext.getUserCodeClassLoader(),
                        ((StreamingRuntimeContext) runtimeContext).getProcessingTimeService());

                LOG.info("Periodic Watermark Emitter for Reader ID: {} has started with an interval of {}", readerId,
                        autoWatermarkInterval());
                periodicEmitter.start();
            }

            // main work loop, which this task is running
            while (this.running) {
                EventRead<ByteBuffer> eventRead;
                try {
                    eventRead = pravegaReader.readNextEvent(eventReadTimeout.toMilliseconds());
                } catch (TruncatedDataException e) {
                    // Data is truncated, Force the reader going forward to the next available event
                    continue;
                }

                if (eventRead.getEvent() == null) {
                    // if the read marks a checkpoint, trigger the checkpoint
                    if (eventRead.isCheckpoint()) {
                        triggerCheckpoint(eventRead.getCheckpointName());
                    }
                    continue;
                }

                emitEvent(eventRead, ctx, previousTimestamp, assigner);

                if (pravegaCollector.isEndOfStreamSignalled()) {
                    // Found stream end marker.
                    LOG.info("Reached end of stream for reader: {}", readerId);
                    return;
                }
            }
        }
        catch (RuntimeException e) {
            LOG.error("Exception occurred while creating a Pravega EventStreamReader to read events", e);
            throw e;
        }
    }

    /** Deserialize and collect the event. */
    private void emitEvent(EventRead<ByteBuffer> eventRead,
                           SourceContext<T> ctx,
                           long previousTimestamp,
                           @Nullable AssignerWithTimeWindows<T> assigner) throws IOException {
        byte[] eventBytes = byteBufferToArray(eventRead.getEvent());
        if (deserializationSchema instanceof PravegaDeserializationSchemaWithMetadata) {
            ((PravegaDeserializationSchemaWithMetadata<T>) this.deserializationSchema).deserialize(eventBytes, eventRead, pravegaCollector);
        } else {
            this.deserializationSchema.deserialize(eventBytes, pravegaCollector);
        }

        T event;
        while ((event = pravegaCollector.getRecords().poll()) != null) {
            synchronized (ctx.getCheckpointLock()) {
                if (isEventTimeMode()) {
                    assert assigner != null;  // assigner won't be null in the event time mode
                    long currentTimestamp = assigner.extractTimestamp(event, previousTimestamp);
                    ctx.collectWithTimestamp(event, currentTimestamp);
                    previousTimestamp = currentTimestamp;
                } else {
                    ctx.collect(event);
                }
            }
        }

    }

    @Override
    public void cancel() {
        this.running = false;
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return this.deserializationSchema.getProducedType();
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        deserializationSchema.open(RuntimeContextInitializationContextAdapters.deserializationAdapter(
                getRuntimeContext(), metricGroup -> metricGroup.addGroup("user")));
        createEventStreamClientFactory();
        createReaderGroupManager();
        createReaderGroup();
        if (enableMetrics) {
            registerMetrics();
        }
        if (isEventTimeMode()) {
            Preconditions.checkArgument(autoWatermarkInterval() > 0,
                    "Periodic watermark interval should be positive, " +
                            "please use env.getConfig().setAutoWatermarkInterval() to set a positive number. Recommended value: 10000");
        }
    }

    @Override
    public void close() throws Exception {
        Throwable ex = null;
        if (eventStreamClientFactory != null) {
            try {
                LOG.info("Closing Pravega eventStreamClientFactory");
                eventStreamClientFactory.close();
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    LOG.warn("Interrupted while waiting for eventStreamClientFactory to close, retrying ...");
                    eventStreamClientFactory.close();
                } else {
                    ex = ExceptionUtils.firstOrSuppressed(e, ex);
                }
            }
        }
        if (readerGroupManager != null) {
            LOG.info("Closing Pravega ReaderGroupManager");
            try {
                readerGroupManager.close();
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    LOG.warn("Interrupted while waiting for ReaderGroupManager to close, retrying ...");
                    readerGroupManager.close();
                } else {
                    ex = ExceptionUtils.firstOrSuppressed(e, ex);
                }
            }
        }
        if (readerGroup != null) {
            try {
                LOG.info("Closing Pravega ReaderGroup");
                readerGroup.close();
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    LOG.warn("Interrupted while waiting for ReaderGroup to close, retrying ...");
                    readerGroup.close();
                } else {
                    ex = ExceptionUtils.firstOrSuppressed(e, ex);
                }
            }
        }
        if (ex != null && ex instanceof Exception) {
            throw (Exception) ex;
        }
    }

    // ------------------------------------------------------------------------
    //  checkpoints
    // ------------------------------------------------------------------------

    @Override
    public MasterTriggerRestoreHook<Checkpoint> createMasterTriggerRestoreHook() {
        return new ReaderCheckpointHook(this.hookUid,
            this.readerGroupName,
            this.readerGroupScope,
            this.checkpointInitiateTimeout,
            this.clientConfig,
            this.readerGroupConfig);
    }

    @Override
    public void setCheckpointTrigger(CheckpointTrigger checkpointTrigger) {
        this.checkpointTrigger = checkpointTrigger;
    }

    /**
     * Triggers the checkpoint in the Flink source operator.
     *
     * <p>This method assumes that the {@code checkpointIdentifier} is a string of the form
     */
    private void triggerCheckpoint(String checkpointIdentifier) throws FlinkException {
        Preconditions.checkState(checkpointTrigger != null, "checkpoint trigger not set");

        LOG.debug("{} received checkpoint event for {}",
                getRuntimeContext().getTaskNameWithSubtasks(), checkpointIdentifier);

        final long checkpointId;
        try {
            checkpointId = ReaderCheckpointHook.parseCheckpointId(checkpointIdentifier);
        } catch (IllegalArgumentException e) {
            throw new FlinkException("Cannot trigger checkpoint due to invalid Pravega checkpoint name", e.getCause());
        }

        checkpointTrigger.triggerCheckpoint(checkpointId);
    }

    // ------------------------------------------------------------------------
    //  metrics
    // ------------------------------------------------------------------------

    /**
     * Gauge for getting the unread bytes information from reader group.
     */
    private static class UnreadBytesGauge implements Gauge<Long> {

        private final ReaderGroup readerGroup;

        public UnreadBytesGauge(ReaderGroup readerGroup) {
            this.readerGroup = readerGroup;
        }

        @Override
        public Long getValue() {
            return readerGroup.getMetrics().unreadBytes();
        }
    }

    /**
     * Gauge for getting the reader group name information from reader group.
     */
    private static class ReaderGroupNameGauge implements Gauge<String> {

        private final ReaderGroup readerGroup;

        public ReaderGroupNameGauge(ReaderGroup readerGroup) {
            this.readerGroup = readerGroup;
        }

        @Override
        public String getValue() {
            return readerGroup.getGroupName();
        }
    }

    /**
     * Gauge for getting the scope name information from reader group.
     */
    private static class ScopeNameGauge implements Gauge<String> {

        private final ReaderGroup readerGroup;

        public ScopeNameGauge(ReaderGroup readerGroup) {
            this.readerGroup = readerGroup;
        }

        @Override
        public String getValue() {
            return readerGroup.getScope();
        }
    }

    /**
     * Gauge for getting online readers information from reader group.
     */
    private static class OnlineReadersGauge implements Gauge<String> {

        private final ReaderGroup readerGroup;

        public OnlineReadersGauge(ReaderGroup readerGroup) {
            this.readerGroup = readerGroup;
        }

        @Override
        public String getValue() {
            return readerGroup.getOnlineReaders().stream().collect(Collectors.joining(SEPARATOR));
        }
    }

    /**
     * Gauge for getting stream name information from reader group.
     */
    private static class StreamNamesGauge implements Gauge<String> {

        private final ReaderGroup readerGroup;

        public StreamNamesGauge(ReaderGroup readerGroup) {
            this.readerGroup = readerGroup;
        }

        @Override
        public String getValue() {
            return readerGroup.getStreamNames().stream().collect(Collectors.joining(","));
        }
    }

    /**
     * Gauge for getting position information of each segment of a stream from reader group.
     */
    private static class SegmentPositionsGauge implements Gauge<String> {

        private final ReaderGroup readerGroup;
        private final String scope;
        private final String stream;

        public SegmentPositionsGauge(ReaderGroup readerGroup, String scope, String stream) {
            this.readerGroup = readerGroup;
            this.scope = scope;
            this.stream = stream;
        }

        @Override
        public String getValue() {
            StringBuilder builder = new StringBuilder();
            builder.append("scope=").append(scope).append(", ");
            builder.append("stream=").append(stream).append(", segments={");

            readerGroup.getStreamCuts().entrySet().stream()
                    .filter(e -> e.getKey().getStreamName().equals(stream) &&
                            e.getKey().getScope().equals(scope)).findFirst()
                    .ifPresent(streamStreamCutEntry -> builder.append(streamStreamCutEntry.getValue().toString()));

            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * register reader group metrics
     *
     */
    private void registerMetrics() {
        Preconditions.checkState(readerGroup != null, "Reader Group is not created");
        MetricGroup pravegaReaderMetricGroup = getRuntimeContext().getMetricGroup().addGroup(PRAVEGA_READER_METRICS_GROUP);
        MetricGroup readerGroupMetricGroup = pravegaReaderMetricGroup.addGroup(READER_GROUP_METRICS_GROUP);
        readerGroupMetricGroup.gauge(UNREAD_BYTES_METRICS_GAUGE, new UnreadBytesGauge(readerGroup));
        readerGroupMetricGroup.gauge(READER_GROUP_NAME_METRICS_GAUGE, new ReaderGroupNameGauge(readerGroup));
        readerGroupMetricGroup.gauge(SCOPE_NAME_METRICS_GAUGE, new ScopeNameGauge(readerGroup));
        readerGroupMetricGroup.gauge(ONLINE_READERS_METRICS_GAUGE, new OnlineReadersGauge(readerGroup));
        readerGroupMetricGroup.gauge(STREAM_NAMES_METRICS_GAUGE, new StreamNamesGauge(readerGroup));

        Set<String> streamNames = readerGroup.getStreamNames();
        for (String scopedStream: streamNames) {
            String[] streamInfo = scopedStream.split("/", 2);
            Preconditions.checkArgument(streamInfo.length == 2, "not a fully qualified stream expected: scopeName/streamName");
            MetricGroup streamMetricGroup = readerGroupMetricGroup
                    .addGroup(STREAM_METRICS_GROUP + "." + streamInfo[0]+ "_"+ streamInfo[1]);
            streamMetricGroup.gauge(SEGMENT_POSITIONS_METRICS_GAUGE,
                    new SegmentPositionsGauge(readerGroup, streamInfo[0], streamInfo[1]));
        }
    }

    // ------------------------------------------------------------------------
    //  utility
    // ------------------------------------------------------------------------

    /**
     * Create the {@link ReaderGroup} for the current configuration.
     */
    private ReaderGroup createReaderGroup() {
        try {
            readerGroup = readerGroupManager.getReaderGroup(readerGroupName);
        } catch (ReaderGroupNotFoundException e) {
            readerGroupManager.createReaderGroup(readerGroupName, readerGroupConfig);
            readerGroup = readerGroupManager.getReaderGroup(readerGroupName);
        }
        return readerGroup;
    }

    /**
     * Create the {@link ReaderGroupManager} for the current configuration.
     *
     * @return An instance of {@link ReaderGroupManager}
     */
    protected ReaderGroupManager createReaderGroupManager() {
        if (readerGroupManager == null) {
            readerGroupManager = ReaderGroupManager.withScope(readerGroupScope, clientConfig);
        }

        return readerGroupManager;
    }

    /**
     * Create the {@link EventStreamClientFactory} for the current configuration.
     *
     * @return An instance of {@link EventStreamClientFactory}
     */
    protected EventStreamClientFactory createEventStreamClientFactory() {
        if (eventStreamClientFactory == null) {
            eventStreamClientFactory = EventStreamClientFactory.withScope(readerGroupScope, clientConfig);
        }

        return eventStreamClientFactory;
    }

    /**
     * Create the {@link EventStreamReader} for the current configuration. <p>
     *
     * The reader will output raw ByteBuffer rather than the deserialized T.
     * See {@link #emitEvent} for the decoding process.
     * To customize the process, overwrite {@link PravegaDeserializationSchemaWithMetadata}.
     *
     * @param readerId the readerID to use.
     * @return An instance of {@link EventStreamReader}
     */
    protected EventStreamReader<ByteBuffer> createEventStreamReader(String readerId) {
        return createPravegaReader(
                readerId,
                this.readerGroupName,
                ReaderConfig.builder().build(),
                eventStreamClientFactory);
    }

    // ------------------------------------------------------------------------
    //  configuration
    // ------------------------------------------------------------------------

    /**
     * Gets a builder for {@link FlinkPravegaReader} to read Pravega streams using the Flink streaming API.
     *
     * @param <T> the element type.
     * @return A new builder of {@link FlinkPravegaReader}
     */
    public static <T> FlinkPravegaReader.Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * A builder for {@link FlinkPravegaReader}.
     *
     * @param <T> the element type.
     */
    public static class Builder<T> extends AbstractStreamingReaderBuilder<T, Builder<T>> {

        private DeserializationSchema<T> deserializationSchema;
        private SerializedValue<AssignerWithTimeWindows<T>> assignerWithTimeWindows;

        protected Builder<T> builder() {
            return this;
        }

        /**
         * Sets the deserialization schema.
         *
         * @param deserializationSchema The deserialization schema
         * @return Builder instance.
         */
        public Builder<T> withDeserializationSchema(DeserializationSchema<T> deserializationSchema) {
            this.deserializationSchema = deserializationSchema;
            return builder();
        }

        /**
         * Sets the deserialization schema from schema registry. It supports Json, Avro and Protobuf format.
         *
         * @param groupId The group id in schema registry
         * @param tClass  The class describing the deserialized type.
         * @return Builder instance.
         */
        @SuppressWarnings("unchecked")
        public Builder<T> withDeserializationSchemaFromRegistry(String groupId, Class<T> tClass) {
            this.deserializationSchema = new PravegaDeserializationSchema<>(tClass,
                    new DeserializerFromSchemaRegistry<>(getPravegaConfig(), groupId, tClass));
            return builder();
        }

        /**
         * Sets the timestamp and watermark assigner.
         *
         * @param assignerWithTimeWindows The timestamp and watermark assigner.
         * @return Builder instance.
         */

        public Builder<T> withTimestampAssigner(AssignerWithTimeWindows<T> assignerWithTimeWindows) {
            try {
                ClosureCleaner.clean(assignerWithTimeWindows, ExecutionConfig.ClosureCleanerLevel.RECURSIVE, true);
                this.assignerWithTimeWindows = new SerializedValue<>(assignerWithTimeWindows);
            } catch (IOException e) {
                throw new IllegalArgumentException("The given assigner is not serializable", e);
            }
            return this;
        }

        @Override
        protected DeserializationSchema<T> getDeserializationSchema() {
            Preconditions.checkState(deserializationSchema != null, "Deserialization schema must not be null.");
            return deserializationSchema;
        }

        @Override
        protected SerializedValue<AssignerWithTimeWindows<T>> getAssignerWithTimeWindows() {
            return assignerWithTimeWindows;
        }

        /**
         * Builds a {@link FlinkPravegaReader} based on the configuration.
         *
         * @throws IllegalStateException if the configuration is invalid.
         * @return An instance of {@link FlinkPravegaReader}
         */
        public FlinkPravegaReader<T> build() {
            FlinkPravegaReader<T> reader = buildSourceFunction();
            return reader;
        }
    }
}

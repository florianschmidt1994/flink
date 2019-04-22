/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.plugable.SerializationDelegate;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.replication.MonotonicWallclockTime;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.streamrecord.EndOfEpochMarker;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamElement;
import org.apache.flink.streaming.runtime.streamrecord.StreamElementSerializer;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusProvider;
import org.apache.flink.streaming.runtime.tasks.OperatorChain;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.Preconditions;

import java.io.IOException;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Implementation of {@link Output} that sends data using a {@link RecordWriter}.
 */
@Internal
public class RecordWriterOutput<OUT> implements OperatorChain.WatermarkGaugeExposingOutput<StreamRecord<OUT>> {

	private final boolean isSource;

	private StreamRecordWriter<SerializationDelegate<StreamElement>> recordWriter;

	private SerializationDelegate<StreamElement> serializationDelegate;

	private final StreamStatusProvider streamStatusProvider;

	private final OutputTag outputTag;

	private final WatermarkGauge watermarkGauge = new WatermarkGauge();

	private int lastDedupTs;

	private long previousSentTs = MonotonicWallclockTime.getNanos();

	private StreamTask containingTask;

	private long currentEpoch = 0;

	@SuppressWarnings("unchecked")
	public RecordWriterOutput(
		StreamTask containingTask,
		StreamRecordWriter<SerializationDelegate<StreamRecord<OUT>>> recordWriter,
		TypeSerializer<OUT> outSerializer,
		OutputTag outputTag,
		StreamStatusProvider streamStatusProvider,
		boolean isSource
	) {

		this.containingTask = containingTask;

		checkNotNull(recordWriter);
		this.outputTag = outputTag;
		// generic hack: cast the writer to generic Object type so we can use it
		// with multiplexed records and watermarks
		this.recordWriter = (StreamRecordWriter<SerializationDelegate<StreamElement>>)
				(StreamRecordWriter<?>) recordWriter;

		TypeSerializer<StreamElement> outRecordSerializer =
				new StreamElementSerializer<>(outSerializer);

		if (outSerializer != null) {
			serializationDelegate = new SerializationDelegate<>(outRecordSerializer);
		}

		this.streamStatusProvider = checkNotNull(streamStatusProvider);
		this.isSource = isSource;
	}

	@Override
	public void collect(StreamRecord<OUT> record) {

		if (this.outputTag != null) {
			// we are only responsible for emitting to the main input
			return;
		}

		pushToRecordWriter(record);
	}

	@Override
	public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {

		if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
			// we are only responsible for emitting to the side-output specified by our
			// OutputTag.
			return;
		}

		pushToRecordWriter(record);
	}

	private <X> void pushToRecordWriter(StreamRecord<X> record) {
		setup(record);

		try {
			recordWriter.emit(serializationDelegate);
		}
		catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Override
	public void emitWatermark(Watermark mark) {
		setup(mark);

		watermarkGauge.setCurrentWatermark(mark.getTimestamp());

		if (streamStatusProvider.getStreamStatus().isActive()) {
			try {
				recordWriter.broadcastEmit(serializationDelegate);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}
	}

	public void emitStreamStatus(StreamStatus streamStatus) {
		setup(streamStatus);

		try {
			recordWriter.broadcastEmit(serializationDelegate);
		}
		catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Override
	public void emitLatencyMarker(LatencyMarker latencyMarker) {
		setup(latencyMarker);

		try {
			recordWriter.randomEmit(serializationDelegate);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Override
	public void emitBoundedDelayMarker(EndOfEpochMarker delayMarker) {
		setup(delayMarker);

		try {
			// TODO: Thesis - Is broadcast really the right solution here?
			//	Think this through
			recordWriter.broadcastEmit(serializationDelegate);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	private void setup(StreamElement element) {
		if (element.isEndOfEpochMarker()) {
			element.setPreviousTimestamp(Long.MAX_VALUE);
			element.setCurrentTimestamp(Long.MAX_VALUE);
			element.setEpoch(currentEpoch);
			currentEpoch++;
		} else if (this.isSource) {
			long nextSentTs = MonotonicWallclockTime.getNanos();

			element.setCurrentTimestamp(nextSentTs);
			element.setPreviousTimestamp(previousSentTs);
			element.setEpoch(currentEpoch);

			Preconditions.checkState(previousSentTs < nextSentTs, "timestamps for sentTs are not strictly monotonic");

			previousSentTs = nextSentTs;

		} else {
			long out = this.containingTask.getOutTs();
			if (out <= previousSentTs) {
				out = previousSentTs + 1;
			}
			element.setCurrentTimestamp(out);
			element.setPreviousTimestamp(previousSentTs);
			element.setEpoch(currentEpoch);
			previousSentTs = out;
		}

		element.setDeduplicationTimestamp(++lastDedupTs);

		serializationDelegate.setInstance(element);
	}

	public void broadcastEvent(AbstractEvent event) throws IOException {

		recordWriter.broadcastEvent(event);
	}

	public void flush() throws IOException {
		recordWriter.flushAll();
	}

	@Override
	public void close() {
		recordWriter.close();
	}

	@Override
	public Gauge<Long> getWatermarkGauge() {
		return watermarkGauge;
	}
}

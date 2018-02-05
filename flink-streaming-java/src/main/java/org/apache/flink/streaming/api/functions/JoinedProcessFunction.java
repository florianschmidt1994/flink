/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.functions;

import org.apache.flink.api.common.functions.AbstractRichFunction;
import org.apache.flink.streaming.api.TimeDomain;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * A function that processes two joined elements and produces a single output one.
 *
 * <p>This function will get called for every joined pair of elements the joined two streams.
 * The timestamp of the joined pair as well as the timestamp of the left element and the right
 * element can be accessed through the {@link Context}.
 *
 * @param <IN1> Type of the first input
 * @param <IN2> Type of the second input
 * @param <OUT> Type of the output
 */
public abstract class JoinedProcessFunction<IN1, IN2, OUT> extends AbstractRichFunction {

	/**
	 * This method is called for each joined pair of elements.
	 *
	 * <p>This function can output zero or more elements through the {@link Collector} parameter
	 * and has access to timestamps through the {@link Context}
	 *
	 * @param left         The left element the joined pair
	 * @param right        The right element of the joined pair
	 * @param ctx          A context that allows querying the timestamps of the left, right and joined pair
	 * @param out          The collector to emit resulting elements to
	 * @throws Exception   This function may throw exceptions which cause the streaming programm to
	 * 					   fail and go in recovery mode.
	 */
	public abstract void processElement(IN1 left, IN2 right, Context ctx, Collector<OUT> out) throws Exception;

	// TODO: JavaDoc
	public void onTimer(long timestamp, Context ctx, Collector<OUT> out) throws Exception {
	}

	/**
	 * The Context that gets passed to processElement.
	 */
	public abstract class Context implements TimerService {

		/**
		 * @return The timestamp of the left element of a joined pair
		 */
		public abstract long getLeftTimestamp();

		/**
		 * @return The timestamp of the right element of a joined pair
		 */
		public abstract long getRightTimestamp();

		/**
		 * @return The timestamp of the joined pair
		 */
		public abstract long getTimestamp();

		public abstract <X> void output(OutputTag<X> outputTag, X value);
	}
}

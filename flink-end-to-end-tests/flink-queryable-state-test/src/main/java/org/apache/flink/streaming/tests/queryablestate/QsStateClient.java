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

package org.apache.flink.streaming.tests.queryablestate;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.queryablestate.client.QueryableStateClient;
import org.apache.flink.queryablestate.exceptions.UnknownKeyOrNamespaceException;

import org.apache.flink.shaded.guava18.com.google.common.collect.Iterables;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A simple implementation of a queryable state client.
 * This client queries the state for a while (~2.5 mins) and prints
 * out the values that it found in the map state
 *
 * <p>Usage: java -jar QsStateClient.jar --host HOST --port PORT --job-id JOB_ID
 */
public class QsStateClient {

	public static final String STATE_NAME = "state";
	public static final String QUERY_NAME = "state";

	public static void main(final String[] args) throws Exception {

		ParameterTool parameters = ParameterTool.fromArgs(args);

		// setup values
		String jobId = parameters.getRequired("job-id");
		String host = parameters.get("host", "localhost");
		int port = parameters.getInt("port", 9069);
		int numIterations = parameters.getInt("iterations", 1500);

		QueryableStateClient client = new QueryableStateClient(host, port);
		client.setExecutionConfig(new ExecutionConfig());

		MapStateDescriptor<EmailId, EmailInformation> stateDescriptor =
				new MapStateDescriptor<>(
						STATE_NAME,
						TypeInformation.of(new TypeHint<EmailId>() {

						}),
						TypeInformation.of(new TypeHint<EmailInformation>() {

						})
				);

		// wait for state to exist
		for (int i = 0; i < 240; i++) { // ~120s
			try {
				getMapState(jobId, client, stateDescriptor);
				break;
			} catch (ExecutionException e) {
				if (e.getCause() instanceof UnknownKeyOrNamespaceException) {
					System.err.println("State does not exist yet; sleeping 500ms");
					Thread.sleep(500L);
				} else {
					throw e;
				}
			}

			if (i == 239) {
				throw new RuntimeException("Timeout: state doesn't exist after 120s");
			}
		}

		// query state
		for (int iterations = 0; iterations < numIterations; iterations++) {

			MapState<EmailId, EmailInformation> mapState =
				getMapState(jobId, client, stateDescriptor);

			int size = Iterables.size(mapState.values());
			System.out.println("MapState has " + size + " entries");
			if (size > 0) {
				EmailId key = mapState.keys().iterator().next();
				EmailInformation value = mapState.get(key);
				System.out.println("First entry: " + key + " --> " + value);
			}

			Thread.sleep(100L);
		}
	}

	private static MapState<EmailId, EmailInformation> getMapState(
			String jobId,
			QueryableStateClient client,
			MapStateDescriptor<EmailId, EmailInformation> stateDescriptor) throws InterruptedException, ExecutionException {

		CompletableFuture<MapState<EmailId, EmailInformation>> resultFuture =
				client.getKvState(
						JobID.fromHexString(jobId),
						QUERY_NAME,
						"", // which key of the keyed state to access
						BasicTypeInfo.STRING_TYPE_INFO,
						stateDescriptor);

		return resultFuture.get();
	}
}

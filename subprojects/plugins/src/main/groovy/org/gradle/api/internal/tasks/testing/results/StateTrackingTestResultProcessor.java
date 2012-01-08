/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.results;

import org.gradle.api.internal.tasks.testing.*;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestOutputEvent;

import java.util.HashMap;
import java.util.Map;

public abstract class StateTrackingTestResultProcessor implements TestResultProcessor {
    private final Map<Object, TestState> executing = new HashMap<Object, TestState>();
    private Map<Object, TestState> garbage = new HashMap<Object, TestState>();

    public final void started(TestDescriptorInternal test, TestStartEvent event) {
        TestDescriptorInternal parent = null;
        if (event.getParentId() != null) {
            parent = executing.get(event.getParentId()).test;
        }
        TestState state = new TestState(new DecoratingTestDescriptor(test, parent), event, executing);
        TestState oldState = executing.put(test.getId(), state);
        if (oldState != null) {
            throw new IllegalArgumentException(String.format("Received a start event for %s with duplicate id '%s'.",
                    test, test.getId()));
        }

        started(state);
    }

    public final void completed(Object testId, TestCompleteEvent event) {
        TestState testState = executing.remove(testId);
        if (testState == null) {
            throw new IllegalArgumentException(String.format(
                    "Received a completed event for test with unknown id '%s'. Registered test ids: '%s'",
                    testId, executing.keySet()));
        }

        testState.completed(event);
        completed(testState);

        //(SF) Let's garbage collect the test descriptor when the test suite is finished
        //this way we keep the completed tests for a while longer
        //in case the output event arrives shortly after completion of the test
        //and we need to have a matching descriptor to inform the user which test this output belongs to.

        //This approach should generally work because at the moment we reset capturing output per suite
        //(see CaptureTestOutputTestResultProcessor) and that reset happens earlier in the chain.
        //So in theory when suite is completed, the output redirector has been already stopped
        //and there shouldn't be any output events passed
        //See also GRADLE-2035

        //It's far from perfect, so let's call it a first iteration before some serious refactoring
        if (testState.test.isComposite()) {
            garbage = new HashMap<Object, TestState>();
        } else {
            garbage.put(testId, testState);
        }
    }

    public final void failure(Object testId, Throwable result) {
        TestState testState = executing.get(testId);
        if (testState == null) {
            throw new IllegalArgumentException(String.format(
                    "Received a failure event for test with unknown id '%s'. Registered test ids: '%s'",
                    testId, executing.keySet()));
        }
        testState.failures.add(result);
    }

    public final void output(Object testId, TestOutputEvent event) {
        TestState state = executing.get(testId);
        if (state == null) {
            //see the earlier comment about garbage collecting the descriptors
            state = garbage.get(testId);
        }
        TestDescriptor descriptor;
        if (state != null) {
            descriptor = state.test;
        } else {
            //in theory this should not happen
            descriptor = new UnknownTestDescriptor();
        }
        output(descriptor, event);
    }

    protected void output(TestDescriptor descriptor, TestOutputEvent event) {
        // Don't care
    }

    protected void started(TestState state) {
    }

    protected void completed(TestState state) {
    }

    protected TestState getTestStateFor(Object testId) {
        return executing.get(testId);
    }
}
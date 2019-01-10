/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.ballerinalang.bre.bvm;

import org.ballerinalang.model.values.BError;
import org.ballerinalang.util.codegen.ProgramFile;
import org.ballerinalang.util.debugger.DebugContext;
import org.ballerinalang.util.transactions.TransactionLocalContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This represents a Ballerina execution strand in the new VM.
 */
public class Strand {

    private static final int DEFAULT_CONTROL_STACK_SIZE = 2000;

    private static AtomicInteger count = new AtomicInteger(0);

    private String id;

    public State state;

    public volatile boolean aborted;

    // Stack frame pointer;
    public int fp = -1;

    public StackFrame currentFrame;

    public ProgramFile programFile;

    private DebugContext debugContext;

    public StrandCallback respCallback;

    private BError error;

    public Map<String, Object> globalProps;

    public StrandWaitHandler strandWaitHandler;

    public FlushDetail flushDetail;

    private TransactionLocalContext transactionStrandContext;

    public StackFramePool stackFramePool;

    public Strand(ProgramFile programFile, String name, Map<String, Object> properties, StrandCallback respCallback) {
        this.programFile = programFile;
        this.respCallback = respCallback;
        this.state = State.NEW;
        this.globalProps = properties;
        StringBuilder sb = new StringBuilder(name);
        this.id = sb.append("-").append(count.incrementAndGet()).toString();
        this.aborted = false;
        this.transactionStrandContext = null;
        stackFramePool = new StackFramePool();
        if (programFile.debugger.debugEnabled) {
            initDebugger();
        }
    }

    private void initDebugger() {
        this.debugContext = new DebugContext();
        this.programFile.debugger.addStrand(this);
    }

    public String getId() {
        return id;
    }

    public StackFrame pushFrame(StackFrame frame) {
        if (this.fp >= 2000) {
            //TODO fail
        }
        frame.prevFrame = this.currentFrame;
        this.currentFrame = frame;
        this.fp++;
        return currentFrame;
    }

    public StackFrame popFrame() {
        StackFrame poppedFrame = this.currentFrame;
        stackFramePool.returnObject(poppedFrame);
        if (poppedFrame != null) {
            this.currentFrame = poppedFrame.prevFrame;
        }
        this.fp--;
        return poppedFrame;
    }

    public StackFrame peekFrame(int offset) {
        StackFrame peekedFrame = this.currentFrame;
        for (int i = 0; i < offset; i++) {
            peekedFrame = peekedFrame.prevFrame;
        }
        return peekedFrame;
    }

    public StackFrame getCurrentFrame() {
        return currentFrame;
    }

    public void setError(BError error) {
        this.error = error;
    }

    public BError getError() {
        return error;
    }

    public boolean isInTransaction() {
        return this.transactionStrandContext != null;
    }

    public void setLocalTransactionContext(TransactionLocalContext transactionLocalContext) {
        this.transactionStrandContext = transactionLocalContext;
    }

    public TransactionLocalContext getLocalTransactionContext() {
        return this.transactionStrandContext;
    }

    public void removeLocalTransactionContext() {
        this.transactionStrandContext = null;
    }

    public void createWaitHandler(int callBacksRemaining, List<Integer> callBacksToWaitFor) {
        this.strandWaitHandler = new StrandWaitHandler(callBacksRemaining, callBacksToWaitFor);
    }

    public void acquireExecutionLock() {
        try {
            this.strandWaitHandler.executionLock.acquire();
        } catch (InterruptedException e) {
            /* ignore */
        }
    }

    public void releaseExecutionLock() {
        this.strandWaitHandler.executionLock.release();
    }

    public DebugContext getDebugContext() {
        return debugContext;
    }

    public void configureFlushDetails(String[] flushChannels) {
        this.flushDetail = new FlushDetail(flushChannels);
    }

    /**
     * Strand execution states.
     */
    public enum State {
        NEW,
        RUNNABLE,
        PAUSED,
        TERMINATED
    }

    /**
     * This class holds relevant data for callback wait handling related to strand side.
     */
    public static class StrandWaitHandler {
        private Semaphore executionLock;
        boolean waitCompleted;
        // This is for wait for all scenario
        List<Integer> callbacksToWaitFor;
        // This is for wait for any scenario
        int callBacksRemaining;

        public StrandWaitHandler(int callBacksRemaining, List<Integer> callBacksToWaitFor) {
            this.callBacksRemaining = callBacksRemaining;
            this.callbacksToWaitFor = callBacksToWaitFor;
            this.waitCompleted = false;
            this.executionLock = new Semaphore(1);
        }
    }

    /**
     * Class to hold flush action related details.
     */
    public static class FlushDetail {
        public String[] flushChannels;
        public int flushedCount;
        public Lock flushLock;

        public FlushDetail(String[] flushChannels) {
            this.flushChannels = flushChannels;
            this.flushedCount = 0;
            this.flushLock = new ReentrantLock();
        }
    }
}


/*
 * Copyright 2015 NAVER Corp.
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

package com.navercorp.pinpoint.plugin.thrift.interceptor.client.async;

import static com.navercorp.pinpoint.plugin.thrift.ThriftScope.THRIFT_CLIENT_SCOPE;

import java.net.SocketAddress;

import com.navercorp.pinpoint.bootstrap.async.AsyncTraceIdAccessor;
import com.navercorp.pinpoint.bootstrap.context.AsyncTraceId;
import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.context.TraceId;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.interceptor.annotation.Scope;
import com.navercorp.pinpoint.bootstrap.interceptor.annotation.Name;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.ExecutionPolicy;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.InterceptorScope;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.InterceptorScopeInvocation;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.plugin.thrift.ThriftConstants;
import com.navercorp.pinpoint.plugin.thrift.ThriftRequestProperty;
import com.navercorp.pinpoint.plugin.thrift.ThriftUtils;
import com.navercorp.pinpoint.plugin.thrift.field.accessor.SocketAddressFieldAccessor;
import org.apache.thrift.async.TAsyncMethodCall;

/**
 * @author HyunGil Jeong
 */
@Scope(value = THRIFT_CLIENT_SCOPE, executionPolicy = ExecutionPolicy.BOUNDARY)
public class TAsyncClientManagerCallInterceptor implements AroundInterceptor {

    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final boolean isDebug = logger.isDebugEnabled();

    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;
    private final InterceptorScope scope;

    public TAsyncClientManagerCallInterceptor(TraceContext traceContext, MethodDescriptor descriptor, @Name(THRIFT_CLIENT_SCOPE) InterceptorScope scope) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
        this.scope = scope;
    }

    @Override
    public void before(Object target, Object[] args) {
        if (isDebug) {
            logger.beforeInterceptor(target, args);
        }

        if (!validate(target, args)) {
            return;
        }

        final Trace trace = this.traceContext.currentRawTraceObject();
        if (trace == null) {
            return;
        }

        try {
            ThriftRequestProperty parentTraceInfo = new ThriftRequestProperty();
            final boolean shouldSample = trace.canSampled();
            if (!shouldSample) {
                if (isDebug) {
                    logger.debug("set Sampling flag=false");
                }
                parentTraceInfo.setShouldSample(shouldSample);
            } else {
                SpanEventRecorder recorder = trace.traceBlockBegin();
                Object asyncMethodCallObj = args[0];
                // inject async trace info to AsyncMethodCall object
                final AsyncTraceId asyncTraceId = injectAsyncTraceId(asyncMethodCallObj, trace);

                // retrieve connection information
                String remoteAddress = getRemoteAddress(asyncMethodCallObj);

                final TraceId nextId = asyncTraceId.getNextTraceId();

                // Inject nextSpanId as the actual sending of data will be handled asynchronously.
                final long nextSpanId = nextId.getSpanId();
                parentTraceInfo.setSpanId(nextSpanId);

                parentTraceInfo.setTraceId(nextId.getTransactionId());
                parentTraceInfo.setParentSpanId(nextId.getParentSpanId());

                parentTraceInfo.setFlags(nextId.getFlags());
                parentTraceInfo.setParentApplicationName(this.traceContext.getApplicationName());
                parentTraceInfo.setParentApplicationType(this.traceContext.getServerTypeCode());
                parentTraceInfo.setAcceptorHost(remoteAddress);


                recorder.recordServiceType(ThriftConstants.THRIFT_CLIENT);
                recorder.recordNextSpanId(nextSpanId);
                recorder.recordDestinationId(remoteAddress);

                String methodUri = ThriftUtils.getAsyncMethodCallName((TAsyncMethodCall<?>) asyncMethodCallObj);
                String thriftUrl = remoteAddress + "/" + methodUri;
                recorder.recordAttribute(ThriftConstants.THRIFT_URL, thriftUrl);
            }
            InterceptorScopeInvocation currentTransaction = this.scope.getCurrentInvocation();
            currentTransaction.setAttachment(parentTraceInfo);
        } catch (Throwable t) {
            logger.warn("BEFORE error. Caused:{}", t.getMessage(), t);
        }
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        if (isDebug) {
            logger.afterInterceptor(target, args, result, throwable);
        }

        final Trace trace = this.traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }

        try {
            SpanEventRecorder recorder = trace.currentSpanEventRecorder();
            recorder.recordApi(this.descriptor);
            recorder.recordException(throwable);
        } catch (Throwable t) {
            logger.warn("AFTER error. Caused:{}", t.getMessage(), t);
        } finally {
            trace.traceBlockEnd();
        }
    }

    private boolean validate(final Object target, final Object[] args) {
        if (args.length != 1) {
            return false;
        }

        Object asyncMethodCallObj = args[0];
        if (asyncMethodCallObj == null) {
            if (isDebug) {
                logger.debug("Field accessor target object is null.");
            }
            return false;
        }

        if (!(asyncMethodCallObj instanceof AsyncTraceIdAccessor)) {
            if (isDebug) {
                logger.debug("Invalid target object. Need field accessor({}).", AsyncTraceIdAccessor.class.getName());
            }
            return false;
        }

        return true;
    }

    private AsyncTraceId injectAsyncTraceId(final Object asyncMethodCallObj, final Trace trace) {
        final AsyncTraceId asyncTraceId = trace.getAsyncTraceId();
        SpanEventRecorder recorder = trace.currentSpanEventRecorder();
        recorder.recordNextAsyncId(asyncTraceId.getAsyncId());
        ((AsyncTraceIdAccessor) asyncMethodCallObj)._$PINPOINT$_setAsyncTraceId(asyncTraceId);
        if (isDebug) {
            logger.debug("Set asyncTraceId metadata {}", asyncTraceId);
        }
        return asyncTraceId;
    }

    private String getRemoteAddress(Object asyncMethodCallObj) {
        if (!(asyncMethodCallObj instanceof SocketAddressFieldAccessor)) {
            return ThriftConstants.UNKNOWN_ADDRESS;
        }
        SocketAddress socketAddress = ((SocketAddressFieldAccessor)asyncMethodCallObj)._$PINPOINT$_getSocketAddress();
        return ThriftUtils.getHostPort(socketAddress);
    }

}

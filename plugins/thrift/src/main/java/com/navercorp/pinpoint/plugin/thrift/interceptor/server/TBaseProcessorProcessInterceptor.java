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

package com.navercorp.pinpoint.plugin.thrift.interceptor.server;

import static com.navercorp.pinpoint.plugin.thrift.ThriftScope.THRIFT_SERVER_SCOPE;

import java.net.Socket;

import com.navercorp.pinpoint.bootstrap.interceptor.annotation.Scope;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.InterceptorScope;
import org.apache.thrift.TBaseProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;

import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanEventRecorder;
import com.navercorp.pinpoint.bootstrap.context.SpanRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor;
import com.navercorp.pinpoint.bootstrap.interceptor.annotation.Name;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.ExecutionPolicy;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.InterceptorScopeInvocation;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;
import com.navercorp.pinpoint.plugin.thrift.ThriftClientCallContext;
import com.navercorp.pinpoint.plugin.thrift.ThriftConstants;
import com.navercorp.pinpoint.plugin.thrift.ThriftUtils;
import com.navercorp.pinpoint.plugin.thrift.field.accessor.SocketFieldAccessor;

/**
 * Entry/exit point for tracing synchronous processors for Thrift services.
 * <p>
 * Because trace objects cannot be created until the message is read, this interceptor merely sends trace objects created by other interceptors in the tracing
 * pipeline:
 * <ol>
 * <li>
 * <p>
 * {@link com.navercorp.pinpoint.plugin.thrift.interceptor.server.ProcessFunctionProcessInterceptor ProcessFunctionProcessInterceptor} marks the start of a
 * trace, and sets up the environment for trace data to be injected.</li>
 * </p>
 * 
 * <li>
 * <p>
 * {@link com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadFieldBeginInterceptor TProtocolReadFieldBeginInterceptor},
 * {@link com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadTTypeInterceptor TProtocolReadTTypeInterceptor} reads the header fields
 * and injects the parent trace object (if any).</li></p>
 * 
 * <li>
 * <p>
 * {@link com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadMessageEndInterceptor TProtocolReadMessageEndInterceptor} creates the
 * actual root trace object.</li></p> </ol>
 * <p>
 * <b><tt>TBaseProcessorProcessInterceptor</tt></b> -> <tt>ProcessFunctionProcessInterceptor</tt> -> <tt>TProtocolReadFieldBeginInterceptor</tt> <->
 * <tt>TProtocolReadTTypeInterceptor</tt> -> <tt>TProtocolReadMessageEndInterceptor</tt>
 * <p>
 * Based on Thrift 0.8.0+
 * 
 * @author HyunGil Jeong
 * 
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.server.ProcessFunctionProcessInterceptor ProcessFunctionProcessInterceptor
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadFieldBeginInterceptor TProtocolReadFieldBeginInterceptor
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadTTypeInterceptor TProtocolReadTTypeInterceptor
 * @see com.navercorp.pinpoint.plugin.thrift.interceptor.tprotocol.server.TProtocolReadMessageEndInterceptor TProtocolReadMessageEndInterceptor
 */
@Scope(value = THRIFT_SERVER_SCOPE, executionPolicy = ExecutionPolicy.BOUNDARY)
public class TBaseProcessorProcessInterceptor implements AroundInterceptor {

    private final PLogger logger = PLoggerFactory.getLogger(this.getClass());
    private final boolean isDebug = logger.isDebugEnabled();

    private final TraceContext traceContext;
    private final MethodDescriptor descriptor;
    private final InterceptorScope scope;

    public TBaseProcessorProcessInterceptor(TraceContext traceContext, MethodDescriptor descriptor, @Name(THRIFT_SERVER_SCOPE) InterceptorScope scope) {
        this.traceContext = traceContext;
        this.descriptor = descriptor;
        this.scope = scope;
    }

    @Override
    public void before(Object target, Object[] args) {
        // Do nothing
    }

    @Override
    public void after(Object target, Object[] args, Object result, Throwable throwable) {
        final Trace trace = this.traceContext.currentRawTraceObject();
        if (trace == null) {
            return;
        }
        // logging here as some Thrift servers depend on TTransportException being thrown for normal operations.
        // log only when current transaction is being traced.
        if (isDebug) {
            logger.afterInterceptor(target, args, result, throwable);
        }
        this.traceContext.removeTraceObject();
        if (trace.canSampled()) {
            try {
                processTraceObject(trace, target, args, throwable);
            } catch (Throwable t) {
                logger.warn("Error processing trace object. Cause:{}", t.getMessage(), t);
            } finally {
                trace.close();
            }
        }
    }

    private void processTraceObject(final Trace trace, Object target, Object[] args, Throwable throwable) {
        // end spanEvent
        try {
            SpanEventRecorder recorder = trace.currentSpanEventRecorder();
            // TODO Might need a way to collect and record method arguments
            // trace.recordAttribute(...);
            recorder.recordException(throwable);
            recorder.recordApi(this.descriptor);
        } catch (Throwable t) {
            logger.warn("Error processing trace object. Cause:{}", t.getMessage(), t);
        } finally {
            trace.traceBlockEnd();
        }

        // end root span
        SpanRecorder recorder = trace.getSpanRecorder();
        String methodUri = getMethodUri(target);
        recorder.recordRpcName(methodUri);
        // retrieve connection information
        String localIpPort = ThriftConstants.UNKNOWN_ADDRESS;
        String remoteAddress = ThriftConstants.UNKNOWN_ADDRESS;
        if (args.length == 2 && args[0] instanceof TProtocol) {
            TProtocol inputProtocol = (TProtocol)args[0];
            TTransport inputTransport = inputProtocol.getTransport();
            if (inputTransport instanceof SocketFieldAccessor) {
                Socket socket = ((SocketFieldAccessor)inputTransport)._$PINPOINT$_getSocket();
                if (socket != null) {
                    localIpPort = ThriftUtils.getHostPort(socket.getLocalSocketAddress());
                    remoteAddress = ThriftUtils.getHost(socket.getRemoteSocketAddress());
                }
            } else {
                if (isDebug) {
                    logger.debug("Invalid target object. Need field accessor({}).", SocketFieldAccessor.class.getName());
                }
            }
        }
        if (localIpPort != ThriftConstants.UNKNOWN_ADDRESS) {
            recorder.recordEndPoint(localIpPort);
        }
        if (remoteAddress != ThriftConstants.UNKNOWN_ADDRESS) {
            recorder.recordRemoteAddress(remoteAddress);
        }
    }

    private String getMethodUri(Object target) {
        String methodUri = ThriftConstants.UNKNOWN_METHOD_URI;
        InterceptorScopeInvocation currentTransaction = this.scope.getCurrentInvocation();
        Object attachment = currentTransaction.getAttachment();
        if (attachment instanceof ThriftClientCallContext && target instanceof TBaseProcessor) {
            ThriftClientCallContext clientCallContext = (ThriftClientCallContext)attachment;
            String methodName = clientCallContext.getMethodName();
            methodUri = ThriftUtils.getProcessorNameAsUri((TBaseProcessor<?>)target);
            StringBuilder sb = new StringBuilder(methodUri);
            if (!methodUri.endsWith("/")) {
                sb.append("/");
            }
            sb.append(methodName);
            methodUri = sb.toString();
        }
        return methodUri;
    }

}

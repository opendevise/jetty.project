//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.HttpChannelState.Action;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;

/**
 * HttpChannel represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both an HttpParser.RequestHandler, where it passively receives events from
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to
 * HttpTransport.completed().
 */
public abstract class HttpChannel implements Runnable, HttpOutput.Interceptor
{
    public static Listener NOOP_LISTENER = new Listener() {};
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);

    private final AtomicLong _requests = new AtomicLong();
    private final Connector _connector;
    private final Executor _executor;
    private final HttpConfiguration _configuration;
    private final EndPoint _endPoint;
    private final HttpTransport _transport;
    private final HttpChannelState _state;
    private final Request _request;
    private final Response _response;
    private final HttpChannel.Listener _combinedListener;
    private final Dispatchable _requestDispatcher;
    private final Dispatchable _asyncDispatcher;
    @Deprecated
    private final List<Listener> _transientListeners = new ArrayList<>();
    private MetaData.Response _committedMetaData;
    private RequestLog _requestLog;
    private long _oldIdleTimeout;

    /**
     * Bytes written after interception (eg after compression)
     */
    private long _written;

    public HttpChannel(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport)
    {
        _connector = connector;
        _configuration = Objects.requireNonNull(configuration);
        _endPoint = endPoint;
        _transport = transport;

        _state = new HttpChannelState(this);
        _request = new Request(this, newHttpInput(_state));
        _response = new Response(this, newHttpOutput());
        _executor = connector.getServer().getThreadPool();
        _requestLog = connector.getServer().getRequestLog();
        _combinedListener = (connector instanceof AbstractConnector)
            ? ((AbstractConnector)connector).getHttpChannelListeners()
            : NOOP_LISTENER;
        _requestDispatcher = new RequestDispatchable();
        _asyncDispatcher = new AsyncDispatchable();

        if (LOG.isDebugEnabled())
            LOG.debug("new {} -> {},{},{}",
                this,
                _endPoint,
                _endPoint == null ? null : _endPoint.getConnection(),
                _state);
    }

    public boolean isSendError()
    {
        return _state.isSendError();
    }

    /** Format the address or host returned from Request methods
     * @param addr The address or host
     * @return Default implementation returns {@link HostPort#normalizeHost(String)}
     */
    protected String formatAddrOrHost(String addr)
    {
        return HostPort.normalizeHost(addr);
    }

    private HttpInput newHttpInput(HttpChannelState state)
    {
        return new HttpInput(state);
    }

    /**
     * Notify the channel that content is needed. If some content is immediately available, true is returned and
     * {@link #produceContent()} has to be called and will return a non-null object.
     * If no content is immediately available, an attempt to produce content must be made; if new content has been
     * produced, true is returned; otherwise {@link HttpInput#onContentProducible()} is called once some content
     * arrives and {@link #produceContent()} can be called without returning {@code null}.
     * If a failure happens, then {@link HttpInput#onContentProducible()} will be called and an error content will
     * return the error on the next call to {@link #produceContent()}.
     * @return true if content is immediately available.
     */
    public abstract boolean needContent();

    /**
     * Produce a {@link HttpInput.Content} object with data currently stored within the channel. The produced content
     * can be special (meaning calling {@link HttpInput.Content#isSpecial()} returns true) if the channel reached a special
     * state, like EOF or an error.
     * Once a special content has been returned, all subsequent calls to this method will always return a special content
     * of the same kind and {@link #needContent()} will always return true.
     * The returned content is "raw", i.e.: not decoded.
     * @return a {@link HttpInput.Content} object if one is immediately available without blocking, null otherwise.
     */
    public abstract HttpInput.Content produceContent();

    /**
     * Fail all content that is currently stored within the channel.
     * @param failure the failure to fail the content with.
     * @return true if EOF was reached while failing all content, false otherwise.
     */
    public abstract boolean failAllContent(Throwable failure);

    /**
     * Fail the channel's input.
     * @param failure the failure.
     * @return true if the channel needs to be rescheduled.
     */
    public abstract boolean failed(Throwable failure);

    /**
     * Mark the channel's input as EOF.
     * @return true if the channel needs to be rescheduled.
     */
    protected abstract boolean eof();

    protected HttpOutput newHttpOutput()
    {
        return new HttpOutput(this);
    }

    public HttpChannelState getState()
    {
        return _state;
    }

    /**
     * Add a transient Listener to the HttpChannel.
     * <p>Listeners added by this method will only be notified
     * if the HttpChannel has been constructed with an instance of
     * {@link TransientListeners} as an {@link AbstractConnector}
     * provided listener</p>
     * <p>Transient listeners are removed after every request cycle</p>
     * @param listener the listener to add
     * @return true if the listener was added.
     */
    @Deprecated
    public boolean addListener(Listener listener)
    {
        return _transientListeners.add(listener);
    }

    @Deprecated
    public boolean removeListener(Listener listener)
    {
        return _transientListeners.remove(listener);
    }

    @Deprecated
    public List<Listener> getTransientListeners()
    {
        return _transientListeners;
    }

    public long getBytesWritten()
    {
        return _written;
    }

    /**
     * @return the number of requests handled by this connection
     */
    public long getRequests()
    {
        return _requests.get();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpTransport getHttpTransport()
    {
        return _transport;
    }

    public RequestLog getRequestLog()
    {
        return _requestLog;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        _requestLog = requestLog;
    }

    public void addRequestLog(RequestLog requestLog)
    {
        if (_requestLog == null)
            _requestLog = requestLog;
        else if (_requestLog instanceof RequestLogCollection)
            ((RequestLogCollection)_requestLog).add(requestLog);
        else
            _requestLog = new RequestLogCollection(_requestLog, requestLog);
    }

    public MetaData.Response getCommittedMetaData()
    {
        return _committedMetaData;
    }

    /**
     * Get the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#getIdleTimeout()}, but may be
     * overridden by channels that have timeouts different from their connections.
     *
     * @return the idle timeout (in milliseconds)
     */
    public long getIdleTimeout()
    {
        return _endPoint.getIdleTimeout();
    }

    /**
     * Set the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#setIdleTimeout(long)}, but may be
     * overridden by channels that have timeouts different from their connections.
     *
     * @param timeoutMs the idle timeout in milliseconds
     */
    public void setIdleTimeout(long timeoutMs)
    {
        _endPoint.setIdleTimeout(timeoutMs);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return _connector.getByteBufferPool();
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Request getRequest()
    {
        return _request;
    }

    public Response getResponse()
    {
        return _response;
    }

    public Connection getConnection()
    {
        return _endPoint.getConnection();
    }

    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    /**
     * <p>Return the local name of the connected channel.</p>
     *
     * <p>
     * This is the host name after the connector is bound and the connection is accepted.
     * </p>
     * <p>
     * Value can be overridden by {@link HttpConfiguration#setLocalAddress(SocketAddress)}.
     * </p>
     * <p>
     * Note: some connectors are not based on IP networking, and default behavior here will
     * result in a null return.  Use {@link HttpConfiguration#setLocalAddress(SocketAddress)}
     * to set the value to an acceptable host name.
     * </p>
     *
     * @return the local name, or null
     */
    public String getLocalName()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
        {
            SocketAddress localAddress = httpConfiguration.getLocalAddress();
            if (localAddress instanceof InetSocketAddress)
                return ((InetSocketAddress)localAddress).getHostName();
        }

        InetSocketAddress local = getLocalAddress();
        if (local != null)
            return local.getHostString();

        return null;
    }

    /**
     * <p>Return the Local Port of the connected channel.</p>
     *
     * <p>
     * This is the port the connector is bound to and is accepting connections on.
     * </p>
     * <p>
     * Value can be overridden by {@link HttpConfiguration#setLocalAddress(SocketAddress)}.
     * </p>
     * <p>
     * Note: some connectors are not based on IP networking, and default behavior here will
     * result in a value of 0 returned.  Use {@link HttpConfiguration#setLocalAddress(SocketAddress)}
     * to set the value to an acceptable port.
     * </p>
     *
     * @return the local port, or 0 if unspecified
     */
    public int getLocalPort()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
        {
            SocketAddress localAddress = httpConfiguration.getLocalAddress();
            if (localAddress instanceof InetSocketAddress)
                return ((InetSocketAddress)localAddress).getPort();
        }

        InetSocketAddress local = getLocalAddress();
        return local == null ? 0 : local.getPort();
    }

    public InetSocketAddress getLocalAddress()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
        {
            SocketAddress localAddress = httpConfiguration.getLocalAddress();
            if (localAddress instanceof InetSocketAddress)
                return ((InetSocketAddress)localAddress);
        }

        SocketAddress local = _endPoint.getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = _endPoint.getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return (InetSocketAddress)remote;
        return null;
    }

    /**
     * @return return the HttpConfiguration server authority override
     */
    public HostPort getServerAuthority()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
            return httpConfiguration.getServerAuthority();

        return null;
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @param available estimate of the number of bytes that are available
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    public void recycle()
    {
        _request.recycle();
        _response.recycle();
        _committedMetaData = null;
        _requestLog = _connector == null ? null : _connector.getServer().getRequestLog();
        _written = 0;
        _oldIdleTimeout = 0;
        _transientListeners.clear();
    }

    @Override
    public void run()
    {
        handle();
    }

    /**
     * @return True if the channel is ready to continue handling (ie it is not suspended)
     */
    public boolean handle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} {} ", _request.getHttpURI(), this);

        HttpChannelState.Action action = _state.handling();

        // Loop here to handle async request redispatches.
        // The loop is controlled by the call to async.unhandle in the
        // finally block below.  Unhandle will return false only if an async dispatch has
        // already happened when unhandle is called.
        loop:
        while (!getServer().isStopped())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("action {} {}", action, this);

                switch (action)
                {
                    case TERMINATED:
                        onCompleted();
                        break loop;

                    case WAIT:
                        // break loop without calling unhandle
                        break loop;

                    case DISPATCH:
                    {
                        if (!_request.hasMetaData())
                            throw new IllegalStateException("state=" + _state);

                        dispatch(DispatcherType.REQUEST, _requestDispatcher);

                        break;
                    }

                    case ASYNC_DISPATCH:
                    {
                        dispatch(DispatcherType.ASYNC, _asyncDispatcher);
                        break;
                    }

                    case ASYNC_TIMEOUT:
                        _state.onTimeout();
                        break;

                    case SEND_ERROR:
                    {
                        try
                        {
                            // Get ready to send an error response
                            _response.resetContent();

                            // the following is needed as you cannot trust the response code and reason
                            // as those could have been modified after calling sendError
                            Integer code = (Integer)_request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                            if (code == null)
                                code = HttpStatus.INTERNAL_SERVER_ERROR_500;
                            _response.setStatus(code);

                            // The handling of the original dispatch failed and we are now going to either generate
                            // and error response ourselves or dispatch for an error page.  If there is content left over
                            // from the failed dispatch, then we try to consume it here and if we fail we add a
                            // Connection:close.  This can't be deferred to COMPLETE as the response will be committed
                            // by then.
                            ensureConsumeAllOrNotPersistent();

                            ContextHandler.Context context = (ContextHandler.Context)_request.getAttribute(ErrorHandler.ERROR_CONTEXT);
                            ErrorHandler errorHandler = ErrorHandler.getErrorHandler(getServer(), context == null ? null : context.getContextHandler());

                            // If we can't have a body, then create a minimal error response.
                            if (HttpStatus.hasNoBody(_response.getStatus()) || errorHandler == null || !errorHandler.errorPageForMethod(_request.getMethod()))
                            {
                                sendResponseAndComplete();
                                break;
                            }

                            dispatch(DispatcherType.ERROR, new ErrorDispatchable(errorHandler));
                        }
                        catch (Throwable x)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform ERROR dispatch, aborting", x);
                            if (_state.isResponseCommitted())
                                abort(x);
                            else
                            {
                                try
                                {
                                    _response.resetContent();
                                    sendResponseAndComplete();
                                }
                                catch (Throwable t)
                                {
                                    if (x != t)
                                        x.addSuppressed(t);
                                    abort(x);
                                }
                            }
                        }
                        finally
                        {
                            // clean up the context that was set in Response.sendError
                            _request.removeAttribute(ErrorHandler.ERROR_CONTEXT);
                        }
                        break;
                    }

                    case ASYNC_ERROR:
                    {
                        throw _state.getAsyncContextEvent().getThrowable();
                    }

                    case READ_CALLBACK:
                    {
                        ContextHandler handler = _state.getContextHandler();
                        if (handler != null)
                            handler.handle(_request, _request.getHttpInput());
                        else
                            _request.getHttpInput().run();
                        break;
                    }

                    case WRITE_CALLBACK:
                    {
                        ContextHandler handler = _state.getContextHandler();
                        if (handler != null)
                            handler.handle(_request, _response.getHttpOutput());
                        else
                            _response.getHttpOutput().run();
                        break;
                    }

                    case COMPLETE:
                    {
                        if (!_response.isCommitted())
                        {
                            if (!_request.isHandled() && !_response.getHttpOutput().isClosed())
                            {
                                // The request was not actually handled
                                _response.sendError(HttpStatus.NOT_FOUND_404);
                                break;
                            }

                            // Indicate Connection:close if we can't consume all.
                            if (_response.getStatus() >= 200)
                                ensureConsumeAllOrNotPersistent();
                        }

                        // RFC 7230, section 3.3.
                        if (!_request.isHead() &&
                            _response.getStatus() != HttpStatus.NOT_MODIFIED_304 &&
                            !_response.isContentComplete(_response.getHttpOutput().getWritten()))
                        {
                            if (sendErrorOrAbort("Insufficient content written"))
                                break;
                        }

                        // If send error is called we need to break.
                        if (checkAndPrepareUpgrade())
                            break;

                        // Set a close callback on the HttpOutput to make it an async callback
                        _response.completeOutput(Callback.from(NON_BLOCKING, () -> _state.completed(null), _state::completed));

                        break;
                    }

                    default:
                        throw new IllegalStateException(this.toString());
                }
            }
            catch (Throwable failure)
            {
                if ("org.eclipse.jetty.continuation.ContinuationThrowable".equals(failure.getClass().getName()))
                    LOG.trace("IGNORED", failure);
                else
                    handleException(failure);
            }

            action = _state.unhandle();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("!handle {} {}", action, this);

        boolean suspended = action == Action.WAIT;
        return !suspended;
    }

    public void ensureConsumeAllOrNotPersistent()
    {
        switch (_request.getHttpVersion())
        {
            case HTTP_1_0:
                if (_request.getHttpInput().consumeAll())
                    return;

                // Remove any keep-alive value in Connection headers
                _response.getHttpFields().computeField(HttpHeader.CONNECTION, (h, fields) ->
                {
                    if (fields == null || fields.isEmpty())
                        return null;
                    String v = fields.stream()
                        .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s)))
                        .collect(Collectors.joining(", "));
                    if (StringUtil.isEmpty(v))
                        return null;

                    return new HttpField(HttpHeader.CONNECTION, v);
                });
                break;

            case HTTP_1_1:
                if (_request.getHttpInput().consumeAll())
                    return;

                // Add close value to Connection headers
                _response.getHttpFields().computeField(HttpHeader.CONNECTION, (h, fields) ->
                {
                    if (fields == null || fields.isEmpty())
                        return HttpConnection.CONNECTION_CLOSE;

                    if (fields.stream().anyMatch(f -> f.contains(HttpHeaderValue.CLOSE.asString())))
                    {
                        if (fields.size() == 1)
                        {
                            HttpField f = fields.get(0);
                            if (HttpConnection.CONNECTION_CLOSE.equals(f))
                                return f;
                        }

                        return new HttpField(HttpHeader.CONNECTION, fields.stream()
                            .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s)))
                            .collect(Collectors.joining(", ")));
                    }

                    return new HttpField(HttpHeader.CONNECTION,
                        Stream.concat(fields.stream()
                        .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s))),
                        Stream.of(HttpHeaderValue.CLOSE.asString()))
                        .collect(Collectors.joining(", ")));
                });
                break;

            default:
                break;
        }
    }

    /**
     * @param message the error message.
     * @return true if we have sent an error, false if we have aborted.
     */
    public boolean sendErrorOrAbort(String message)
    {
        try
        {
            if (isCommitted())
            {
                abort(new IOException(message));
                return false;
            }

            _response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500, message);
            return true;
        }
        catch (Throwable x)
        {
            LOG.trace("IGNORED", x);
            abort(x);
        }
        return false;
    }

    private void dispatch(DispatcherType type, Dispatchable dispatchable) throws IOException, ServletException
    {
        try
        {
            _request.setHandled(false);
            _response.reopen();
            _request.setDispatcherType(type);
            _combinedListener.onBeforeDispatch(_request);
            dispatchable.dispatch();
        }
        catch (Throwable x)
        {
            _combinedListener.onDispatchFailure(_request, x);
            throw x;
        }
        finally
        {
            _combinedListener.onAfterDispatch(_request);
            _request.setDispatcherType(null);
        }
    }

    /**
     * <p>Sends an error 500, performing a special logic to detect whether the request is suspended,
     * to avoid concurrent writes from the application.</p>
     * <p>It may happen that the application suspends, and then throws an exception, while an application
     * spawned thread writes the response content; in such case, we attempt to commit the error directly
     * bypassing the {@link ErrorHandler} mechanisms and the response OutputStream.</p>
     *
     * @param failure the Throwable that caused the problem
     */
    protected void handleException(Throwable failure)
    {
        // Unwrap wrapping Jetty and Servlet exceptions.
        Throwable quiet = unwrap(failure, QuietException.class);
        Throwable noStack = unwrap(failure, BadMessageException.class, IOException.class, TimeoutException.class);

        if (quiet != null || !getServer().isRunning())
        {
            if (LOG.isDebugEnabled())
                LOG.debug(_request.getRequestURI(), failure);
        }
        else if (noStack != null)
        {
            // No stack trace unless there is debug turned on
            if (LOG.isDebugEnabled())
                LOG.warn("handleException {}", _request.getRequestURI(), failure);
            else
                LOG.warn("handleException {} {}", _request.getRequestURI(), noStack.toString());
        }
        else
        {
            LOG.warn(_request.getRequestURI(), failure);
        }

        if (isCommitted())
        {
            abort(failure);
        }
        else
        {
            try
            {
                _state.onError(failure);
            }
            catch (IllegalStateException e)
            {
                abort(failure);
            }
        }
    }

    /**
     * Unwrap failure causes to find target class
     *
     * @param failure The throwable to have its causes unwrapped
     * @param targets Exception classes that we should not unwrap
     * @return A target throwable or null
     */
    protected Throwable unwrap(Throwable failure, Class<?>... targets)
    {
        while (failure != null)
        {
            for (Class<?> x : targets)
            {
                if (x.isInstance(failure))
                    return failure;
            }
            failure = failure.getCause();
        }
        return null;
    }

    public void sendResponseAndComplete()
    {
        try
        {
            _request.setHandled(true);
            _state.completing();
            sendResponse(null, _response.getHttpOutput().getBuffer(), true, Callback.from(() -> _state.completed(null), _state::completed));
        }
        catch (Throwable x)
        {
            abort(x);
        }
    }

    public boolean isExpecting100Continue()
    {
        return false;
    }

    public boolean isExpecting102Processing()
    {
        return false;
    }

    @Override
    public String toString()
    {
        long timeStamp = _request.getTimeStamp();
        return String.format("%s@%x{s=%s,r=%s,c=%b/%b,a=%s,uri=%s,age=%d}",
            getClass().getSimpleName(),
            hashCode(),
            _state,
            _requests,
            isRequestCompleted(),
            isResponseCompleted(),
            _state.getState(),
            _request.getHttpURI(),
            timeStamp == 0 ? 0 : System.currentTimeMillis() - timeStamp);
    }

    public void onRequest(MetaData.Request request)
    {
        _requests.incrementAndGet();
        _request.setTimeStamp(System.currentTimeMillis());
        HttpFields.Mutable fields = _response.getHttpFields();
        if (_configuration.getSendDateHeader() && !fields.contains(HttpHeader.DATE))
            fields.put(_connector.getServer().getDateField());

        long idleTO = _configuration.getIdleTimeout();
        _oldIdleTimeout = getIdleTimeout();
        if (idleTO >= 0 && _oldIdleTimeout != idleTO)
            setIdleTimeout(idleTO);

        _request.setMetaData(request);

        _combinedListener.onRequestBegin(_request);

        if (LOG.isDebugEnabled())
            LOG.debug("REQUEST for {} on {}{}{} {} {}{}{}", request.getURIString(), this, System.lineSeparator(),
                request.getMethod(), request.getURIString(), request.getHttpVersion(), System.lineSeparator(),
                request.getFields());
    }

    public boolean onContent(HttpInput.Content content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onContent {} {}", this, content);
        _combinedListener.onRequestContent(_request, content.getByteBuffer());
        return false;
    }

    public boolean onContentComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onContentComplete {}", this);
        _combinedListener.onRequestContentEnd(_request);
        return false;
    }

    public void onTrailers(HttpFields trailers)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onTrailers {} {}", this, trailers);
        _request.setTrailerHttpFields(trailers);
        _combinedListener.onRequestTrailers(_request);
    }

    public boolean onRequestComplete()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onRequestComplete {}", this);
        boolean result = eof();
        _combinedListener.onRequestEnd(_request);
        return result;
    }

    /**
     * <p>Checks whether the processing of the request resulted in an upgrade,
     * and if so performs upgrade preparation steps <em>before</em> the upgrade
     * response is sent back to the client.</p>
     * <p>This avoids a race where the server is unprepared if the client sends
     * data immediately after having received the upgrade response.</p>
     * @return true if the channel is not complete and more processing is required,
     * typically because sendError has been called.
     */
    protected boolean checkAndPrepareUpgrade()
    {
        return false;
    }

    public void onCompleted()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onCompleted for {} written={}", getRequest().getRequestURI(), getBytesWritten());

        long idleTO = _configuration.getIdleTimeout();
        if (idleTO >= 0 && getIdleTimeout() != _oldIdleTimeout)
            setIdleTimeout(_oldIdleTimeout);

        _request.onCompleted();
        _combinedListener.onComplete(_request);
        _transport.onCompleted();
    }

    public void onBadMessage(BadMessageException failure)
    {
        int status = failure.getCode();
        String reason = failure.getReason();
        if (status < HttpStatus.BAD_REQUEST_400 || status > 599)
            failure = new BadMessageException(HttpStatus.BAD_REQUEST_400, reason, failure);

        _combinedListener.onRequestFailure(_request, failure);

        Action action;
        try
        {
            action = _state.handling();
        }
        catch (Throwable e)
        {
            // The bad message cannot be handled in the current state,
            // so rethrow, hopefully somebody will be able to handle.
            abort(e);
            throw failure;
        }

        try
        {
            if (action == Action.DISPATCH)
            {
                ByteBuffer content = null;
                HttpFields.Mutable fields = HttpFields.build();

                ErrorHandler handler = getServer().getBean(ErrorHandler.class);
                if (handler != null)
                    content = handler.badMessageError(status, reason, fields);

                sendResponse(new MetaData.Response(HttpVersion.HTTP_1_1, status, null, fields, BufferUtil.length(content)), content, true);
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to send bad message response", e);
        }
        finally
        {
            try
            {
                onCompleted();
            }
            catch (Throwable e)
            {
                LOG.debug("Unable to complete bad message", e);
                abort(e);
            }
        }
    }

    protected boolean sendResponse(MetaData.Response response, ByteBuffer content, boolean complete, final Callback callback)
    {
        boolean committing = _state.commitResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("sendResponse info={} content={} complete={} committing={} callback={}",
                response,
                BufferUtil.toDetailString(content),
                complete,
                committing,
                callback);

        if (committing)
        {
            // Let HttpChannel.Listeners modify the response before commit
            _combinedListener.onResponseBegin(_request);
            // We need an info to commit
            if (response == null)
                response = _response.newResponseMetaData();
            commit(response);
            _request.onResponseCommit();

            // wrap callback to process informational responses
            final int status = response.getStatus();
            final Callback committed = HttpStatus.isInformational(status)
                ? new Send1XXCallback(callback)
                : new SendCallback(callback, content, true, complete);

            // committing write
            _transport.send(_request.getMetaData(), response, content, complete, committed);
        }
        else if (response == null)
        {
            // This is a normal write
            _transport.send(_request.getMetaData(), null, content, complete, new SendCallback(callback, content, false, complete));
        }
        else
        {
            callback.failed(new IllegalStateException("committed"));
        }
        return committing;
    }

    public boolean sendResponse(MetaData.Response info, ByteBuffer content, boolean complete) throws IOException
    {
        try (Blocker blocker = _response.getHttpOutput().acquireWriteBlockingCallback())
        {
            boolean committing = sendResponse(info, content, complete, blocker);
            blocker.block();
            return committing;
        }
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to send response", failure);
            abort(failure);
            throw failure;
        }
    }

    protected void commit(MetaData.Response info)
    {
        _committedMetaData = info;
        if (LOG.isDebugEnabled())
            LOG.debug("COMMIT for {} on {}{}{} {} {}{}{}", getRequest().getRequestURI(), this, System.lineSeparator(),
                info.getStatus(), info.getReason(), info.getHttpVersion(), System.lineSeparator(),
                info.getFields());
    }

    public boolean isCommitted()
    {
        return _state.isResponseCommitted();
    }

    /**
     * @return True if the request lifecycle is completed
     */
    public boolean isRequestCompleted()
    {
        return _state.isCompleted();
    }

    /**
     * @return True if the response is completely written.
     */
    public boolean isResponseCompleted()
    {
        return _state.isResponseCompleted();
    }

    public boolean isPersistent()
    {
        return _endPoint.isOpen();
    }

    /**
     * <p>Non-Blocking write, committing the response if needed.</p>
     * Called as last link in HttpOutput.Filter chain
     *
     * @param content the content buffer to write
     * @param complete whether the content is complete for the response
     * @param callback Callback when complete or failed
     */
    @Override
    public void write(ByteBuffer content, boolean complete, Callback callback)
    {
        sendResponse(null, content, complete, callback);
    }

    @Override
    public void resetBuffer()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
    }

    @Override
    public HttpOutput.Interceptor getNextInterceptor()
    {
        return null;
    }

    protected void execute(Runnable task)
    {
        _executor.execute(task);
    }

    public Scheduler getScheduler()
    {
        return _connector.getScheduler();
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return getHttpConfiguration().isUseInputDirectByteBuffers();
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return getHttpConfiguration().isUseOutputDirectByteBuffers();
    }

    /**
     * If a write or similar operation to this channel fails,
     * then this method should be called.
     * <p>
     * The standard implementation calls {@link HttpTransport#abort(Throwable)}.
     *
     * @param failure the failure that caused the abort.
     */
    public void abort(Throwable failure)
    {
        if (_state.abortResponse())
        {
            _combinedListener.onResponseFailure(_request, failure);
            _transport.abort(failure);
        }
    }

    public boolean isTunnellingSupported()
    {
        return false;
    }

    public EndPoint getTunnellingEndPoint()
    {
        throw new UnsupportedOperationException("Tunnelling not supported");
    }

    private void notifyEvent1(Function<Listener, Consumer<Request>> function, Request request)
    {
        for (Listener listener : _transientListeners)
        {
            try
            {
                function.apply(listener).accept(request);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failure invoking listener {}", listener, x);
            }
        }
    }

    private void notifyEvent2(Function<Listener, BiConsumer<Request, ByteBuffer>> function, Request request, ByteBuffer content)
    {
        for (Listener listener : _transientListeners)
        {
            ByteBuffer view = content.slice();
            try
            {
                function.apply(listener).accept(request, view);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failure invoking listener {}", listener, x);
            }
        }
    }

    private void notifyEvent2(Function<Listener, BiConsumer<Request, Throwable>> function, Request request, Throwable failure)
    {
        for (Listener listener : _transientListeners)
        {
            try
            {
                function.apply(listener).accept(request, failure);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failure invoking listener {}", listener, x);
            }
        }
    }

    interface Dispatchable
    {
        void dispatch() throws IOException, ServletException;
    }

    /**
     * <p>Listener for {@link HttpChannel} events.</p>
     * <p>HttpChannel will emit events for the various phases it goes through while
     * processing an HTTP request and response.</p>
     * <p>Implementations of this interface may listen to those events to track
     * timing and/or other values such as request URI, etc.</p>
     * <p>The events parameters, especially the {@link Request} object, may be
     * in a transient state depending on the event, and not all properties/features
     * of the parameters may be available inside a listener method.</p>
     * <p>It is recommended that the event parameters are <em>not</em> acted upon
     * in the listener methods, or undefined behavior may result. For example, it
     * would be a bad idea to try to read some content from the
     * {@link javax.servlet.ServletInputStream} in listener methods. On the other
     * hand, it is legit to store request attributes in one listener method that
     * may be possibly retrieved in another listener method in a later event.</p>
     * <p>Listener methods are invoked synchronously from the thread that is
     * performing the request processing, and they should not call blocking code
     * (otherwise the request processing will be blocked as well).</p>
     * <p>Listener instances that are set as a bean on the {@link Connector} are
     * efficiently added to {@link HttpChannel}.  If additional listeners are added
     * using the deprecated {@link HttpChannel#addListener(Listener)}</p> method,
     * then an instance of {@link TransientListeners} must be added to the connector
     * in order for them to be invoked.
     */
    public interface Listener extends EventListener
    {
        /**
         * Invoked just after the HTTP request line and headers have been parsed.
         *
         * @param request the request object
         */
        default void onRequestBegin(Request request)
        {
        }

        /**
         * Invoked just before calling the application.
         *
         * @param request the request object
         */
        default void onBeforeDispatch(Request request)
        {
        }

        /**
         * Invoked when the application threw an exception.
         *
         * @param request the request object
         * @param failure the exception thrown by the application
         */
        default void onDispatchFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just after the application returns from the first invocation.
         *
         * @param request the request object
         */
        default void onAfterDispatch(Request request)
        {
        }

        /**
         * Invoked every time a request content chunk has been parsed, just before
         * making it available to the application.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the request content chunk
         */
        default void onRequestContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the end of the request content is detected.
         *
         * @param request the request object
         */
        default void onRequestContentEnd(Request request)
        {
        }

        /**
         * Invoked when the request trailers have been parsed.
         *
         * @param request the request object
         */
        default void onRequestTrailers(Request request)
        {
        }

        /**
         * Invoked when the request has been fully parsed.
         *
         * @param request the request object
         */
        default void onRequestEnd(Request request)
        {
        }

        /**
         * Invoked when the request processing failed.
         *
         * @param request the request object
         * @param failure the request failure
         */
        default void onRequestFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just before the response line is written to the network.
         *
         * @param request the request object
         */
        default void onResponseBegin(Request request)
        {
        }

        /**
         * Invoked just after the response is committed (that is, the response
         * line, headers and possibly some content have been written to the
         * network).
         *
         * @param request the request object
         */
        default void onResponseCommit(Request request)
        {
        }

        /**
         * Invoked after a response content chunk has been written to the network.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the response content chunk
         */
        default void onResponseContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the response has been fully written.
         *
         * @param request the request object
         */
        default void onResponseEnd(Request request)
        {
        }

        /**
         * Invoked when the response processing failed.
         *
         * @param request the request object
         * @param failure the response failure
         */
        default void onResponseFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked when the request <em>and</em> response processing are complete.
         *
         * @param request the request object
         */
        default void onComplete(Request request)
        {
        }
    }

    private class SendCallback extends Callback.Nested
    {
        private final ByteBuffer _content;
        private final int _length;
        private final boolean _commit;
        private final boolean _complete;

        private SendCallback(Callback callback, ByteBuffer content, boolean commit, boolean complete)
        {
            super(callback);
            _content = content == null ? BufferUtil.EMPTY_BUFFER : content.slice();
            _length = _content.remaining();
            _commit = commit;
            _complete = complete;
        }

        @Override
        public void succeeded()
        {
            _written += _length;
            if (_commit)
                _combinedListener.onResponseCommit(_request);
            if (_length > 0)
                _combinedListener.onResponseContent(_request, _content);
            if (_complete && _state.completeResponse())
                _combinedListener.onResponseEnd(_request);
            super.succeeded();
        }

        @Override
        public void failed(final Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Commit failed", x);

            if (x instanceof BadMessageException)
            {
                _transport.send(_request.getMetaData(), HttpGenerator.RESPONSE_500_INFO, null, true, new Callback.Nested(this)
                {
                    @Override
                    public void succeeded()
                    {
                        _response.getHttpOutput().completed(null);
                        super.failed(x);
                    }

                    @Override
                    public void failed(Throwable th)
                    {
                        abort(x);
                        super.failed(x);
                    }
                });
            }
            else
            {
                abort(x);
                super.failed(x);
            }
        }
    }

    private class Send1XXCallback extends SendCallback
    {
        private Send1XXCallback(Callback callback)
        {
            super(callback, null, false, false);
        }

        @Override
        public void succeeded()
        {
            if (_state.partialResponse())
                super.succeeded();
            else
                super.failed(new IllegalStateException());
        }
    }

    /**
     * A Listener instance that can be added as a bean to {@link AbstractConnector} so that
     * the listeners obtained from HttpChannel{@link #getTransientListeners()}
     */
    @Deprecated
    public static class TransientListeners implements Listener
    {
        @Override
        public void onRequestBegin(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onRequestBegin, request);
        }

        @Override
        public void onBeforeDispatch(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onBeforeDispatch, request);
        }

        @Override
        public void onDispatchFailure(Request request, Throwable failure)
        {
            request.getHttpChannel().notifyEvent2(listener -> listener::onDispatchFailure, request, failure);
        }

        @Override
        public void onAfterDispatch(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onAfterDispatch, request);
        }

        @Override
        public void onRequestContent(Request request, ByteBuffer content)
        {
            request.getHttpChannel().notifyEvent2(listener -> listener::onRequestContent, request, content);
        }

        @Override
        public void onRequestContentEnd(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onRequestContentEnd, request);
        }

        @Override
        public void onRequestTrailers(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onRequestTrailers, request);
        }

        @Override
        public void onRequestEnd(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onRequestEnd, request);
        }

        @Override
        public void onRequestFailure(Request request, Throwable failure)
        {
            request.getHttpChannel().notifyEvent2(listener -> listener::onRequestFailure, request, failure);
        }

        @Override
        public void onResponseBegin(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onResponseBegin, request);
        }

        @Override
        public void onResponseCommit(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onResponseCommit, request);
        }

        @Override
        public void onResponseContent(Request request, ByteBuffer content)
        {
            request.getHttpChannel().notifyEvent2(listener -> listener::onResponseContent, request, content);
        }

        @Override
        public void onResponseEnd(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onResponseEnd, request);
        }

        @Override
        public void onResponseFailure(Request request, Throwable failure)
        {
            request.getHttpChannel().notifyEvent2(listener -> listener::onResponseFailure, request, failure);
        }

        @Override
        public void onComplete(Request request)
        {
            request.getHttpChannel().notifyEvent1(listener -> listener::onComplete, request);
        }
    }

    private class RequestDispatchable implements Dispatchable
    {
        @Override
        public void dispatch() throws IOException, ServletException
        {
            for (HttpConfiguration.Customizer customizer : _configuration.getCustomizers())
            {
                customizer.customize(getConnector(), _configuration, _request);
                if (_request.isHandled())
                    return;
            }
            getServer().handle(HttpChannel.this);
        }
    }

    private class AsyncDispatchable implements Dispatchable
    {
        @Override
        public void dispatch() throws IOException, ServletException
        {
            getServer().handleAsync(HttpChannel.this);
        }
    }

    private class ErrorDispatchable implements Dispatchable
    {
        private final ErrorHandler _errorHandler;

        public ErrorDispatchable(ErrorHandler errorHandler)
        {
            _errorHandler = errorHandler;
        }

        @Override
        public void dispatch() throws IOException, ServletException
        {
            _errorHandler.handle(null, _request, _request, _response);
            _request.setHandled(true);
        }
    }
}

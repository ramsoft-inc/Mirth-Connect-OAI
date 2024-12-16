/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.connectors.js;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.tools.debugger.MirthMain;

import com.mirth.connect.donkey.model.channel.DebugOptions;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.BatchRawMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.ChannelException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.PollConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.donkey.server.message.batch.BatchMessageException;
import com.mirth.connect.donkey.server.message.batch.BatchMessageReader;

import com.mirth.connect.model.codetemplates.ContextType;
import com.mirth.connect.server.MirthScopeProvider;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ContextFactoryController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.util.CompiledScriptCache;
import com.mirth.connect.server.util.javascript.JavaScriptExecutorException;
import com.mirth.connect.server.util.javascript.JavaScriptScopeUtil;
import com.mirth.connect.server.util.javascript.JavaScriptTask;
import com.mirth.connect.server.util.javascript.JavaScriptUtil;
import com.mirth.connect.server.util.javascript.MirthContextFactory;

public class JavaScriptReceiver extends PollConnector {
    private Logger logger = LogManager.getLogger(getClass());

    private EventController eventController = getEventController();
    private ContextFactoryController contextFactoryController = getContextFactoryController();
    private JavaScriptReceiverProperties connectorProperties;
    private String scriptId;
    private String contextFactoryId;
    private boolean debug = false;
    private MirthMain debugger;
    private MirthScopeProvider scopeProvider = new MirthScopeProvider();
    private boolean ignoreBreakpoints = false;

    protected EventController getEventController() {
        return ControllerFactory.getFactory().createEventController();
    }

    protected ScriptController getScriptController() {
        return ControllerFactory.getFactory().createScriptController();
    }

    @Override
    public void onDeploy() throws ConnectorTaskException {
        this.onDeploy(null);

    }

    @Override
    public void onDebugDeploy(DebugOptions debugOptions) throws ConnectorTaskException {
        onDeploy(debugOptions);
    }

    public void onDeploy(DebugOptions debugOptions) throws ConnectorTaskException {
        this.connectorProperties = (JavaScriptReceiverProperties) getConnectorProperties();
        scriptId = ScriptController.getScriptId("JavaScript_Reader", getChannelId());

        try {
            MirthContextFactory contextFactory;
            this.debug = debugOptions != null && debugOptions.isSourceConnectorScripts();

            if (debug) {
                contextFactory = contextFactoryController.getDebugContextFactory(getResourceIds(), getChannelId(), scriptId);
                contextFactory.setContextType(ContextType.SOURCE_RECEIVER);
                contextFactory.setScriptText(connectorProperties.getScript());
                contextFactory.setDebugType(true);
                debugger = getDebugger(contextFactory);

            } else {
                //default case
                contextFactory = contextFactoryController.getContextFactory(getResourceIds());

            }

            contextFactoryId = contextFactory.getId();
            compileAndAddScript(contextFactory, scriptId);

        } catch (Exception e) {
            throw new ConnectorTaskException("Error compiling " + connectorProperties.getName() + " script " + scriptId + ".", e);
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
        JavaScriptUtil.removeScriptFromCache(scriptId);

        if (debug && debugger != null) {
            contextFactoryController.removeDebugContextFactory(getResourceIds(), getChannelId(), scriptId);
            debugger.dispose();
            debugger = null;
        }
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        ignoreBreakpoints = false;
        if (debug && debugger != null) {
            debugger.enableDebugging();
        }
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        if (debug && debugger != null) {
            debugger.finishScriptExecution();
        }
    }

    @Override
    public void onHalt() throws ConnectorTaskException {}

    @Override
    public void handleRecoveredResponse(DispatchResult dispatchResult) {
        finishDispatch(dispatchResult);
    }

    @Override
    public void poll() throws InterruptedException {
        Object result = null;
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.READING));

        try {
            MirthContextFactory contextFactory = debug ? contextFactoryController.getDebugContextFactory(getResourceIds(), getChannelId(), scriptId) : contextFactoryController.getContextFactory(getResourceIds());
            if (!contextFactoryId.equals(contextFactory.getId())) {
                JavaScriptUtil.recompileGeneratedScript(contextFactory, scriptId);
                contextFactoryId = contextFactory.getId();
            }

            result = JavaScriptUtil.execute(new JavaScriptReceiverTask(contextFactory));

            for (RawMessage rawMessage : convertJavaScriptResult(result)) {
                if (isTerminated()) {
                    return;
                }

                if (isProcessBatch()) {
                    if (rawMessage.isBinary()) {
                        throw new BatchMessageException("Batch processing is not supported for binary data in channel " + getChannelId());
                    } else {
                        BatchRawMessage batchRawMessage = new BatchRawMessage(new BatchMessageReader(rawMessage.getRawData()), rawMessage.getSourceMap());

                        // Clean up the reference to the raw message so it doesn't hold the contents in memory
                        rawMessage = null;

                        dispatchBatchMessage(batchRawMessage, null);
                    }
                } else {
                    DispatchResult dispatchResult = null;

                    try {
                        dispatchResult = dispatchRawMessage(rawMessage);
                    } catch (ChannelException e) {
                        // Do nothing. An error should have been logged.
                    } finally {
                        finishDispatch(dispatchResult);
                    }
                }
            }

        } catch (JavaScriptExecutorException e) {
            String errorMessage = "Error executing " + connectorProperties.getName() + " script " + scriptId + ".";
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null, ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(), errorMessage, e));
            logger.error(errorMessage, e);
        } catch (InterruptedException e) {
            throw e;
        } catch (BatchMessageException e) {
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null, ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(), "Error processing batch message", e));
            logger.error(e.getMessage(), e);
        } catch (Throwable t) {
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null, ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(), null, t));
            logger.error("Error polling in channel: " + getChannelId(), t);
        } finally {
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(), ConnectionStatusEventType.IDLE));
        }
    }

    protected MirthMain getDebugger(MirthContextFactory contextFactory) {
        return MirthMain.mirthMainEmbedded(contextFactory, scopeProvider, getChannel().getName() + "-" + getChannelId(), scriptId);
    }

    private class JavaScriptReceiverTask extends JavaScriptTask<Object> {
        public JavaScriptReceiverTask(MirthContextFactory contextFactory) {
            super(contextFactory, JavaScriptReceiver.this);
        }

        @Override
        public Object doCall() throws Exception {
            try {
                Scriptable scope = JavaScriptScopeUtil.getMessageReceiverScope(getContextFactory(), LogManager.getLogger("js-connector"), getChannelId(), getChannel().getName());

                if (debug) {
                    scopeProvider.setScope(scope);

                    if (debugger != null && !ignoreBreakpoints) {
                        debugger.doBreak();

                        if (!debugger.isVisible()) {
                            debugger.setVisible(true);
                        }
                    }
                }

                return JavaScriptUtil.executeScript(this, scriptId, scope, getChannelId(), "Source");
            } finally {
                Context.exit();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<RawMessage> convertJavaScriptResult(Object result) {
        List<RawMessage> messages = new ArrayList<RawMessage>();

        if (result instanceof NativeJavaObject) {
            Object object = ((NativeJavaObject) result).unwrap();

            if (object instanceof List<?>) {
                // Allow the user to pass in RawMessage objects as well as strings
                for (Object element : (List<Object>) object) {
                    if (element instanceof com.mirth.connect.server.userutil.RawMessage) {
                        messages.add(convertRawMessage(element));
                    } else {
                        String rawData = element.toString();
                        if (StringUtils.isNotEmpty(rawData)) {
                            messages.add(new RawMessage(rawData));
                        }
                    }
                }
            } else if (object instanceof com.mirth.connect.server.userutil.RawMessage) {
                messages.add(convertRawMessage(object));
            } else {
                // Assume it's a string
                String rawData = object.toString();
                if (StringUtils.isNotEmpty(rawData)) {
                    messages.add(new RawMessage(rawData));
                }
            }
        } else if (result != null && !(result instanceof Undefined)) {
            // This branch will catch all objects that aren't NativeJavaObject, Undefined, or null
            // Assume it's a string
            String rawData = result.toString();
            if (StringUtils.isNotEmpty(rawData)) {
                messages.add(new RawMessage(rawData));
            }
        }

        return messages;
    }

    protected ContextFactoryController getContextFactoryController() {
        return ControllerFactory.getFactory().createContextFactoryController();
    }

    protected ChannelController getChannelController() {
        return ControllerFactory.getFactory().createChannelController();
    }

    private RawMessage convertRawMessage(Object object) {
        com.mirth.connect.server.userutil.RawMessage rawMessage = (com.mirth.connect.server.userutil.RawMessage) object;
        if (rawMessage.isBinary()) {
            return new RawMessage(rawMessage.getRawBytes(), rawMessage.getDestinationMetaDataIds(), rawMessage.getSourceMap());
        } else {
            return new RawMessage(rawMessage.getRawData(), rawMessage.getDestinationMetaDataIds(), rawMessage.getSourceMap());
        }
    }

    protected void compileAndAddScript(MirthContextFactory contextFactory, String scriptId) throws Exception {
        JavaScriptUtil.compileAndAddScript(getChannelId(), contextFactory, scriptId, connectorProperties.getScript(), ContextType.SOURCE_RECEIVER, null, null);

    }

    protected CompiledScriptCache getCompiledScriptCache() {
        return CompiledScriptCache.getInstance();
    }

}

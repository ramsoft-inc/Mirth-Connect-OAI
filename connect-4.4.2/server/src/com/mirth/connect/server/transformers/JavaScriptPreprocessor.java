/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.server.transformers;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.tools.debugger.MirthMain;

import com.mirth.connect.donkey.model.DonkeyException;
import com.mirth.connect.donkey.model.channel.DebugOptions;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.components.PreProcessor;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.model.codetemplates.ContextType;
import com.mirth.connect.server.MirthJavascriptTransformerException;
import com.mirth.connect.server.MirthScopeProvider;
import com.mirth.connect.server.controllers.ContextFactoryController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.util.javascript.JavaScriptExecutorException;
import com.mirth.connect.server.util.javascript.JavaScriptTask;
import com.mirth.connect.server.util.javascript.JavaScriptUtil;
import com.mirth.connect.server.util.javascript.MirthContextFactory;
import com.mirth.connect.util.ErrorMessageBuilder;

public class JavaScriptPreprocessor implements PreProcessor {

    private Logger logger = LogManager.getLogger(getClass());
    private EventController eventController = ControllerFactory.getFactory().createEventController();
    private ContextFactoryController contextFactoryController = ControllerFactory.getFactory().createContextFactoryController();

    private Channel channel;
    private String scriptId;
    private volatile String contextFactoryId;
    private MirthScopeProvider scopeProvider = new MirthScopeProvider();
    private MirthMain debugger;
    private String preProcessingScript;
    private Boolean debug = false;
    public JavaScriptPreprocessor(Channel channel, String preProcessingScript, DebugOptions debugOptions) throws JavaScriptInitializationException {
        this.channel = channel;
        this.preProcessingScript = preProcessingScript;
        
        scriptId = ScriptController.getScriptId(ScriptController.PREPROCESSOR_SCRIPT_KEY, channel.getChannelId());
        this.debug = debugOptions != null && debugOptions.isDeployUndeployPreAndPostProcessorScripts();
        
        if (!debug) {
            try {
                MirthContextFactory contextFactory = contextFactoryController.getContextFactory(channel.getResourceIds());
                contextFactoryId = contextFactory.getId();
                JavaScriptUtil.compileAndAddScript(channel.getChannelId(), contextFactory, scriptId, preProcessingScript, ContextType.CHANNEL_PREPROCESSOR);
            } catch (Exception e) {
                logger.error("Error compiling preprocessor script " + scriptId + ".", e);
    
                if (e instanceof RhinoException) {
                    e = new MirthJavascriptTransformerException((RhinoException) e, channel.getChannelId(), null, 0, ErrorEventType.PREPROCESSOR_SCRIPT.toString(), null);
                }
    
                logger.error(ErrorMessageBuilder.buildErrorMessage(ErrorEventType.PREPROCESSOR_SCRIPT.toString(), null, e));
                throw new JavaScriptInitializationException("Error initializing JavaScript Preprocessor", e);
            }
        }
        
        
    }

    @Override
    public String doPreProcess(ConnectorMessage message) throws DonkeyException, InterruptedException {
        try {
            MirthContextFactory contextFactory;

            try {
                Map<String, MirthContextFactory> contextFactories = new HashMap<>();
                if (debug) {
                    String preProcessingScriptId = ScriptController.getScriptId(ScriptController.PREPROCESSOR_SCRIPT_KEY, channel.getChannelId());
                    contextFactory = getContextFactory();
                    contextFactoryId = contextFactory.getId();
                    contextFactory.setContextType(ContextType.CHANNEL_PREPROCESSOR);
                    contextFactory.setScriptText(preProcessingScript);
                    contextFactory.setDebugType(true);
                    contextFactories.put(preProcessingScriptId, contextFactory);
                    if (JavaScriptUtil.getCompiledScript(scriptId) != null) {
                    	debugger = JavaScriptUtil.getDebugger(contextFactory, scopeProvider, channel, scriptId, true);
                    }
                } else {
                    contextFactory = getContextFactory();
                    if (!contextFactoryId.equals(contextFactory.getId())) {
                        synchronized (this) {
                            contextFactory = getContextFactory();
                            if (!contextFactoryId.equals(contextFactory.getId())) {
                                JavaScriptUtil.recompileGeneratedScript(contextFactory, scriptId);
                                contextFactoryId = contextFactory.getId();
                            }
                        }
                    }
                }
                
                
                
            } catch (Exception e) {
                logger.error("Error compiling preprocessor script " + scriptId + ".", e);
    
                if (e instanceof RhinoException) {
                    e = new MirthJavascriptTransformerException((RhinoException) e, channel.getChannelId(), null, 0, ErrorEventType.POSTPROCESSOR_SCRIPT.toString(), null);
                }
    
                logger.error(ErrorMessageBuilder.buildErrorMessage(ErrorEventType.PREPROCESSOR_SCRIPT.toString(), null, e));
                throw new JavaScriptInitializationException("Error initializing JavaScript Preprocessor", e);
            }

            return JavaScriptUtil.executeJavaScriptPreProcessorTask(new JavaScriptPreProcessorTask(contextFactory, message), message.getChannelId());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Throwable t = e;
            if (e instanceof JavaScriptExecutorException) {
                t = e.getCause();
            }

            eventController.dispatchEvent(new ErrorEvent(message.getChannelId(), null, message.getMessageId(), ErrorEventType.PREPROCESSOR_SCRIPT, null, null, "Error running preprocessor scripts", t));
            throw new DonkeyException(t, ErrorMessageBuilder.buildErrorMessage(ErrorEventType.PREPROCESSOR_SCRIPT.toString(), "Error running preprocessor scripts", t));
        }
    }

    protected MirthContextFactory getContextFactory() throws Exception {
        if (debug) {
            return contextFactoryController.getDebugContextFactory(channel.getResourceIds(), channel.getChannelId(), scriptId);
        } else {
            return contextFactoryController.getContextFactory(channel.getResourceIds());
        }
    }
   
    private class JavaScriptPreProcessorTask extends JavaScriptTask<Object> {

        private ConnectorMessage message;;
        private MirthScopeProvider scopeProvider;
        
        public JavaScriptPreProcessorTask(MirthContextFactory contextFactory, ConnectorMessage message) {
            super(contextFactory, "Preprocessor", channel.getChannelId(), channel.getName());
            this.message = message;
            this.scopeProvider = null;
        }
        
        public JavaScriptPreProcessorTask(MirthContextFactory contextFactory, ConnectorMessage message, MirthScopeProvider scopeProvider) {
            super(contextFactory, "Preprocessor", channel.getChannelId(), channel.getName());
            this.message = message;
            this.scopeProvider = scopeProvider;
        }

        @Override
        public Object doCall() throws Exception {
            if (debug && debugger != null) {
                debugger.doBreak();

                if (!debugger.isVisible()) {
                    debugger.setVisible(true);
                }
            }
            return JavaScriptUtil.executePreprocessorScripts(this, message, channel.getSourceConnector().getDestinationIdMap(), this.scopeProvider);
        }
    }
}

/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Rhino JavaScript Debugger code, released
 * November 21, 2000.
 *
 * The Initial Developer of the Original Code is
 * SeeBeyond Corporation.
 * Portions created by the Initial Developer are Copyright (C) 2000
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Igor Bukanov
 *   Matt Gould
 *   Christopher Oliver
 *   Cameron McCormack
 *
 * Alternatively, the contents of this file may be used under the terms of
 * the GNU General Public License Version 2 or later (the "GPL"), in which
 * case the provisions of the GPL are applicable instead of those above. If
 * you wish to allow use of your version of this file only under the terms of
 * the GPL and not to allow others to use your version of this file under the
 * MPL, indicate your decision by deleting the provisions above and replacing
 * them with the notice and other provisions required by the GPL. If you do
 * not delete the provisions above, a recipient may use your version of this
 * file under either the MPL or the GPL.
 *
 * ***** END LICENSE BLOCK ***** */
package org.mozilla.javascript.tools.debugger;

import org.mozilla.javascript.*;
import org.mozilla.javascript.debug.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.*;
import java.net.URL;

/**
 * Dim or Debugger Implementation for Rhino.
 */
public class Dim {

    // Constants for instructing the debugger what action to perform
    // to end interruption.  Used by 'returnValue'.
    public static final int STEP_OVER = 0;
    public static final int STEP_INTO = 1;
    public static final int STEP_OUT = 2;
    public static final int GO = 3;
    public static final int BREAK = 4;
    public static final int EXIT = 5;

    // Constants for the DimIProxy interface implementation class.
    private static final int IPROXY_DEBUG = 0;
    private static final int IPROXY_LISTEN = 1;
    private static final int IPROXY_COMPILE_SCRIPT = 2;
    private static final int IPROXY_EVAL_SCRIPT = 3;
    private static final int IPROXY_STRING_IS_COMPILABLE = 4;
    private static final int IPROXY_OBJECT_TO_STRING = 5;
    private static final int IPROXY_OBJECT_PROPERTY = 6;
    private static final int IPROXY_OBJECT_IDS = 7;

    /**
     * Interface to the debugger GUI.
     */
    private GuiCallback callback;

    /**
     * Whether the debugger should break.
     */
    private boolean breakFlag;

    /**
     * A list of contexts to break at the next opportunity
     */
    private ArrayList<Long> breakThreadIds = new ArrayList<Long>();

    /**
     * The ScopeProvider object that provides the scope in which to
     * evaluate script.
     */
    private ScopeProvider scopeProvider;

    /**
     * The SourceProvider object that provides the source of evaluated scripts.
     */
    private SourceProvider sourceProvider;

    /**
     * The index of the current stack frame.
     */
    private int frameIndex = -1;

    /**
     * Information about the current stack at the point of interruption.
     */
    private volatile ContextData interruptedContextData;

    /**
     * The ContextFactory to listen to for debugging information.
     */
    private ContextFactory contextFactory;

    /**
     * Synchronization object used to allow script evaluations to
     * happen when a thread is resumed.
     */
    private Object monitor = new Object();

    /**
     * Synchronization object used to wait for valid
     * {@link #interruptedContextData}.
     */
    private Object eventThreadMonitor = new Object();

    /**
     * The action to perform to end the interruption loop.
     */
    private volatile int returnValue = -1;

    /**
     * Whether the debugger is inside the interruption loop.
     */
    private boolean insideInterruptLoop;

    /**
     * The requested script string to be evaluated when the thread
     * has been resumed.
     */
    private String evalRequest;

    /**
     * The scope that evalRequest will be evaluated in
     */
    private Scriptable evalScope;

    /**
     * The "this" object that evalRequest will be evaluated with
     */
    private Scriptable evalThisObj;

    /**
     * The result of evaluating {@link #evalRequest}.
     */
    private Object evalResult;

    /**
     * Whether the debugger should break when a script exception is thrown.
     */
    private boolean breakOnExceptions;

    /**
     * A list of exception types to break on
     */
    private ArrayList<String> exceptionBreakpoints = new ArrayList<String>();

    /**
     * Whether the debugger should break when a script function is entered.
     */
    private boolean breakOnEnter;

    /**
     * Whether the debugger should break when a script function is returned
     * from.
     */
    private boolean breakOnReturn;

    /**
     * Table mapping URLs to information about the script source.
     */
    private final Map<String,SourceInfo> urlToSourceInfo = 
        Collections.synchronizedMap(new HashMap<String,SourceInfo>());

    /**
     * Table mapping function names to information about the function.
     */
    private final Map<String,FunctionSource> functionNames =
        Collections.synchronizedMap(new HashMap<String,FunctionSource>());

    /**
     * Table mapping functions to information about the function.
     */
    private final Map<DebuggableScript,FunctionSource> functionToSource =
        Collections.synchronizedMap(new HashMap<DebuggableScript,FunctionSource>());

    /**
     * ContextFactory.Listener instance attached to {@link #contextFactory}.
     */
    private DimIProxy listener;

    /**
     * A logger that can be set to see debug statements
     */
    private Logger logger = null;

    /**
     * Sets the GuiCallback object to use.
     */
    public void setGuiCallback(GuiCallback callback) {
        this.callback = callback;
    }

    /**
     * Tells the debugger to break at the next opportunity.
     */
    public void setBreak() {
        this.breakFlag = true;
    }

    /**
     * Tells the debugger to break at the next opportunity for the given thread ID.
     */
    public void setBreakThread(long threadId) {
        synchronized (breakThreadIds) {
            if (!breakThreadIds.contains(threadId)) {
                if (logger != null) {
                    logger.fine("break when thread enters again: " + threadId);
                }
                breakThreadIds.add(threadId);
            }
        }
        if (logger != null) {
            logger.fine("current break thread ids: " + breakThreadIds);
        }
    }

    /**
     * Sets the ScopeProvider to be used.
     */
    public void setScopeProvider(ScopeProvider scopeProvider) {
        this.scopeProvider = scopeProvider;
    }

    /**
     * Sets the ScopeProvider to be used.
     */
    public void setSourceProvider(final SourceProvider sourceProvider) {
        this.sourceProvider = sourceProvider;
    }

    /**
     * Switches context to the stack frame with the given index.
     */
    public void contextSwitch(int frameIndex) {
        this.frameIndex = frameIndex;
    }

    /**
     * Sets whether the debugger should break on exceptions.
     */
    public void setBreakOnExceptions(boolean breakOnExceptions) {
        this.breakOnExceptions = breakOnExceptions;
    }

    /**
     * Sets whether the debugger should break on function entering.
     */
    public void setBreakOnEnter(boolean breakOnEnter) {
        this.breakOnEnter = breakOnEnter;
    }

    /**
     * Sets whether the debugger should break on function return.
     */
    public void setBreakOnReturn(boolean breakOnReturn) {
        this.breakOnReturn = breakOnReturn;
    }

    /**
     * Sets the debugger that should log debug statements.
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Attaches the debugger to the given ContextFactory.
     */
    public void attachTo(ContextFactory factory) {
        detach();
        this.contextFactory = factory;
        this.listener = new DimIProxy(this, IPROXY_LISTEN);
        factory.addListener(this.listener);
    }

    /**
     * Attaches the debugger to an existing Context
     */
    public void attachTo(Context context) {
        ContextData contextData = new ContextData();
        Debugger debugger = new DimIProxy(this, IPROXY_DEBUG);
        context.setDebugger(debugger, contextData);
        context.setGeneratingDebug(true);
        context.setOptimizationLevel(-1);
    }

    /**
     * Detaches the debugger from the current ContextFactory.
     */
    public void detach() {
        if (listener != null) {
            contextFactory.removeListener(listener);
            contextFactory = null;
            listener = null;
        }
    }

    /**
     * Releases resources associated with this debugger.
     */
    public void dispose() {
        detach();
    }

    /**
     * Returns the FunctionSource object for the given script or function.
     */
    private FunctionSource getFunctionSource(DebuggableScript fnOrScript) {
        FunctionSource fsource = functionSource(fnOrScript);
        if (fsource == null) {
            String url = getNormalizedUrl(fnOrScript);
            SourceInfo si = sourceInfo(url);
            if (si == null) {
                if (!fnOrScript.isGeneratedScript()) {
                    // Not eval or Function, try to load it from URL
                    String source = loadSource(url);
                    if (source != null) {
                        DebuggableScript top = fnOrScript;
                        for (;;) {
                            DebuggableScript parent = top.getParent();
                            if (parent == null) {
                                break;
                            }
                            top = parent;
                        }
                        registerTopScript(top, source);
                        fsource = functionSource(fnOrScript);
                    }
                }
            }
        }
        return fsource;
    }

    /**
     * Loads the script at the given URL.
     */
    private String loadSource(String sourceUrl) {
        String source = null;
        int hash = sourceUrl.indexOf('#');
        if (hash >= 0) {
            sourceUrl = sourceUrl.substring(0, hash);
        }
        try {
            InputStream is;
          openStream:
            {
                if (sourceUrl.indexOf(':') < 0) {
                    // Can be a file name
                    try {
                        if (sourceUrl.startsWith("~/")) {
                            String home = SecurityUtilities.getSystemProperty("user.home");
                            if (home != null) {
                                String pathFromHome = sourceUrl.substring(2);
                                File f = new File(new File(home), pathFromHome);
                                if (f.exists()) {
                                    is = new FileInputStream(f);
                                    break openStream;
                                }
                            }
                        }
                        File f = new File(sourceUrl);
                        if (f.exists()) {
                            is = new FileInputStream(f);
                            break openStream;
                        }
                    } catch (SecurityException ex) { }
                    // No existing file, assume missed http://
                    if (sourceUrl.startsWith("//")) {
                        sourceUrl = "http:" + sourceUrl;
                    } else if (sourceUrl.startsWith("/")) {
                        sourceUrl = "http://127.0.0.1" + sourceUrl;
                    } else {
                        sourceUrl = "http://" + sourceUrl;
                    }
                }

                is = (new URL(sourceUrl)).openStream();
            }

            try {
                source = Kit.readReader(new InputStreamReader(is));
            } finally {
                is.close();
            }
        } catch (IOException ex) {
            System.err.println
                ("Failed to load source from "+sourceUrl+": "+ ex);
        }
        return source;
    }

    /**
     * Registers the given script as a top-level script in the debugger.
     */
    private void registerTopScript(DebuggableScript topScript, String source) {
        if (!topScript.isTopLevel()) {
            throw new IllegalArgumentException();
        }
        String url = getNormalizedUrl(topScript);
        DebuggableScript[] functions = getAllFunctions(topScript);
        if (sourceProvider != null) {
            final String providedSource = sourceProvider.getSource(topScript); 
            if(providedSource != null) {
                source = providedSource; 
            }
        }

        final SourceInfo sourceInfo = new SourceInfo(source, functions, url);

        synchronized (urlToSourceInfo) {
            SourceInfo old = urlToSourceInfo.get(url);
            if (old != null) {
                sourceInfo.copyBreakpointsFrom(old);
            }
            urlToSourceInfo.put(url, sourceInfo);
            for (int i = 0; i != sourceInfo.functionSourcesTop(); ++i) {
                FunctionSource fsource = sourceInfo.functionSource(i);
                String name = fsource.name();
                if (name.length() != 0) {
                    functionNames.put(name, fsource);
                }
            }
        }

        synchronized (functionToSource) {
            for (int i = 0; i != functions.length; ++i) {
                FunctionSource fsource = sourceInfo.functionSource(i);
                functionToSource.put(functions[i], fsource);
            }
        }

        callback.updateSourceText(sourceInfo);
    }

    /**
     * Returns the FunctionSource object for the given function or script.
     */
    private FunctionSource functionSource(DebuggableScript fnOrScript) {
        return functionToSource.get(fnOrScript);
    }

    /**
     * Returns an array of all function names.
     */
    public String[] functionNames() {
        synchronized (urlToSourceInfo) {
            return functionNames.keySet().toArray(new String[functionNames.size()]);
        }
    }

    /**
     * Returns the FunctionSource object for the function with the given name.
     */
    public FunctionSource functionSourceByName(String functionName) {
        return functionNames.get(functionName);
    }

    /**
     * Returns the SourceInfo object for the given URL.
     */
    public SourceInfo sourceInfo(String url) {
        return urlToSourceInfo.get(url);
    }

    /**
     * Returns the source URL for the given script or function.
     */
    private String getNormalizedUrl(DebuggableScript fnOrScript) {
        String url = fnOrScript.getSourceName();
        if (url == null) { url = "<stdin>"; }
        else {
            // Not to produce window for eval from different lines,
            // strip line numbers, i.e. replace all #[0-9]+\(eval\) by
            // (eval)
            // Option: similar teatment for Function?
            char evalSeparator = '#';
            StringBuffer sb = null;
            int urlLength = url.length();
            int cursor = 0;
            for (;;) {
                int searchStart = url.indexOf(evalSeparator, cursor);
                if (searchStart < 0) {
                    break;
                }
                String replace = null;
                int i = searchStart + 1;
                while (i != urlLength) {
                    int c = url.charAt(i);
                    if (!('0' <= c && c <= '9')) {
                        break;
                    }
                    ++i;
                }
                if (i != searchStart + 1) {
                    // i points after #[0-9]+
                    if ("(eval)".regionMatches(0, url, i, 6)) {
                        cursor = i + 6;
                        replace = "(eval)";
                    }
                }
                if (replace == null) {
                    break;
                }
                if (sb == null) {
                    sb = new StringBuffer();
                    sb.append(url.substring(0, searchStart));
                }
                sb.append(replace);
            }
            if (sb != null) {
                if (cursor != urlLength) {
                    sb.append(url.substring(cursor));
                }
                url = sb.toString();
            }
        }
        return url;
    }

    /**
     * Returns an array of all functions in the given script.
     */
    private static DebuggableScript[] getAllFunctions
            (DebuggableScript function) {
        ObjArray functions = new ObjArray();
        collectFunctions_r(function, functions);
        DebuggableScript[] result = new DebuggableScript[functions.size()];
        functions.toArray(result);
        return result;
    }

    /**
     * Helper function for {@link #getAllFunctions(DebuggableScript)}.
     */
    private static void collectFunctions_r(DebuggableScript function,
                                             ObjArray array) {
        array.add(function);
        for (int i = 0; i != function.getFunctionCount(); ++i) {
            collectFunctions_r(function.getFunction(i), array);
        }
    }

    /**
     * Clears all breakpoints.
     */
    public void clearAllBreakpoints() {
        for (SourceInfo si: urlToSourceInfo.values()) {
            si.removeAllBreakpoints();
        }
    }

    /**
     * Called when a breakpoint has been hit.
     */
    private void handleBreakpointHit(StackFrame frame, Context cx) {
        breakFlag = false;
        interrupted(cx, frame, null);
    }

    private void handleBreakpointConditionException(StackFrame frame, Context cx, Throwable ex) {
        breakFlag = false;
        ContextData cd = frame.contextData();
        cd.lastProcessedException = ex;
        interrupted(cx, frame, ex);
    }

    /**
     * Called when a script exception has been thrown.
     */
    private void handleExceptionThrown(Context cx, Throwable ex,
                                         StackFrame frame) {
        ContextData cd = frame.contextData();
        if (breakOnExceptions || shouldBreakOnException(ex)) {
            if (cd.lastProcessedException != ex) {
                interrupted(cx, frame, ex);
                cd.lastProcessedException = ex;
            }
        }
    }

    public void addExceptionBreakpoint(String exceptionType) {
        exceptionBreakpoints.add(exceptionType);
    }

    public boolean shouldBreakOnException(Throwable exception) {
        if (exception instanceof JavaScriptException) {
            JavaScriptException jsException = (JavaScriptException) exception;
            Object value = jsException.getValue();
            if (value instanceof Scriptable) {
                Scriptable error = (Scriptable) value;
                Object name = error.getPrototype().get("name", error);
                if (exceptionBreakpoints.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void removeExceptionBreakpoint(String exceptionType) {
        exceptionBreakpoints.remove(exceptionType);
    }

    /**
     * Returns the current ContextData object.
     */
    public ContextData currentContextData() {
        return interruptedContextData;
    }

    /**
     * Sets the action to perform to end interruption.
     */
    public void setReturnValue(int returnValue) {
        synchronized (monitor) {
            this.returnValue = returnValue;
            monitor.notify();
        }
    }

    /**
     * Resumes execution of script.
     */
    public void go() {
        synchronized (monitor) {
            this.returnValue = GO;
            monitor.notifyAll();
        }
    }

    public Object eval(Scriptable scope, Scriptable thisObj, String expr) {
        Object result = Undefined.instance;
        if (expr == null) {
            return result;
        }
        ContextData contextData = currentContextData();
        if (contextData == null || frameIndex >= contextData.frameCount()) {
            return result;
        }
        if (contextData.eventThreadFlag) {
            Context cx = Context.getCurrentContext();
            result = do_eval(cx, scope, thisObj, expr);
        } else {
            synchronized (monitor) {
                if (insideInterruptLoop) {
                    evalRequest = expr;
                    evalScope = scope;
                    evalThisObj = thisObj;
                    monitor.notify();
                    do {
                        try {
                            monitor.wait();
                        } catch (InterruptedException exc) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } while (evalRequest != null);
                    result = evalResult;
                }
            }
        }
        return result;
    }

    /**
     * Evaluates the given script, and returns it's value in string format
     */
    public Object eval(String expr) {
        String result = "undefined";
        ContextData contextData = currentContextData();
        if (contextData == null || frameIndex >= contextData.frameCount()) {
            return result;
        }
        StackFrame frame = contextData.getFrame(frameIndex);
        return eval(frame.scope, frame.thisObj, expr);
    }

    /**
     * Compiles the given script.
     */
    public void compileScript(String url, String text) {
        DimIProxy action = new DimIProxy(this, IPROXY_COMPILE_SCRIPT);
        action.url = url;
        action.text = text;
        action.withContext();
    }

    /**
     * Evaluates the given script.
     */
    public Object evalScript(final String url, final String text) {
        DimIProxy action = new DimIProxy(this, IPROXY_EVAL_SCRIPT);
        action.url = url;
        action.text = text;
        action.withContext();
        return action.objectResult;
    }

    /**
     * Converts the given script object to a string.
     */
    public String objectToString(Object object) {
        DimIProxy action = new DimIProxy(this, IPROXY_OBJECT_TO_STRING);
        action.object = object;
        action.withContext();
        return action.stringResult;
    }

    /**
     * Returns whether the given string is syntactically valid script.
     */
    public boolean stringIsCompilableUnit(String str) {
        DimIProxy action = new DimIProxy(this, IPROXY_STRING_IS_COMPILABLE);
        action.text = str;
        action.withContext();
        return action.booleanResult;
    }

    /**
     * Returns the value of a property on the given script object.
     */
    public Object getObjectProperty(Object object, Object id) {
        DimIProxy action = new DimIProxy(this, IPROXY_OBJECT_PROPERTY);
        action.object = object;
        action.id = id;
        action.withContext();
        return action.objectResult;
    }

    /**
     * Returns an array of the property names on the given script object.
     */
    public Object[] getObjectIds(Object object) {
        DimIProxy action = new DimIProxy(this, IPROXY_OBJECT_IDS);
        action.object = object;
        action.withContext();
        return action.objectArrayResult;
    }

    /**
     * Returns the value of a property on the given script object.
     */
    private Object getObjectPropertyImpl(Context cx, Object object,
                                           Object id) {
        Scriptable scriptable = (Scriptable)object;
        Object result;
        if (id instanceof String) {
            String name = (String)id;
            if (name.equals("this")) {
                result = scriptable;
            } else if (name.equals("__proto__")) {
                result = scriptable.getPrototype();
            } else if (name.equals("__parent__")) {
                result = scriptable.getParentScope();
            } else {
                result = ScriptableObject.getProperty(scriptable, name);
                if (result == ScriptableObject.NOT_FOUND) {
                    result = Undefined.instance;
                }
            }
        } else {
            int index = ((Integer)id).intValue();
            result = ScriptableObject.getProperty(scriptable, index);
            if (result == ScriptableObject.NOT_FOUND) {
                result = Undefined.instance;
            }
        }
        return result;
    }

    /**
     * Returns an array of the property names on the given script object.
     */
    private Object[] getObjectIdsImpl(Context cx, Object object) {
        if (!(object instanceof Scriptable) || object == Undefined.instance) {
            return Context.emptyArgs;
        }

        Object[] ids;
        Scriptable scriptable = (Scriptable)object;
        if (scriptable instanceof DebuggableObject) {
            ids = ((DebuggableObject)scriptable).getAllIds();
        } else {
            ids = scriptable.getIds();
        }

        Scriptable proto = scriptable.getPrototype();
        Scriptable parent = scriptable.getParentScope();
        int extra = 0;
        if (proto != null) {
            ++extra;
        }
        if (parent != null) {
            ++extra;
        }
        if (extra != 0) {
            Object[] tmp = new Object[extra + ids.length];
            System.arraycopy(ids, 0, tmp, extra, ids.length);
            ids = tmp;
            extra = 0;
            if (proto != null) {
                ids[extra++] = "__proto__";
            }
            if (parent != null) {
                ids[extra++] = "__parent__";
            }
        }

        return ids;
    }

    /**
     * Interrupts script execution.
     */
    private void interrupted(Context cx, final StackFrame frame,
                               Throwable scriptException) {
        if (logger != null) {
            logger.fine("interrupted, context = " + cx + ", frame = " + frame + ", exception = " + scriptException);
        }
        ContextData contextData = frame.contextData();
        boolean eventThreadFlag = callback.isGuiEventThread();
        contextData.eventThreadFlag = eventThreadFlag;

        boolean recursiveEventThreadCall = false;

interruptedCheck:
        synchronized (eventThreadMonitor) {
            if (eventThreadFlag) {
                if (interruptedContextData != null) {
                    recursiveEventThreadCall = true;
                    break interruptedCheck;
                }
            } else {
                while (interruptedContextData != null) {
                    try {
                        eventThreadMonitor.wait();
                    } catch (InterruptedException exc) {
                        return;
                    }
                }
            }
            interruptedContextData = contextData;
        }

        if (recursiveEventThreadCall) {
            // XXX: For now the following is commented out as on Linux
            // too deep recursion of dispatchNextGuiEvent causes GUI lockout.
            // Note: it can make GUI unresponsive if long-running script
            // will be called on GUI thread while processing another interrupt
            if (false) {
               // Run event dispatch until gui sets a flag to exit the initial
               // call to interrupted.
                while (this.returnValue == -1) {
                    try {
                        callback.dispatchNextGuiEvent();
                    } catch (InterruptedException exc) {
                    }
                }
            }
            return;
        }

        if (interruptedContextData == null) Kit.codeBug();

        try {
            do {
                int frameCount = contextData.frameCount();
                this.frameIndex = frameCount -1;

                final String threadTitle = Thread.currentThread().toString();
                final String alertMessage;

                int returnValue = -1;
                if (!eventThreadFlag) {
                    synchronized (monitor) {
                        if (insideInterruptLoop) Kit.codeBug();
                        this.insideInterruptLoop = true;
                        this.evalRequest = null;
                        this.returnValue = -1;
                        callback.enterInterrupt(frame, threadTitle,
                                                scriptException);
                        try {
                            for (;;) {
                                try {
                                    monitor.wait();
                                } catch (InterruptedException exc) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                if (evalRequest != null) {
                                    this.evalResult = null;
                                    try {
                                        evalResult = do_eval(cx, evalScope, evalThisObj,
                                                             evalRequest);
                                    } finally {
                                        evalRequest = null;
                                        evalScope = null;
                                        evalThisObj = null;
                                        monitor.notify();
                                    }
                                    continue;
                                }
                                if (this.returnValue != -1) {
                                    returnValue = this.returnValue;
                                    break;
                                }
                            }
                        } finally {
                            insideInterruptLoop = false;
                        }
                    }
                } else {
                    this.returnValue = -1;
                    callback.enterInterrupt(frame, threadTitle, scriptException);
                    while (this.returnValue == -1) {
                        try {
                            callback.dispatchNextGuiEvent();
                        } catch (InterruptedException exc) {
                        }
                    }
                    returnValue = this.returnValue;
                }
                switch (returnValue) {
                case STEP_OVER:
                    contextData.breakNextLine = true;
                    contextData.stopAtFrameDepth = contextData.frameCount();
                    break;
                case STEP_INTO:
                    contextData.breakNextLine = true;
                    contextData.stopAtFrameDepth = -1;
                    break;
                case STEP_OUT:
                    if (contextData.frameCount() > 1) {
                        contextData.breakNextLine = true;
                        contextData.stopAtFrameDepth
                            = contextData.frameCount() -1;
                    }
                    break;
                }
            } while (false);
        } finally {
            synchronized (eventThreadMonitor) {
                interruptedContextData = null;
                eventThreadMonitor.notifyAll();
            }
        }

    }

    /**
     * Evaluates script in the given scope with the given thisObj
     */
    private static Object do_eval(Context cx, Scriptable scope, Scriptable thisObj, String expr) {
        Object result;
        Debugger saved_debugger = cx.getDebugger();
        Object saved_data = cx.getDebuggerContextData();
        int saved_level = cx.getOptimizationLevel();

        cx.setDebugger(null, null);
        cx.setOptimizationLevel(-1);
        cx.setGeneratingDebug(false);
        try {
            Callable script = (Callable)cx.compileString(expr, "", 0, null);
            result = script.call(cx, scope, thisObj, ScriptRuntime.emptyArgs);
        } catch (Exception exc) {
            result = exc;
        } finally {
            cx.setGeneratingDebug(true);
            cx.setOptimizationLevel(saved_level);
            cx.setDebugger(saved_debugger, saved_data);
        }
        return result;
    }

    /**
     * Proxy class to implement debug interfaces without bloat of class
     * files.
     */
    private static class DimIProxy
        implements ContextAction, ContextFactory.Listener, Debugger {

        /**
         * The debugger.
         */
        private Dim dim;

        /**
         * The interface implementation type.  One of the IPROXY_* constants
         * defined in {@link Dim}.
         */
        private int type;

        /**
         * The URL origin of the script to compile or evaluate.
         */
        private String url;

        /**
         * The text of the script to compile, evaluate or test for compilation.
         */
        private String text;

        /**
         * The object to convert, get a property from or enumerate.
         */
        private Object object;

        /**
         * The property to look up in {@link #object}.
         */
        private Object id;

        /**
         * The boolean result of the action.
         */
        private boolean booleanResult;

        /**
         * The String result of the action.
         */
        private String stringResult;

        /**
         * The Object result of the action.
         */
        private Object objectResult;

        /**
         * The Object[] result of the action.
         */
        private Object[] objectArrayResult;

        /**
         * Creates a new DimIProxy.
         */
        private DimIProxy(Dim dim, int type) {
            this.dim = dim;
            this.type = type;
        }

        // ContextAction

        /**
         * Performs the action given by {@link #type}.
         */
        public Object run(Context cx) {
            switch (type) {
              case IPROXY_COMPILE_SCRIPT:
                cx.compileString(text, url, 1, null);
                break;

              case IPROXY_EVAL_SCRIPT:
                {
                    Scriptable scope = null;
                    if (dim.scopeProvider != null) {
                        scope = dim.scopeProvider.getScope();
                    }
                    if (scope == null) {
                        scope = new ImporterTopLevel(cx);
                    }
                    objectResult = cx.evaluateString(scope, text, url, 1, null);
                }
                break;

              case IPROXY_STRING_IS_COMPILABLE:
                booleanResult = cx.stringIsCompilableUnit(text);
                break;

              case IPROXY_OBJECT_TO_STRING:
                if (object == Undefined.instance) {
                    stringResult = "undefined";
                } else if (object == null) {
                    stringResult = "null";
                } else if (object instanceof NativeCall) {
                    stringResult = "[object Call]";
                } else {
                    stringResult = Context.toString(object);
                }
                break;

              case IPROXY_OBJECT_PROPERTY:
                objectResult = dim.getObjectPropertyImpl(cx, object, id);
                break;

              case IPROXY_OBJECT_IDS:
                objectArrayResult = dim.getObjectIdsImpl(cx, object);
                break;

              default:
                throw Kit.codeBug();
            }
            return null;
        }

        /**
         * Performs the action given by {@link #type} with the attached
         * {@link ContextFactory}.
         */
        private void withContext() {
            dim.contextFactory.call(this);
        }

        // ContextFactory.Listener

        /**
         * Called when a Context is created.
         */
        public void contextCreated(Context cx) {
            if (type != IPROXY_LISTEN) Kit.codeBug();
            ContextData contextData = new ContextData();
            Debugger debugger = new DimIProxy(dim, IPROXY_DEBUG);
            cx.setDebugger(debugger, contextData);
            cx.setGeneratingDebug(true);
            cx.setOptimizationLevel(-1);
        }

        /**
         * Called when a Context is destroyed.
         */
        public void contextReleased(Context cx) {
            if (type != IPROXY_LISTEN) Kit.codeBug();
        }

        // Debugger

        /**
         * Returns a StackFrame for the given function or script.
         */
        public DebugFrame getFrame(Context cx, DebuggableScript fnOrScript) {
            if (type != IPROXY_DEBUG) Kit.codeBug();

            FunctionSource item = dim.getFunctionSource(fnOrScript);
            if (item == null) {
                // Can not debug if source is not available
                return null;
            }
            return new StackFrame(cx, dim, item);
        }

        /**
         * Called when compilation is finished.
         */
        public void handleCompilationDone(Context cx,
                                          DebuggableScript fnOrScript,
                                          String source) {
            if (type != IPROXY_DEBUG) Kit.codeBug();

            if (!fnOrScript.isTopLevel()) {
                return;
            }
            dim.registerTopScript(fnOrScript, source);
        }
    }

    /**
     * Class to store information about a stack.
     */
    public static class ContextData {

        /**
         * The stack frames.
         */
        private ObjArray frameStack = new ObjArray();

        /**
         * Whether the debugger should break at the next line in this context.
         */
        private boolean breakNextLine;

        /**
         * The frame depth the debugger should stop at.  Used to implement
         * "step over" and "step out".
         */
        private int stopAtFrameDepth = -1;

        /**
         * Whether this context is in the event thread.
         */
        private boolean eventThreadFlag;

        /**
         * The last exception that was processed.
         */
        private Throwable lastProcessedException;

        /**
         * Returns the ContextData for the given Context.
         */
        public static ContextData get(Context cx) {
            return (ContextData) cx.getDebuggerContextData();
        }

        /**
         * Returns the number of stack frames.
         */
        public int frameCount() {
            return frameStack.size();
        }

        /**
         * Returns the stack frame with the given index.
         */
        public StackFrame getFrame(int frameNumber) {
            int num = frameStack.size() - frameNumber - 1;
            return (StackFrame) frameStack.get(num);
        }

        /**
         * Pushes a stack frame on to the stack.
         */
        private void pushFrame(StackFrame frame) {
            frameStack.push(frame);
        }

        /**
         * Pops a stack frame from the stack.
         */
        private void popFrame() {
            frameStack.pop();
        }
    }

    /**
     * Object to represent one stack frame.
     */
    public static class StackFrame implements DebugFrame {

        /**
         * The debugger.
         */
        private Dim dim;

        /**
         * The ContextData for the Context being debugged.
         */
        private ContextData contextData;

        /**
         * The scope.
         */
        private Scriptable scope;

        /**
         * The 'this' object.
         */
        private Scriptable thisObj;

        /**
         * The args to the current frame
         */
        private Object[] args;

        /**
         * Information about the function.
         */
        private FunctionSource fsource;

        /**
         * Array of breakpoint state for each source line.
         */
        private boolean[] breakpoints;

        /**
         * Current line number.
         */
        private int lineNumber;

        /**
         * Creates a new StackFrame.
         */
        private StackFrame(Context cx, Dim dim, FunctionSource fsource) {
            this.dim = dim;
            this.contextData = ContextData.get(cx);
            this.fsource = fsource;
            this.breakpoints = fsource.sourceInfo().breakpoints;
            this.lineNumber = fsource.firstLine();
        }

        /**
         * Called when the stack frame is entered.
         */
        public void onEnter(Context cx, Scriptable scope,
                            Scriptable thisObj, Object[] args) {
            contextData.pushFrame(this);
            this.args = args;
            this.scope = scope;
            this.thisObj = thisObj;
            if (dim.breakOnEnter) {
                dim.handleBreakpointHit(this, cx);
            }
        }

        /**
         * Called when the current position has changed.
         */
        public void onLineChange(Context cx, int lineno) {
            String url = fsource.sourceInfo().url();
            long threadId = Thread.currentThread().getId();
            if (dim.logger != null) {
                dim.logger.fine("onLineChange, thread id: " + threadId + ", url: " + url + ", cx: " + cx + ", lineno: " + lineno);
            }
            this.lineNumber = lineno;

            boolean checkCondition = true;
            if (!breakpoints[lineno] && !dim.breakFlag) {
                boolean lineBreak = contextData.breakNextLine;
                if (lineBreak && contextData.stopAtFrameDepth >= 0) {
                    lineBreak = (contextData.frameCount()
                                 <= contextData.stopAtFrameDepth);
                }
                if (!lineBreak) {
                    synchronized (dim.breakThreadIds) {
                        if (!dim.breakThreadIds.contains(threadId)) {
                            return;
                        } else {
                            if (dim.logger != null) {
                                dim.logger.fine("breaking on url: " + url + ", context " + cx + ", removing from breakContexts");
                            }
                            dim.breakThreadIds.remove(threadId);
                        }
                    }
                }
                contextData.stopAtFrameDepth = -1;
                contextData.breakNextLine = false;
                checkCondition = false;
            } else if (dim.breakFlag) {
                checkCondition = false;
            }

            boolean hit = true;
            if (checkCondition) {
                if (dim.logger != null) {
                    dim.logger.fine("checking condition for breakpoint at line " + lineno + ", url: " + url);
                }
                Condition condition = fsource.sourceInfo().getCondition(lineno);
                if (condition != null) {
                    try {
                        if (!condition.shouldBreak(cx, scope, thisObj)) {
                            if (dim.logger != null) {
                                dim.logger.fine("skipping breakpoint, condition failed");
                            }
                            hit = false;
                        }
                    } catch (Exception e) {
                        if (dim.logger != null) {
                            dim.logger.fine("caught exception while evaluating condition, breaking and reporting: " + e);
                        }
                        dim.handleBreakpointConditionException(this, cx, e);
                        return;
                    }
                }
            }
            if (hit) {
                if (dim.logger != null) {
                    dim.logger.fine("handlin breakpoint hit for url: " + url + ", context: " + cx);
                }
                dim.handleBreakpointHit(this, cx);
            }
        }

        /**
         * Called when an exception has been thrown.
         */
        public void onExceptionThrown(Context cx, Throwable exception) {
            dim.handleExceptionThrown(cx, exception, this);
        }

        /**
         * Called when the stack frame has been left.
         */
        public void onExit(Context cx, boolean byThrow,
                           Object resultOrException) {
            this.args = null;
            if (dim.breakOnReturn && !byThrow) {
                dim.handleBreakpointHit(this, cx);
            }
            contextData.popFrame();
        }

        /**
         * Called when a 'debugger' statement is executed.
         */
        public void onDebuggerStatement(Context cx) {
            dim.handleBreakpointHit(this, cx);
        }

        /**
         * Returns the SourceInfo object for the function.
         */
        public SourceInfo sourceInfo() {
            return fsource.sourceInfo();
        }

        /**
         * Returns the ContextData object for the Context.
         */
        public ContextData contextData() {
            return contextData;
        }

        /**
         * Returns the scope object for this frame.
         */
        public Object scope() {
            return scope;
        }

        /**
         * Returns the 'this' object for this frame.
         */
        public Object thisObj() {
            return thisObj;
        }

        public Object[] args() {
            return args;
        }

        /**
         * Returns the source URL.
         */
        public String getUrl() {
            return fsource.sourceInfo().url();
        }

        /**
         * Returns the current line number.
         */
        public int getLineNumber() {
            return lineNumber;
        }
    }

    /**
     * Class to store information about a function.
     */
    public static class FunctionSource {

        /**
         * Information about the source of the function.
         */
        private SourceInfo sourceInfo;

        /**
         * Line number of the first line of the function.
         */
        private int firstLine;

        /**
         * The function name.
         */
        private String name;

        /**
         * Creates a new FunctionSource.
         */
        private FunctionSource(SourceInfo sourceInfo, int firstLine,
                                 String name) {
            if (name == null) throw new IllegalArgumentException();
            this.sourceInfo = sourceInfo;
            this.firstLine = firstLine;
            this.name = name;
        }

        /**
         * Returns the SourceInfo object that describes the source of the
         * function.
         */
        public SourceInfo sourceInfo() {
            return sourceInfo;
        }

        /**
         * Returns the line number of the first line of the function.
         */
        public int firstLine() {
            return firstLine;
        }

        /**
         * Returns the name of the function.
         */
        public String name() {
            return name;
        }
    }

    public static class Condition {
        public static enum Type
        {
            OnTrue, OnValueChange
        }

        public int hitCount = 1, currentHit = 0;
        public String condition;
        public Condition.Type conditionType;
        public Script compiledCondition;
        public Object currentConditionValue;

        public Condition(String condition, Condition.Type type)
        {
            this.condition = condition;
            this.conditionType = type;
        }

        public boolean shouldBreak(Context context, Scriptable scope, Scriptable thisObj)
        {
            currentHit++;
            if (currentHit < hitCount) { return false; }
            if (condition == null) { return true; }
            if (compiledCondition == null) {
                compiledCondition = context.compileString(condition, "<expr>", 1, null);
            }
            // Scripts are implicit functions
            Function fn = (Function) compiledCondition;
            Object result = fn.call(context, scope, thisObj, ScriptRuntime.emptyArgs);
            if (conditionType == Type.OnValueChange) {
                if (result == null && currentConditionValue != null) {
                    currentConditionValue = null;
                    return true;
                } else if (result != null && !result.equals(currentConditionValue)) {
                    currentConditionValue = result;
                    return true;
                }
            } else if (conditionType == Type.OnTrue) {
                if (result instanceof Boolean) {
                    return ((Boolean) result).booleanValue();
                } else if (result != null) { return Boolean.parseBoolean(result.toString()); }
            }
            return false;
        }
    }

    /**
     * Class to store information about a script source.
     */
    public static class SourceInfo {

        /**
         * An empty array of booleans.
         */
        private static final boolean[] EMPTY_BOOLEAN_ARRAY = new boolean[0];

        /**
         * The script.
         */
        private String source;

        /**
         * The URL of the script.
         */
        private String url;

        /**
         * Array indicating which lines can have breakpoints set.
         */
        private boolean[] breakableLines;
        
        /**
         * Array indicating whether a breakpoint is set on the line.
         */
        private boolean[] breakpoints;

        /**
         * Array of conditions for each brekapoint (null indicates no condition)
         */
        private Condition[] conditions;

        /**
         * Array of FunctionSource objects for the functions in the script.
         */
        private FunctionSource[] functionSources;

        /**
         * Creates a new SourceInfo object.
         */
        private SourceInfo(String source, DebuggableScript[] functions,
                             String normilizedUrl) {
            this.source = source;
            this.url = normilizedUrl;

            int N = functions.length;
            int[][] lineArrays = new int[N][];
            for (int i = 0; i != N; ++i) {
                lineArrays[i] = functions[i].getLineNumbers();
            }

            int minAll = 0, maxAll = -1;
            int[] firstLines = new int[N];
            for (int i = 0; i != N; ++i) {
                int[] lines = lineArrays[i];
                if (lines == null || lines.length == 0) {
                    firstLines[i] = -1;
                } else {
                    int min, max;
                    min = max = lines[0];
                    for (int j = 1; j != lines.length; ++j) {
                        int line = lines[j];
                        if (line < min) {
                            min = line;
                        } else if (line > max) {
                            max = line;
                        }
                    }
                    firstLines[i] = min;
                    if (minAll > maxAll) {
                        minAll = min;
                        maxAll = max;
                    } else {
                        if (min < minAll) {
                            minAll = min;
                        }
                        if (max > maxAll) {
                            maxAll = max;
                        }
                    }
                }
            }

            if (minAll > maxAll) {
                // No line information
                this.breakableLines = EMPTY_BOOLEAN_ARRAY;
                this.breakpoints = EMPTY_BOOLEAN_ARRAY;
                this.conditions = new Condition[0];
            } else {
                if (minAll < 0) {
                    // Line numbers can not be negative
                    throw new IllegalStateException(String.valueOf(minAll));
                }
                int linesTop = maxAll + 1;
                this.breakableLines = new boolean[linesTop];
                this.breakpoints = new boolean[linesTop];
                this.conditions = new Condition[linesTop];
                for (int i = 0; i != N; ++i) {
                    int[] lines = lineArrays[i];
                    if (lines != null && lines.length != 0) {
                        for (int j = 0; j != lines.length; ++j) {
                            int line = lines[j];
                            this.breakableLines[line] = true;
                        }
                    }
                }
            }
            this.functionSources = new FunctionSource[N];
            for (int i = 0; i != N; ++i) {
                String name = functions[i].getFunctionName();
                if (name == null) {
                    name = "";
                }
                this.functionSources[i]
                    = new FunctionSource(this, firstLines[i], name);
            }
        }

        /**
         * Returns the source text.
         */
        public String source() {
            return this.source;
        }

        /**
         * Returns the script's origin URL.
         */
        public String url() {
            return this.url;
        }

        /**
         * Returns the number of FunctionSource objects stored in this object.
         */
        public int functionSourcesTop() {
            return functionSources.length;
        }

        /**
         * Returns the FunctionSource object with the given index.
         */
        public FunctionSource functionSource(int i) {
            return functionSources[i];
        }

        /**
         * Copies the breakpoints from the given SourceInfo object into this
         * one.
         */
        private void copyBreakpointsFrom(SourceInfo old) {
            int end = old.breakpoints.length;
            if (end > this.breakpoints.length) {
                end = this.breakpoints.length;
            }
            for (int line = 0; line != end; ++line) {
                if (old.breakpoints[line]) {
                    this.breakpoints[line] = true;
                }
                this.conditions[line] = old.conditions[line];
            }
        }

        /**
         * Returns whether the given line number can have a breakpoint set on
         * it.
         */
        public boolean breakableLine(int line) {
            return (line < this.breakableLines.length)
                   && this.breakableLines[line];
        }

        /**
         * Returns whether there is a breakpoint set on the given line.
         */
        public boolean breakpoint(int line) {
            if (!breakableLine(line)) {
                throw new IllegalArgumentException(String.valueOf(line));
            }
            return line < this.breakpoints.length && this.breakpoints[line];
        }

        /**
         * Sets or clears the breakpoint flag for the given line.
         */
        public boolean breakpoint(int line, boolean value) {
            if (!breakableLine(line)) {
                throw new IllegalArgumentException(String.valueOf(line));
            }
            boolean changed;
            synchronized (breakpoints) {
                if (breakpoints[line] != value) {
                    breakpoints[line] = value;
                    changed = true;
                } else {
                    changed = false;
                }
            }
            return changed;
        }

        public Condition getCondition(int line) {
            if (!breakableLine(line)) {
                throw new IllegalArgumentException(String.valueOf(line));
            }
            if (line < this.conditions.length) {
                return this.conditions[line];
            }
            return null;
        }

        public void setCondition(int line, Condition condition) {
            if (!breakableLine(line)) {
                throw new IllegalArgumentException(String.valueOf(line));
            }
            synchronized (conditions) {
                conditions[line] = condition;
            }
        }

        /**
         * Removes all breakpoints from the script.
         */
        public void removeAllBreakpoints() {
            synchronized (breakpoints) {
                for (int line = 0; line != breakpoints.length; ++line) {
                    breakpoints[line] = false;
                }
            }
        }
    }
}

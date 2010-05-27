/*
* Copyright 2007-2009 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package net.paoding.rose.web.impl.thread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.paoding.rose.web.Dispatcher;
import net.paoding.rose.web.Invocation;
import net.paoding.rose.web.InvocationUtils;
import net.paoding.rose.web.RequestPath;
import net.paoding.rose.web.annotation.ReqMethod;
import net.paoding.rose.web.impl.mapping.MappingNode;
import net.paoding.rose.web.impl.mapping.MatchResult;
import net.paoding.rose.web.impl.module.Module;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 
 * @author 王志亮 [qieqie.wang@gmail.com]
 * 
 */
public class Rose implements EngineChain {

    protected static final Log logger = LogFactory.getLog(Rose.class);

    private final List<Module> modules;

    private final MappingNode mappingTree;

    private final RequestPath path;

    private final HttpServletRequest originalHttpRequest;

    private final HttpServletResponse originalHttpResponse;

    private boolean started;

    private List<MatchResult> matchResults;

    private InvocationBean inv;

    private int nextIndexOfChain;

    private final LinkedList<AfterCompletion> afterCompletions = new LinkedList<AfterCompletion>();

    public Rose(List<Module> modules, MappingNode mappingTree, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse, RequestPath requestPath) {
        this.mappingTree = mappingTree;
        this.modules = modules;
        this.originalHttpRequest = httpRequest;
        this.originalHttpResponse = httpResponse;
        this.path = requestPath;
    }

    public MappingNode getMappingTree() {
        return mappingTree;
    }

    public InvocationBean getInvocation() {
        return inv;
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<MatchResult> getMatchResults() {
        return matchResults;
    }

    /**
     * 启动rose逻辑，对请求进行匹配判断，如果匹配未能成功返回false； <br>
     * 对匹配成功的启动相关的架构处理逻辑直至整个请求的完成
     * 
     * @return
     * @throws Throwable
     */
    public boolean start() throws Throwable {
        if (this.started) {
            throw new IllegalStateException("don't start again");
        }
        this.started = true;
        return innerStart();
    }

    /**
     * @throws IndexOutOfBoundsException
     */
    @Override
    public Object doNext() throws Throwable {
        MatchResult matchResult = matchResults.get(nextIndexOfChain++);
        Engine engine = matchResult.getEngine();
        return engine.execute(this, matchResult);
    }

    private boolean innerStart() throws Throwable {
        final ArrayList<MatchResult> matchResults = mappingTree.match(originalHttpRequest,
                this.path);
        final MatchResult result = matchResults.get(matchResults.size() - 1);

        // 完成一次成功匹配需要走到树的叶子结点，并且是方法级别的结点
        if (!result.getMappingNode().isFileType()) {
            if (logger.isDebugEnabled()) {
                logger.debug("[" + this.path + "] matchResults.size=" + matchResults.size());
            }
            return false;
        }

        // but 405 ?
        // TODO: 有些result.getEngine为null的，但不一定是405，比如@IfParamExists的，是为404
        if (result.getEngine() == null) {
            /* 405 Method Not Allowed
             * The method specified in the Request-Line is not allowed for the
             * resource identified by the Request-URI. The response MUST include an
             * Allow header containing a list of valid methods for the requested
             * resource.
             */
            StringBuilder allow = new StringBuilder();
            final String gap = ", ";
            for (ReqMethod method : result.getAllowedMethods()) {
                allow.append(method.toString()).append(gap);
            }
            if (allow.length() > 0) {
                allow.setLength(allow.length() - gap.length());
            }
            originalHttpResponse.addHeader("Allow", allow.toString());
            originalHttpResponse.sendError(405, this.path.getUri());
            
            // true: don't forward to next filter or servlet
            return true;
        }

        // ok, got it
        this.matchResults = matchResults;

        Map<String, String> mrParameters = null;
        for (int i = 0; i < matchResults.size(); i++) {
            MatchResult tmr = matchResults.get(i);
            if (tmr.getParameterCount() > 0) {
                if (mrParameters == null) {
                    mrParameters = new HashMap<String, String>(6);
                }
                for (String name : tmr.getParameterNames()) {
                    mrParameters.put(name, tmr.getParameter(name));
                }
            }
        }
        HttpServletRequest httpRequest = originalHttpRequest;
        if (mrParameters != null && mrParameters.size() > 0) {
            httpRequest = new ParameteredUriRequest(originalHttpRequest, mrParameters);
        }

        // originalThreadRequest可能为null，特别是在portal框架下
        HttpServletRequest originalThreadRequest = InvocationUtils.getCurrentThreadRequest();
        //
        Invocation preInvocation = null;
        if (path.getDispatcher() != Dispatcher.REQUEST) {
            preInvocation = InvocationUtils.getInvocation(originalHttpRequest);
        }
        // invocation 对象 代表一次Rose调用
        InvocationBean inv = new InvocationBean(httpRequest, originalHttpResponse, path);
        inv.setRose(this);
        inv.setPreInvocation(preInvocation);
        //
        InvocationUtils.bindRequestToCurrentThread(httpRequest);
        InvocationUtils.bindInvocationToRequest(inv, httpRequest);

        // invoke the engine chain
        this.inv = inv;
        Throwable error = null;
        try {
            Object instuction = ((EngineChain) this).doNext();
            if (":continue".equals(instuction)) {
                return false;
            }
        } catch (Throwable local) {
            error = local;
            throw local;
        } finally {
            for (AfterCompletion task : afterCompletions) {
                try {
                    task.afterCompletion(inv, error);
                } catch (Throwable e) {
                    logger.error("", e);
                }
            }
            if (originalThreadRequest != null) {
                InvocationUtils.bindRequestToCurrentThread(originalThreadRequest);
            } else {
                InvocationUtils.unindRequestFromCurrentThread();
            }
            // 更新绑定的invocation，只对于那些forward后request.setAttibute影响了前者的有效。(include的不用处理了，已经做了snapshot了)
            if (preInvocation != null) {
                InvocationUtils.bindInvocationToRequest(preInvocation, httpRequest);
            }
        }

        return true;
    }

    @Override
    public void addAfterCompletion(AfterCompletion task) {
        afterCompletions.addFirst(task);
    }
}

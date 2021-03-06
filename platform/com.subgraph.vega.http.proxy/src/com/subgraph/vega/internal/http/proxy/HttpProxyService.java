/*******************************************************************************
 * Copyright (c) 2011 Subgraph.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Subgraph - initial API and implementation
 ******************************************************************************/
package com.subgraph.vega.internal.http.proxy;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.client.HttpClient;

import com.subgraph.vega.api.analysis.IContentAnalyzer;
import com.subgraph.vega.api.analysis.IContentAnalyzerFactory;
import com.subgraph.vega.api.http.proxy.IHttpInterceptProxyEventHandler;
import com.subgraph.vega.api.http.proxy.IHttpInterceptor;
import com.subgraph.vega.api.http.proxy.IHttpProxyService;
import com.subgraph.vega.api.http.proxy.IHttpProxyTransactionManipulator;
import com.subgraph.vega.api.http.proxy.IProxyTransaction;
import com.subgraph.vega.api.http.requests.IHttpRequestEngine;
import com.subgraph.vega.api.http.requests.IHttpRequestEngineFactory;
import com.subgraph.vega.api.model.IModel;
import com.subgraph.vega.api.model.IWorkspace;
import com.subgraph.vega.api.paths.IPathFinder;
import com.subgraph.vega.api.scanner.modules.IResponseProcessingModule;
import com.subgraph.vega.api.scanner.modules.IScannerModuleRegistry;
import com.subgraph.vega.internal.http.proxy.ssl.ProxySSLInitializationException;
import com.subgraph.vega.internal.http.proxy.ssl.SSLContextRepository;

public class HttpProxyService implements IHttpProxyService {
	private final Logger logger = Logger.getLogger(HttpProxyService.class.getName());
	private boolean isRunning = false;
	private boolean isPassthrough = false;
	private final IHttpInterceptProxyEventHandler eventHandler;
	private IModel model;
	private IHttpRequestEngineFactory requestEngineFactory;
	private IContentAnalyzerFactory contentAnalyzerFactory;
	private IContentAnalyzer contentAnalyzer;
	private List<IResponseProcessingModule> responseProcessingModules;

	private IScannerModuleRegistry moduleRepository;
	private HttpProxy proxy;
	private IWorkspace currentWorkspace;
	private IPathFinder pathFinder;

	private ProxyTransactionManipulator transactionManipulator;
	private HttpInterceptor interceptor;
	private SSLContextRepository sslContextRepository;

	public HttpProxyService() {
		eventHandler = new IHttpInterceptProxyEventHandler() {
			@Override
			public void handleRequest(IProxyTransaction transaction) {
				processTransaction(transaction);
			}
		};
		transactionManipulator = new ProxyTransactionManipulator(); 
	}

	public void activate() {
		interceptor = new HttpInterceptor(model);
		responseProcessingModules = loadModules();
		try {
			sslContextRepository = SSLContextRepository.createInstance(pathFinder.getVegaDirectory());
		} catch (ProxySSLInitializationException e) {
			sslContextRepository = null;
			logger.warning("Failed to initialize SSL support in proxy.  SSL interception will be disabled. ("+ e.getMessage() + ")");
		}
	}

	@Override
	public boolean isRunning() {
		return isRunning;
	}

	@Override
	public boolean isPassthrough() {
		synchronized(this) {
			return isPassthrough;
		}
	}

	@Override
	public void start(int proxyPort) {
		currentWorkspace = model.getCurrentWorkspace();
		if(currentWorkspace == null) 
			throw new IllegalStateException("Cannot start proxy because no workspace is currently open");
		currentWorkspace.lock();
		responseProcessingModules = loadModules();
		contentAnalyzer = contentAnalyzerFactory.createContentAnalyzer(currentWorkspace.getScanAlertRepository().getProxyScanInstance());
		contentAnalyzer.setResponseProcessingModules(responseProcessingModules);
		contentAnalyzer.setDefaultAddToRequestLog(true);
		contentAnalyzer.setAddLinksToModel(true);

		final HttpClient httpClient = requestEngineFactory.createBasicClient();
		final IHttpRequestEngine requestEngine = requestEngineFactory.createRequestEngine(httpClient, requestEngineFactory.createConfig());
		proxy = new HttpProxy(proxyPort, transactionManipulator, interceptor, requestEngine, sslContextRepository);
		proxy.registerEventHandler(eventHandler);
		proxy.startProxy();
	}

	private List<IResponseProcessingModule> loadModules() {
		if(responseProcessingModules == null) {
			return moduleRepository.getResponseProcessingModules();
		} else {
			return moduleRepository.updateResponseProcessingModules(responseProcessingModules);
		}
	}

	private void processTransaction(IProxyTransaction transaction) {
		synchronized(this) {
			if(transaction.getResponse() == null || contentAnalyzer == null || isPassthrough) {
				return;
			}
		}
		try {
			contentAnalyzer.processResponse(transaction.getResponse());
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "Exception processing transaction response: "+ e.getMessage(), e);
		}
	}

	@Override
	public void stop() {
		if(currentWorkspace == null)
			throw new IllegalStateException("No workspace is open");
		isRunning = false;
		proxy.unregisterEventHandler(eventHandler);
		proxy.stopProxy();
		contentAnalyzer = null;
		currentWorkspace.unlock();
	}

	@Override
	public void setPassthrough(boolean enabled) {
		synchronized(this) {
			isPassthrough = enabled;
			interceptor.setEnabled(!enabled);
		}
	}

	@Override
	public int getListenPort() {
		if (isRunning) {
			return proxy.getListenPort();
		}
		return -1;
	}

	@Override
	public IHttpProxyTransactionManipulator getTransactionManipulator() {
		return transactionManipulator;
	}

	@Override
	public IHttpInterceptor getInterceptor() {
		return interceptor;
	}

	protected void setModel(IModel model) {
		this.model = model;
	}

	protected void unsetModel(IModel model) {
		this.model = null;
	}

	protected void setContentAnalyzerFactory(IContentAnalyzerFactory factory) {
		this.contentAnalyzerFactory = factory;
	}
	
	protected void unsetContentAnalyzerFactory(IContentAnalyzerFactory factory) {
		this.contentAnalyzerFactory = null;
	}

	protected void setRequestEngineFactory(IHttpRequestEngineFactory factory) {
		this.requestEngineFactory = factory;
	}

	protected void unsetRequestEngineFactory(IHttpRequestEngineFactory factory) {
		this.requestEngineFactory = null;
	}
	
	protected void setModuleRepository(IScannerModuleRegistry moduleRepository) {
		this.moduleRepository = moduleRepository;
	}
	
	protected void unsetModuleRepository(IScannerModuleRegistry moduleRepository) {
		this.moduleRepository = null;
	}

	protected void setPathFinder(IPathFinder pathFinder) {
		this.pathFinder = pathFinder;
	}

	protected void unsetPathFinder(IPathFinder pathFinder) {
		this.pathFinder = null;
	}

	@Override
	public List<IResponseProcessingModule> getResponseProcessingModules() {
		responseProcessingModules = loadModules();
		if(responseProcessingModules == null) {
			return Collections.emptyList();
		} else {
			return responseProcessingModules;
		}
	}

}

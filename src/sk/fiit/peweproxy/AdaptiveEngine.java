package sk.fiit.peweproxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.Set;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.xml.DOMConfigurator;

import rabbit.handler.AdaptiveHandler;
import rabbit.handler.Handler;
import rabbit.http.HttpHeader;
import rabbit.httpio.ConnectionSetupResolver;
import rabbit.httpio.request.ClientResourceHandler;
import rabbit.httpio.request.ContentFetcher;
import rabbit.httpio.request.ContentSeparator;
import rabbit.httpio.request.ContentSource;
import rabbit.httpio.request.DirectContentSource;
import rabbit.httpio.request.PrefetchedContentSource;
import rabbit.io.BufferHandle;
import org.khelekore.rnio.impl.DefaultTaskIdentifier;
import rabbit.proxy.Connection;
import rabbit.proxy.HttpProxy;
import rabbit.proxy.TrafficLoggerHandler;
import rabbit.util.SProperties;
import sk.fiit.peweproxy.headers.HeaderWrapper;
import sk.fiit.peweproxy.messages.HttpMessageFactoryImpl;
import sk.fiit.peweproxy.messages.HttpRequest;
import sk.fiit.peweproxy.messages.HttpRequestImpl;
import sk.fiit.peweproxy.messages.HttpResponse;
import sk.fiit.peweproxy.messages.HttpResponseImpl;
import sk.fiit.peweproxy.messages.ModifiableHttpRequestImpl;
import sk.fiit.peweproxy.messages.ModifiableHttpResponseImpl;
import sk.fiit.peweproxy.plugins.PluginHandler;
import sk.fiit.peweproxy.plugins.ProxyPlugin;
import sk.fiit.peweproxy.plugins.events.EventsHandler;
import sk.fiit.peweproxy.plugins.processing.RequestProcessingPlugin;
import sk.fiit.peweproxy.plugins.processing.ResponseProcessingPlugin;
import sk.fiit.peweproxy.plugins.processing.RequestProcessingPlugin.RequestProcessingActions;
import sk.fiit.peweproxy.plugins.processing.ResponseProcessingPlugin.ResponseProcessingActions;
import sk.fiit.peweproxy.services.ModulesManager;
import sk.fiit.peweproxy.services.ProxyService;
import sk.fiit.peweproxy.services.ServicesHandleBase;
import sk.fiit.peweproxy.utils.StackTraceUtils;

public class AdaptiveEngine  {
	private static final Logger log = Logger.getLogger(AdaptiveEngine.class);
	
	private static final String ORDERING_REQUEST_TEXT = "[request]";
	private static final String ORDERING_RESPONSE_TEXT = "[response]";
	
	private final Map<Connection, ConnectionHandle> requestHandles;
	private final Map<Connection, ConnectionHandle> prevRequestHandles;
	private final HttpProxy proxy;
	private PluginHandler pluginHandler;
	private final EventsHandler loggingHandler;
	private ModulesManager modulesManager;
	private final List<RequestProcessingPlugin> requestPlugins;
	private final List<ResponseProcessingPlugin> responsePlugins;
	private File pluginsOrderFile = null; 
	private boolean proxyDying = false;
	
	class ConnectionHandle {
		private final Connection con;
		private ModifiableHttpRequestImpl request = null;
		private ModifiableHttpResponseImpl response = null;
		private HttpMessageFactoryImpl messageFactory;
		//private boolean requestChunking = false;
		private Handler handler = null;
		private boolean rqLateProcessing = false;
		private boolean rpLateProcessing = false;
		private Boolean responseAfterRQLate = null;
		private final long requestTime;
		private long responseTime;

		public ConnectionHandle(Connection con) {
			this.con = con;
			requestTime = System.currentTimeMillis();
		}
		
		@Override
		public String toString() {
			return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
		}
	}
	
	public AdaptiveEngine(HttpProxy proxy) {
		requestHandles = new HashMap<Connection, ConnectionHandle>();
		prevRequestHandles = new HashMap<Connection, ConnectionHandle>();
		this.proxy = proxy;
		loggingHandler = new EventsHandler(this);
		pluginHandler = new PluginHandler();
		modulesManager = new ModulesManager(this);
		requestPlugins = new LinkedList<RequestProcessingPlugin>();
		responsePlugins = new LinkedList<ResponseProcessingPlugin>();
	}
	
	public ModifiableHttpRequestImpl getRequestForConnection(Connection con) {
		if (con == null) {
			log.warn("Trying to get request for null connection\n"+StackTraceUtils.getStackTraceText());
			return null;
		}
		if (!requestHandles.containsKey(con)) {
			log.warn("No handle for "+con+", can't return request\n"+StackTraceUtils.getStackTraceText());
			return null;
		}
		return requestHandles.get(con).request;
	}
	
	public ModifiableHttpResponseImpl getResponseForConnection(Connection con) {
		if (con == null) {
			log.warn("Trying to get response for null connection\n"+StackTraceUtils.getStackTraceText());
			return null;
		}
		if (!requestHandles.containsKey(con)) {
			log.warn("No handle for "+con+", can't return response\n"+StackTraceUtils.getStackTraceText());
			return null;
		}
		return requestHandles.get(con).response;
	}
	
	public void newRequestAttempt(Connection con) {
		ConnectionHandle newHandle = new ConnectionHandle(con);
		ConnectionHandle prevHandle = requestHandles.put(con, newHandle);
		if (prevHandle != null && (prevHandle.rqLateProcessing || prevHandle.rpLateProcessing))
			prevRequestHandles.put(con, prevHandle);
		if (log.isTraceEnabled())
			log.trace("RQ: "+newHandle+" | Registering new handle for "+con);
	}
	Map<Connection, String> closedConns = new HashMap<Connection, String>();
	
	public void connectionClosed(Connection con) {
		//stackTraceWatcher.addStackTrace(con);
		String stackTrace = StackTraceUtils.getStackTraceText();
		if (closedConns.containsKey(con)) {
			System.out.println(closedConns.get(con));
			System.out.println(stackTrace);
		}
		closedConns.put(con, stackTrace);
		ConnectionHandle conHandle = requestHandles.remove(con);
		if (conHandle.rqLateProcessing || conHandle.rpLateProcessing)
			prevRequestHandles.put(con, conHandle);
		if (log.isTraceEnabled())
			log.trace("RQ: "+conHandle+" | Removing handle for "+con);
	}
	
	public void newRequest(Connection con, HttpHeader clientHeader, HttpHeader proxyHeader) {
		ConnectionHandle conHandle = requestHandles.get(con);
		InetSocketAddress clientSocketAdr = (InetSocketAddress) con.getChannel().socket().getRemoteSocketAddress();
		HttpRequestImpl origRequest = new HttpRequestImpl(modulesManager, new HeaderWrapper(clientHeader),clientSocketAdr);
		origRequest.setReadOnly();
		conHandle.request = new ModifiableHttpRequestImpl(modulesManager, new HeaderWrapper(proxyHeader), origRequest);
		conHandle.messageFactory = new HttpMessageFactoryImpl(this,con,conHandle.request);
		con.setProxyRHeader(conHandle.request.getHeader().getBackedHeader());
		if (log.isTraceEnabled())
			log.trace("RQ: "+conHandle+" | New request received ("
					+origRequest.getHeader().getBackedHeader().getRequestLine()+") - "+conHandle.request);
	}
	
	public void cacheRequestIfNeeded(final Connection con, final ContentSeparator separator, boolean isChunked) {
		final ConnectionHandle conHandle = requestHandles.get(con);
		final BufferHandle bufHandle = con.getRequestBufferHandle();
		final TrafficLoggerHandler tlh = con.getTrafficLoggerHandler();
		ClientResourceHandler resourceHandler = null;
		if (separator != null) {
			// request has some content
			if (log.isTraceEnabled())
				log.trace("RQ: "+conHandle+" | Request separator used: "+separator.toString());
			boolean prefetch = false;
			Set<Class<? extends ProxyService>> desiredServices = new HashSet<Class<? extends ProxyService>>();
			for (RequestProcessingPlugin requestPlugin : requestPlugins) {
				try {
					Set<Class<? extends ProxyService>> pluginsDesiredSvcs
						= new HashSet<Class<? extends ProxyService>>();
					requestPlugin.desiredRequestServices(pluginsDesiredSvcs,conHandle.request.getHeader());
					pluginsDesiredSvcs = new HashSet<Class<? extends ProxyService>>(pluginsDesiredSvcs); //call this "defensive copy"
					if (ServicesHandleBase.contentNeeded(pluginsDesiredSvcs)) {
						if (log.isDebugEnabled())
							log.debug("RQ: "+conHandle+" | Plugin "+requestPlugin+" wants content modifying service for request");
						prefetch = true;
						break;
					} else
						desiredServices.addAll(pluginsDesiredSvcs);
				} catch (Throwable t) {
					log.info("RQ: Throwable raised while obtaining set of desired services from plugin '"+requestPlugin+"'",t);
				}
			}
			if (!prefetch)
				prefetch = conHandle.request.getServicesHandle().needContent(desiredServices);
			if (prefetch) {
				new ContentFetcher(con,bufHandle,tlh,separator,new RequestContentCachedListener(con));
				// cache request body data first, then run full message real-time processing (see requestContentCached())
				return;
			} else {
				if (log.isDebugEnabled())
					log.debug("RQ: "+conHandle+" | No plugin wants content modifying service for request");
				conHandle.rqLateProcessing = true;
				ContentSource directSource = new DirectContentSource(con,bufHandle,tlh,separator,new RequestContentCachedListener(con));
				resourceHandler = new ClientResourceHandler(con,directSource,isChunked);
				// will run header-only real-time processing, then proceeds in handling request what will
				// transfer request body data while caching it for late processing
			}
		} else {
			if (log.isTraceEnabled())
				log.trace("RQ: "+conHandle+" | Request has no body");
			conHandle.rqLateProcessing = false;
		}
		final ClientResourceHandler handler = resourceHandler;
		// no body or no need to modify it, run header-only real-time processing (request may have body)
		proxy.getNioHandler().runThreadTask(new Runnable() {
			@Override
			public void run() {
				ClientResourceHandler handlerToUse = handler;
				RequestProcessingActions result = doRequestProcessing(conHandle, false); //header-only real-time processing
				boolean toSendResponse = (result == RequestProcessingActions.NEW_RESPONSE || result == RequestProcessingActions.FINAL_RESPONSE );
				if (result == RequestProcessingActions.NEW_REQUEST) {
					// substitutive request was constructed
					HttpHeader headerToBeSent = conHandle.request.getHeader().getBackedHeader();
					con.setProxyRHeader(headerToBeSent);
					byte[] requestContent = conHandle.request.getData();
					if (requestContent != null) {
						// substitutive request with body was constructed
						boolean isChunked = ConnectionSetupResolver.isChunked(headerToBeSent); // plugins can decide whether to chunk request body
						PrefetchedContentSource contentSource = new PrefetchedContentSource(requestContent);
						ClientResourceHandler newHandler = new ClientResourceHandler(conHandle.con,contentSource,isChunked);
						handlerToUse = newHandler; // transfer constructed request body instead
						
					} else
						// no body to transfer
						handlerToUse = null;
				} else if (toSendResponse) {
					// set that we need to send response after all data was read from connection with client
					conHandle.responseAfterRQLate = new Boolean((result == RequestProcessingActions.NEW_RESPONSE) ? true : false);
					handlerToUse = null;
				}
				boolean responseWillBeSent = false;
				if (handler != null && handlerToUse != handler) {
					// we are transferring other body than one being read by 'handler' so we need to cache original body for late processing
					new ContentFetcher(con,bufHandle,tlh,separator,new RequestContentCachedListener(con));
					responseWillBeSent = true; // don't send response now, but after request body is read
				}
				if (toSendResponse) {
					if (!responseWillBeSent) {
						boolean processResponse = conHandle.responseAfterRQLate.booleanValue();
						conHandle.responseAfterRQLate = null;
						sendResponse(conHandle, processResponse, false);
					}
				} else
					proceedWithRequest(conHandle, handlerToUse);
			}
		}, new DefaultTaskIdentifier(getClass().getSimpleName()+".requestProcessing", requestProcessingTaskInfo(conHandle)));
	}
	
	public void requestContentCached(final Connection con, final byte[] requestContent, final Queue<Integer> dataIncrements) {
		ConnectionHandle tmpHandle = requestHandles.get(con);
		if (tmpHandle == null || tmpHandle.request == null) {
			tmpHandle = prevRequestHandles.remove(con);
		}
		final ConnectionHandle conHandle = tmpHandle;
		conHandle.request.originalRequest().setData(requestContent);
		if (!conHandle.rqLateProcessing)
			conHandle.request.setData(requestContent);
		if (log.isTraceEnabled()) {
			if (conHandle.rqLateProcessing)
				log.trace("RQ: "+conHandle+" | "+requestContent.length+" bytes of request cached for late processing");
			else
				log.trace("RQ: "+conHandle+" | "+requestContent.length+" bytes of request cached for real-time processing");
		}
		if (conHandle.rqLateProcessing) {
			// full message late processing
			doRequestLateProcessing(conHandle);
			if (conHandle.responseAfterRQLate != null) {
				// now after we've read request body from the connection it's safe to send
				// response body constructed by plugins in header-only real-time processing
				sendResponse(conHandle, conHandle.responseAfterRQLate.booleanValue(), false);
			}
		} else {
			// full message real-time processing
			proxy.getNioHandler().runThreadTask(new Runnable() {
				@Override
				public void run() {
					final RequestProcessingActions result = doRequestProcessing(conHandle, false);
					if (result != RequestProcessingActions.NEW_RESPONSE && result != RequestProcessingActions.FINAL_RESPONSE) {
						// substitutive request may have been constructed
						HttpHeader headerToBeSent = conHandle.request.getHeader().getBackedHeader();
						con.setProxyRHeader(headerToBeSent);
						ClientResourceHandler resourceHandler = null;
						byte[] requestContent = conHandle.request.getData();
						if (requestContent != null) {
							boolean isChunked = ConnectionSetupResolver.isChunked(headerToBeSent); // plugins can decide whether to chunk request body
							PrefetchedContentSource contentSource = new PrefetchedContentSource(requestContent,dataIncrements);
							resourceHandler = new ClientResourceHandler(conHandle.con,contentSource,isChunked);
						}
						proceedWithRequest(conHandle, resourceHandler);
					} else {
						boolean processResponse = (result == RequestProcessingActions.NEW_RESPONSE) ? true : false;
						sendResponse(conHandle, processResponse, false);
					}
				}
			}, new DefaultTaskIdentifier(getClass().getSimpleName()+".requestProcessing", requestProcessingTaskInfo(conHandle)));
		}
	}
	
	private void doRequestLateProcessing(final ConnectionHandle conHandle) {
		pluginHandler.submitTaskToThreadPool(new Runnable() {
			@Override
			public void run() {
				doRequestProcessing(conHandle, true);
				conHandle.rqLateProcessing = false; // to avoid saving conHandle in prevRequestHandles
			}
		});
	}
	
	private RequestProcessingActions doRequestProcessing(ConnectionHandle conHandle, boolean lateProcessing) {
		conHandle.request.setAllowedThread();
		if (log.isTraceEnabled()) {
			if (lateProcessing)
				log.trace("RQ: "+conHandle+" | Running late request processing");
			else
				log.trace("RQ: "+conHandle+" | Running real-time request processing");
		}
		boolean transferOriginalBody = true;
		boolean again = false;
		boolean sendResponse = false;
		boolean processResponse = true;
		Set<RequestProcessingPlugin> pluginsChangedResponse = new HashSet<RequestProcessingPlugin>();
		do {
			again = false;
			for (RequestProcessingPlugin requestPlugin : requestPlugins) {
				if (pluginsChangedResponse.contains(requestPlugin))
					// skip this plugin to prevent cycling
					continue;
				try {
					if (lateProcessing) {
						requestPlugin.processTransferedRequest(conHandle.request);
					} else {
						RequestProcessingActions action = requestPlugin.processRequest(conHandle.request);
						if (action == RequestProcessingActions.NEW_REQUEST || action == RequestProcessingActions.FINAL_REQUEST) {
							HttpRequest newRequest = requestPlugin.getNewRequest(conHandle.request, conHandle.messageFactory);
							if (newRequest == null)
								log.warn("Null HttpRequest was provided by RequestProcessingPlugin '"+requestPlugin+"' after calling getNewRequest()," +
											" substitution is being ignored.");
							else {
								// if FINAL_REQUEST and same request was returned just to stop processing, we DO want to transfer original body
								if (newRequest != conHandle.request) {
									transferOriginalBody = false;
									conHandle.request = (ModifiableHttpRequestImpl) newRequest;
									conHandle.messageFactory = new HttpMessageFactoryImpl(this, conHandle.con, conHandle.request);
									conHandle.request.setAllowedThread();
								}
								if (action == RequestProcessingActions.NEW_REQUEST) {
									pluginsChangedResponse.add(requestPlugin);
									again = true;
								}
								break;
							}
						} else if (action == RequestProcessingActions.NEW_RESPONSE || action == RequestProcessingActions.FINAL_RESPONSE) {
							if (action == RequestProcessingActions.FINAL_RESPONSE)
								processResponse = false;
							HttpResponse newResponse = requestPlugin.getResponse(conHandle.request, conHandle.messageFactory);
							if (newResponse == null)
								log.warn("Null HttpResponse was provided by RequestProcessingPlugin '"+requestPlugin+"' after calling getNewResponse()," +
											" substitution is being ignored.");
							else {
								transferOriginalBody = false; // not needed but still leaving it here
								sendResponse = true;
								HttpResponseImpl origResponse = (HttpResponseImpl) newResponse;
								origResponse.getServicesHandleInternal().finalize();
								conHandle.response = new ModifiableHttpResponseImpl(modulesManager, origResponse.getHeader().clone(), origResponse);
								conHandle.response.setData(origResponse.getData());
								break;
							}
						}
					}
				} catch (Throwable t) {
					log.info("RQ: "+conHandle+" | Throwable raised while processing request with RequestProcessingPlugin '"+requestPlugin+"'",t);
					// TODO revert changes maybe ?
				}
			}
		} while (again);
		if (!lateProcessing) {
			conHandle.request.getServicesHandleInternal().finalize();
			if (!conHandle.rqLateProcessing) {
				// we just executed real-time processing and no late processing is planned
				doRequestLateProcessing(conHandle);
			}
		}
		if (sendResponse) {
			if (processResponse)
				return RequestProcessingActions.NEW_RESPONSE;
			else
				return RequestProcessingActions.FINAL_RESPONSE;
		} else if (transferOriginalBody) {
			return RequestProcessingActions.PROCEED;
		} else {
			return RequestProcessingActions.NEW_REQUEST;
		}
	}

	private void proceedWithRequest(final ConnectionHandle conHandle, final ClientResourceHandler resourceHandler) {
		HttpHeader header = conHandle.request.getHeader().getBackedHeader(); 
		conHandle.con.setKeepalive(new ConnectionSetupResolver(header).isKeepalive()); // plugins can decide whether to keep connection with client alive
		proxy.getNioHandler().runThreadTask(new Runnable() {
			@Override
			public void run() {
				if (log.isTraceEnabled()) {
					log.trace("RQ: "+conHandle+" | Request processing time :"+(System.currentTimeMillis()-conHandle.requestTime));
					log.trace("RQ: "+conHandle+" | Proceeding in handling request");
				}
				conHandle.con.processRequest(resourceHandler);
			}
		}, new DefaultTaskIdentifier(getClass().getSimpleName()+".requestAdvancing", requestSendingTaskInfo(conHandle)));
	}
	
	public void newResponse(Connection con, HttpHeader response) {
		ConnectionHandle conHandle = requestHandles.get(con);
		conHandle.responseTime = System.currentTimeMillis();
		HttpResponseImpl origResponse =  new HttpResponseImpl(modulesManager, new HeaderWrapper(response.clone()), conHandle.request);
		origResponse.setReadOnly();
		conHandle.response = new ModifiableHttpResponseImpl(modulesManager, new HeaderWrapper(response) ,origResponse);
		//conHandle.response.getHeader().setField("AgeORIG", conHandle.response.getWebResponseHeader().getField("Age"));
		conHandle.messageFactory.setResponse(conHandle.response);
		if (log.isTraceEnabled())
			log.trace("RP: "+conHandle+" | New response received ( "
					+response.getStatusLine()+" | requested "
					+conHandle.request.getHeader().getBackedHeader().getRequestLine()+") - "+conHandle.response);
	}
	
	public void newResponse(Connection con, HttpHeader header, final Runnable proceedTask) {
		if (log.isTraceEnabled())
			log.trace("RP: "+requestHandles.get(con)+" | New response constructed");
		if (requestHandles.get(con).response == null)
			newResponse(con, header);
		processResponse(con, proceedTask);
	}

	public boolean transferResponse(Connection con, HttpHeader response) {
		con.setMayCache(false);
		ConnectionHandle conHandle = requestHandles.get(con);
		Set<Class<? extends ProxyService>> desiredServices = new HashSet<Class<? extends ProxyService>>();
		for (ResponseProcessingPlugin responsePlugin : responsePlugins) {
			try {
				Set<Class<? extends ProxyService>> pluginsDesiredSvcs
					= new HashSet<Class<? extends ProxyService>>(); 
				responsePlugin.desiredResponseServices(pluginsDesiredSvcs,conHandle.response.getHeader());
				pluginsDesiredSvcs = new HashSet<Class<? extends ProxyService>>(pluginsDesiredSvcs); //call this "defensive copy"
				if (ServicesHandleBase.contentNeeded(pluginsDesiredSvcs)) {
					if (log.isDebugEnabled())
						log.debug("RP: "+conHandle+" | Plugin "+responsePlugin+" wants content modifying service for response");
					return true;
				} else
					desiredServices.addAll(pluginsDesiredSvcs);
			} catch (Throwable t) {
				log.info("RP: Throwable raised while obtaining set of desired services from plugin '"+responsePlugin+"'",t);
			}
		}
		if (conHandle.response.getServicesHandle().needContent(desiredServices))
			return true;
		if (log.isDebugEnabled())
			log.debug("RP: "+conHandle+" | No plugin wants content modifying service for response");
		conHandle.rpLateProcessing = true;
		return false;
	}

	public void processResponse(final Connection con, final Runnable proceedTask) {
		final ConnectionHandle conHandle = requestHandles.get(con);
		boolean adaptiveHandling = conHandle.handler instanceof AdaptiveHandler; 
		if ((!adaptiveHandling || conHandle.rpLateProcessing) && !proxyDying) {
			proxy.getNioHandler().runThreadTask(new Runnable() {
				@Override
				public void run() {
					boolean transferOrigBody = doResponseProcessing(conHandle,false); // header-only real-time processing
					if (!transferOrigBody) {
						// substitutive response was constructed
						conHandle.handler.setDontSendBytes();
						if (conHandle.response.getData() != null) {
							// substitutive response with body was constructed
							sendResponse(conHandle, false, true);
						}
					}
					proceedWithResponse(conHandle, proceedTask, transferOrigBody);
				}
			}, new DefaultTaskIdentifier(getClass().getSimpleName()+".responseProcessing", responseProcessingTaskInfo(conHandle)));
		} else {
			proceedTask.run(); // let the AdaptiveHandler cache response body (or if proxy dying, don't bother running task through NioHandler)
			if (proxyDying && log.isTraceEnabled())
				log.trace("RP: "+conHandle+" | Response processing time :"+(System.currentTimeMillis()-conHandle.responseTime));
		}
	}
	
	private void fixResponseHeader(ConnectionHandle conHandle) {
		Connection con = conHandle.con;
		HttpHeader header = conHandle.response.getHeader().getBackedHeader();
		con.setChunking(ConnectionSetupResolver.isChunked(header));
		con.fixResponseHeader(header, conHandle.handler.changesContentSize());
	}
	
	public void responseContentCached(Connection con, final byte[] responseContent, final AdaptiveHandler handler) {
		final ConnectionHandle conHandle = requestHandles.get(con);
		conHandle.response.setData(responseContent);
		conHandle.response.originalResponse().setData(responseContent);
		if (log.isTraceEnabled())
			log.trace("RP: "+conHandle+" | "+responseContent.length+" bytes of response cached for real-time processing");
		proxy.getNioHandler().runThreadTask(new Runnable() {
			@Override
			public void run() {
				doResponseProcessing(conHandle,false); // full response real-time processing
				final ModifiableHttpResponseImpl response = conHandle.response;
				final byte[] modifiedContent = conHandle.response.getData();
				proceedWithResponse(conHandle, new Runnable() {
					@Override
					public void run() {
						handler.sendResponse(response.getHeader().getBackedHeader(),modifiedContent);
					}
				}, true);
			}
		}, new DefaultTaskIdentifier(getClass().getSimpleName()+".responseProcessing", responseProcessingTaskInfo(conHandle)));
	}
	
	public void responseContentCached(Connection con, final byte[] responseContent) {
		ConnectionHandle tmpHandle = requestHandles.get(con);
		if (tmpHandle == null || tmpHandle.response == null) {
			tmpHandle = prevRequestHandles.remove(con);
		}
		final ConnectionHandle conHandle = tmpHandle;
		conHandle.response.originalResponse().setData(responseContent);
		if (log.isTraceEnabled())
			log.trace("RP: "+conHandle+" | "+responseContent.length+" bytes of response cached for late processing");
		doResponseLateProcessing(conHandle);
	}
	
	private void doResponseLateProcessing(final ConnectionHandle conHandle) {
		pluginHandler.submitTaskToThreadPool(new Runnable() {
			@Override
			public void run() {
				doResponseProcessing(conHandle, true);
				conHandle.rpLateProcessing = false; // to avoid saving conHandle in prevRequestHandles
			}
		});
	}
	
	private boolean doResponseProcessing(ConnectionHandle conHandle, boolean lateProcessing) {
		conHandle.response.setAllowedThread();
		if (log.isTraceEnabled()) {
			if (lateProcessing)
				log.trace("RP: "+conHandle+" | Running late response processing");
			else
				log.trace("RP: "+conHandle+" | Running real-time response processing");
		}
		boolean transferOriginalBody = true;
		boolean again = false;
		Set<ResponseProcessingPlugin> pluginsChangedResponse = new HashSet<ResponseProcessingPlugin>();
		do {
			again = false;
			for (ResponseProcessingPlugin responsePlugin : responsePlugins) {
				if (pluginsChangedResponse.contains(responsePlugin))
					// skip this plugin to prevent cycling
					continue;
				try {
					if (lateProcessing) {
						responsePlugin.processTransferedResponse(conHandle.response);
					} else {
						ResponseProcessingActions action = responsePlugin.processResponse(conHandle.response);
						if (action == ResponseProcessingActions.NEW_RESPONSE || action == ResponseProcessingActions.FINAL_RESPONSE) {
							HttpResponse newResponse = responsePlugin.getNewResponse(conHandle.response, conHandle.messageFactory);
							if (newResponse == null)
								log.warn("Null HttpResponse was provided by ResponseProcessingPlugin '"+responsePlugin+"' after calling getNewResponse()," +
											" substitution is being ignored.");
							else {
								// if FINAL_RESPONSE and same response was returned just to stop processing, we DO want to transfer original body
								if (newResponse != conHandle.response) {
									transferOriginalBody = false;
									conHandle.response = (ModifiableHttpResponseImpl) newResponse;
									conHandle.response.setAllowedThread();
								}
								if (action == ResponseProcessingActions.NEW_RESPONSE) {
									pluginsChangedResponse.add(responsePlugin);
									again = true;
								}
								break;
							}
						}
					}
				} catch (Throwable t) {
					log.info("RP: "+conHandle+" | Throwable raised while processing response with ResponseProcessingPlugin '"+responsePlugin+"'",t);
					// TODO revert changes maybe ?
				}
			}
		} while (again);
		if (!lateProcessing) {
			conHandle.response.getServicesHandleInternal().finalize();
			fixResponseHeader(conHandle);
			if (!conHandle.rpLateProcessing) {
				// we just executed real-time processing and no late processing is planned
				doResponseLateProcessing(conHandle);
			}
		}
		return transferOriginalBody;
	}
	
	private void proceedWithResponse(final ConnectionHandle conHandle, final Runnable proceedTask, final boolean logProceed) {
		proxy.getNioHandler().runThreadTask(new Runnable() {
			@Override
			public void run() {
				if (log.isTraceEnabled() && logProceed) {
					log.trace("RP: "+conHandle+" | Response processing time :"+(System.currentTimeMillis()-conHandle.responseTime));
					log.trace("RQ: "+conHandle+" | Proceeding in handling response");
				}
				proceedTask.run();
			}
		}, new DefaultTaskIdentifier(getClass().getSimpleName()+".responseAdvancing", responseSendingTaskInfo(conHandle)));
	}

	
	private void sendResponse(final ConnectionHandle conHandle, final boolean processResponse, final boolean processingDone) {
		proxy.getNioHandler().runThreadTask(new Runnable() {
			@Override
			public void run() {
				AdaptiveHandler handlerFactory = (AdaptiveHandler)conHandle.con.getProxy().getNamedHandlerFactory(AdaptiveHandler.class.getName());
				conHandle.handler = handlerFactory;
				if (processResponse) {
					doResponseProcessing(conHandle,false);
				} else if (!processingDone)
					conHandle.response.getServicesHandleInternal().finalize();
					doResponseLateProcessing(conHandle);
				if (log.isTraceEnabled())
					log.trace("RQ: "+conHandle+" | Sending response");
				Connection con = conHandle.con;
				TrafficLoggerHandler tlh = con.getTrafficLoggerHandler();
				HttpHeader requestHeader = conHandle.request.getHeader().getBackedHeader();
				final HttpHeader responseHeader = conHandle.response.getHeader().getBackedHeader();
				//conHandle.response.getProxyResponseHeaders().setHeader("Transfer-Encoding","chunked");
				handlerFactory.nextInstanceWillNotCache();
				final AdaptiveHandler sendingHandler = (AdaptiveHandler)handlerFactory.getNewInstance(conHandle.con, tlh, requestHeader, responseHeader, null, con.getMayCache(), con.getMayFilter(), -1);
				conHandle.handler = sendingHandler;
				conHandle.con.fixResponseHeader(responseHeader, sendingHandler.changesContentSize());
				proceedWithResponse(conHandle, new Runnable() {
					@Override
					public void run() {
						sendingHandler.sendResponse(responseHeader, conHandle.response.getData());
					}
				},true);
			}
		}, new DefaultTaskIdentifier(getClass().getSimpleName()+".responseProcessing", responseProcessingTaskInfo(conHandle)));
	}
	
	private String requestProcessingTaskInfo(ConnectionHandle conHandle) {
		return "Running plugins processing on request \""
			+ ((HttpRequestImpl)conHandle.request.originalRequest()).getHeader().getBackedHeader().getRequestLine()
			+ "\" from " + conHandle.request.clientSocketAddress();
	}

	private String responseProcessingTaskInfo (ConnectionHandle conHandle) {
		return "Running plugins processing on response \""
			+ ((HttpResponseImpl)conHandle.response.originalResponse()).getHeader().getBackedHeader().getStatusLine()
			+ "\" for request \"" + conHandle.request.getHeader().getBackedHeader().getRequestLine()
			+ "\" from " + conHandle.request.clientSocketAddress();
	}

	private String requestSendingTaskInfo(ConnectionHandle conHandle) {
		return "Proceeding in handling request \""
			+ conHandle.request.getHeader().getBackedHeader().getRequestLine()
			+ "\" (orig: \""+ ((HttpRequestImpl)conHandle.request.originalRequest()).getHeader().getBackedHeader().getRequestLine()
			+ "\") from " + conHandle.request.clientSocketAddress();
	}

	private String responseSendingTaskInfo (ConnectionHandle conHandle) {
		return "Proceeding in handling response \""
			+ conHandle.response.getHeader().getBackedHeader().getStatusLine()
			+ "\" (orig: \""+ ((HttpResponseImpl)conHandle.response.originalResponse()).getHeader().getBackedHeader().getStatusLine()
			+ "\") for request \"" + conHandle.request.getHeader().getBackedHeader().getRequestLine()
			+ "\" from " + conHandle.request.clientSocketAddress();
	}

	public HttpProxy getProxy() {
		return proxy;
	}

	public PluginHandler getPluginHandler() {
		return pluginHandler;
	}

	public EventsHandler getEventsHandler() {
		return loggingHandler;
	}

	public void setup(SProperties prop) {
		if (prop == null)
			prop = new SProperties();
		File confDir = proxy.getConfigFile().getParentFile();
		String log4jConfFile = prop.getProperty("logging_conf");
		File loggingConfFile = null;
		if (log4jConfFile != null) {
			loggingConfFile = new File(confDir,log4jConfFile); 
			if (!loggingConfFile.canRead())
				log4jConfFile = null;
		}
		if (loggingConfFile != null) {
			try {
				DOMConfigurator.configure(loggingConfFile.toURI().toURL());
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
		} else {
			String loggingLvL = prop.getProperty("logging_level");
			if (loggingLvL == null)
				loggingLvL = System.getProperty("sk.fiit.adaptiveproxy.logging_level", "INFO").trim();
			Level lvl = Level.toLevel(loggingLvL);
			Logger root = Logger.getRootLogger();
			root.setLevel(lvl);
			Logger.getLogger("org.apache").setLevel(Level.WARN);
			log.setLevel(lvl);
		    root.addAppender(new ConsoleAppender(new PatternLayout("%d{HH:mm:ss,SSS} [%22t] %-5p %-15c{1} %x - %m%n")));
			//BasicConfigurator.configure();
			log.info("No Log4j configuration file specified, using default configuration");
		}
		String pluginsHomeProp = prop.getProperty("plugins_home");
		boolean defaultPlgDir = pluginsHomeProp != null;
		if (pluginsHomeProp == null)
			pluginsHomeProp = "../plugins"; 
		File pluginsHomeDir = new File(confDir,pluginsHomeProp);
		String pluginsOrderFileName = prop.getProperty("plugins_order_file","plugins_ordering");
		pluginsOrderFile = new File(pluginsHomeDir,pluginsOrderFileName);
		if (!pluginsHomeDir.isDirectory() || !pluginsHomeDir.canRead()) {
			log.info("Unable to find or access "+((!defaultPlgDir)? "default ":"")+"plugins directory "+pluginsHomeDir.getAbsolutePath());
		} else {
			File svcsDir = new File(pluginsHomeDir,prop.getProperty("services_folder","services"));
			File sharedLibDir = null;
			String sharedLibDirName = prop.getProperty("shared_libs_folder","shared_libs");
			if (!sharedLibDirName.trim().isEmpty())
				sharedLibDir = new File(pluginsHomeDir,sharedLibDirName);
			Set<String> excludeFiles = new HashSet<String>();
			excludeFiles.add(pluginsOrderFileName);
			String coreThreadsNumber = prop.getProperty("thread_pool_core_threads");
			int coreThreads = -1;
			if (coreThreadsNumber == null)
				log.info("No setting for number of core threads of plugins thread pool, default value will be used");
			else {
				try {
					coreThreads = Integer.parseInt(coreThreadsNumber);
				} catch (Exception e) {
					log.info("Invalid number of core threads of plugins thread pool, default value will be used");
				}
			}
			pluginHandler.setup(pluginsHomeDir,svcsDir,sharedLibDir,excludeFiles,coreThreads);
		}
		modulesManager.setPattern(prop.getProperty("string_services_pattern"));
		reloadPlugins();
		log.info("AdaptiveProxy set up and ready for action");
	}
	
	public void reloadPlugins() {
		log.info("Reloading plugins");
		pluginHandler.reloadPlugins();
		loggingHandler.setup();
		modulesManager.initPlugins(pluginHandler);
		requestPlugins.clear();
		responsePlugins.clear();
		log.info("Loading request processing plugins");
		Set<RequestProcessingPlugin> requestPluginsSet = pluginHandler.getPlugins(RequestProcessingPlugin.class);
		log.info("Loading response processing plugins");
		Set<ResponseProcessingPlugin> responsePluginsSet = pluginHandler.getPlugins(ResponseProcessingPlugin.class);
		boolean pluginsOrderingSuccess;
		if (pluginsOrderFile != null && pluginsOrderFile.canRead()) {
			pluginsOrderingSuccess = addProcessingPluginsInOrder(pluginsOrderFile,requestPluginsSet,responsePluginsSet);
		} else {
			log.info("Unable to find or read plugins ordering file "+pluginsOrderFile.getAbsolutePath());
			pluginsOrderingSuccess = false;
		}
		if (!pluginsOrderingSuccess) {
			log.info("Loading of configuration of plugin ordering failed for some reason. "+
					"Processing plugins will not be called in some desired fashion");
		}
		requestPlugins.addAll(requestPluginsSet);
		responsePlugins.addAll(responsePluginsSet);
	}
	
	private boolean addProcessingPluginsInOrder(File pluginsOrderFile, Set<RequestProcessingPlugin> requestPluginsSet,
			Set<ResponseProcessingPlugin> responsePluginsSet) {
		Scanner sc = null;
		try {
			sc = new Scanner(new FileInputStream(pluginsOrderFile), "UTF-8");
		} catch (FileNotFoundException e) {
			log.info("Unable to locate plugins ordering file at "+pluginsOrderFile.getPath());
		}
		if (sc == null)
			return false;
		boolean wasRequestMark = false;
		boolean wasResponseMark = false;
		while (sc.hasNextLine()) {
			String line = sc.nextLine().trim();
			if (line.startsWith("#") || line.isEmpty())
				continue;
			if (!wasRequestMark && ORDERING_REQUEST_TEXT.equals(line)) {
				wasRequestMark = true;
				continue;
			}
			if (!wasResponseMark && ORDERING_RESPONSE_TEXT.equals(line)) {
				wasResponseMark = true;
				continue;
			}
			if (wasRequestMark) {
				boolean sucess = false;
				if (wasResponseMark)
					sucess = loadPlugin(line, responsePlugins, responsePluginsSet, ResponseProcessingPlugin.class);
				else
					sucess = loadPlugin(line, requestPlugins, requestPluginsSet, RequestProcessingPlugin.class);
				if (!sucess)
					log.info("Can't insert plugin with name '"+line+"' into "+((wasResponseMark)?"response":"resuest")+" processing order, because such plugin is not present");
			}
		}
		return true;
	}
	
	private <T extends ProxyPlugin> boolean loadPlugin(String pluginName, List<T> pluginsList,
		Set<T> pluginsSet, Class<T> pluginsClass) {
		T plugin = pluginHandler.getPlugin(pluginName, pluginsClass);
		if (plugin == null)
			return false;
		pluginsList.add(plugin);
		pluginsSet.remove(plugin);
		return true;
	}
	
	public List<RequestProcessingPlugin> getLoadedRequestPlugins() {
		List<RequestProcessingPlugin> retVal = new LinkedList<RequestProcessingPlugin>();
		retVal.addAll(requestPlugins);
		return retVal;
	}
	
	public List<ResponseProcessingPlugin> getLoadedResponsePlugins() {
		List<ResponseProcessingPlugin> retVal = new LinkedList<ResponseProcessingPlugin>();
		retVal.addAll(responsePlugins);
		return retVal;
	}
	
	public void setProxyIsDying() {
		proxyDying = true;
	}
	
	public boolean isProxyDying(){
		return proxyDying;
	}

	public void responseHandlerUsed(Connection connection, Handler handler) {
		ConnectionHandle conHandle = requestHandles.get(connection);
		conHandle.handler = handler;
		if (log.isTraceEnabled())
			log.trace("RP: "+conHandle+" | Handler "+handler.toString()+" used for response "+conHandle.response
					+" on " +conHandle.request.getHeader().getBackedHeader().getRequestLine());
	}

	public ModulesManager getModulesManager() {
		return modulesManager;
	}
}

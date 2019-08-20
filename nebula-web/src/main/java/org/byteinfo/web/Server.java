package org.byteinfo.web;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;
import org.byteinfo.context.Context;
import org.byteinfo.logging.Log;
import org.byteinfo.util.function.CheckedConsumer;
import org.byteinfo.util.io.IOUtil;
import org.byteinfo.util.misc.Config;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import static io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import static io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;

/**
 * Web Server
 */
public class Server extends Context {
	private final long start = System.currentTimeMillis();

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private DefaultEventExecutorGroup executorGroup;

	private List<CheckedConsumer<Server>> onStartHandlers = new ArrayList<>();
	private List<CheckedConsumer<Server>> onStopHandlers = new ArrayList<>();

	// HTTP Handlers: path -> (method -> route)
	Map<String, Map<String, Handler>> exactHandlers = new HashMap<>();
	Map<String, Map<String, Handler>> genericHandlers = new LinkedHashMap<>();

	// HTTP Secured Attributes
	Map<Handler, String> securedAttributes = new HashMap<>();

	// HTTP Interceptors
	List<Interceptor> interceptors = new ArrayList<>();

	// Error Handler
	ErrorHandler errorHandler = (req, rsp, ex) -> {
		if (ex instanceof WebException) {
			if (ex.getCause() != null) {
				Log.trace(ex.getCause(), "Unexpected error encountered while processing request: {} {}", req.method(), req.path());
			}
			rsp.status(((WebException) ex).getStatus());
			if (rsp.status() == HttpResponseStatus.UNAUTHORIZED) {
				rsp.result("401 Unauthenticated");  // clarify ambiguous
			} else if (rsp.status() == HttpResponseStatus.FORBIDDEN) {
				rsp.result("403 Unauthorized");  // clarify ambiguous
			} else {
				rsp.result(rsp.status().toString());
			}
		} else {
			Log.error(ex, "Unexpected error encountered while processing request: {} {}", req.method(), req.path());
			rsp.status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
			rsp.result(rsp.status().toString());
		}
	};

	/**
	 * Creates a server instance.
	 *
	 * @param modules
	 * @throws IOException
	 */
	public Server(Object... modules) throws IOException {
		super(modules);

		// load configurations
		Config.load("class:org/byteinfo/web/application.properties");
		Config.load("class:application.properties");

		// init runtime variables
		int processors = Runtime.getRuntime().availableProcessors();
		Config.load(Map.of(
				"runtime.pid", ProcessHandle.current().pid(),
				"runtime.processors", processors,
				"runtime.processors-2x", processors * 2,
				"runtime.processors-4x", processors * 4));

		// system properties have the highest priority
		Config.load(System.getProperties());

		// interpolate variables
		Config.interpolate();

		// track resource leak
		System.setProperty("io.netty.leakDetection.level", Config.get("resource.LeakDetection"));
	}

	/**
	 * Register MVC HTTP handlers
	 *
	 * @param classes
	 * @return
	 */
	public Server handler(Class<?>... classes) {
		for (Class<?> clazz : classes) {
			Path basePath = clazz.getAnnotation(Path.class);
			String path = basePath == null ? "" : basePath.value();
			for (Method method : clazz.getDeclaredMethods()) {
				List<HttpMethod> httpMethods = new ArrayList<>();
				String currentPath = path;
				for (Annotation annotation : method.getDeclaredAnnotations()) {
					if (annotation instanceof Path) {
						currentPath += ((Path) annotation).value();
					}
					if (annotation.annotationType().isAnnotationPresent(HttpMethod.class)) {
						httpMethods.add(annotation.annotationType().getAnnotation(HttpMethod.class));
					}
				}

				MVCHandler mvcHandler = new MVCHandler(instance(clazz), method);
				Secured annotation = method.getAnnotation(Secured.class);
				String secured = annotation == null ? null : annotation.value();
				handler(currentPath, httpMethods, mvcHandler, secured);
			}
		}
		return this;
	}

	/**
	 * Register Lambda HTTP handler
	 */
	public Server handler(String path, HttpMethod method, Handler handler) {
		return handler(path, method, handler, null);
	}

	/**
	 * Register Lambda HTTP handler
	 */
	public Server handler(String path, HttpMethod method, Handler handler, String secured) {
		return handler(path, List.of(method), handler, secured);
	}

	/**
	 * Register Lambda HTTP handler
	 *
	 * @param path
	 * @param methods
	 * @param handler
	 * @param secured
	 * @return
	 */
	public Server handler(String path, List<HttpMethod> methods, Handler handler, String secured) {
		Map<String, Map<String, Handler>> handlers = exactHandlers;
		if (path.endsWith("*")) {
			path = path.substring(0, path.length() - 1);
			handlers = genericHandlers;
		}
		for (HttpMethod method : methods) {
			handlers.computeIfAbsent(path, k -> new HashMap<>()).put(method.value(), handler);
		}
		securedAttributes.put(handler, secured);
		return this;
	}

	/**
	 * Register HTTP interceptors
	 *
	 * @param classes
	 * @return
	 */
	@SafeVarargs
	public final Server interceptor(Class<? extends Interceptor>... classes) {
		for (Class<? extends Interceptor> clazz : classes) {
			interceptors.add(instance(clazz));
		}
		return this;
	}

	/**
	 * Register Exception handler
	 *
	 * @param handler
	 * @return
	 */
	public Server error(Class<? extends ErrorHandler> handler) {
		errorHandler = instance(handler);
		return this;
	}

	/**
	 * Start the server.
	 *
	 * @return
	 */
	public Server start() {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

			AccessLog.init();

			bossGroup = new NioEventLoopGroup(Config.getInt("thread.boss"), new DefaultThreadFactory("nio-" + "boss"));
			workerGroup = new NioEventLoopGroup(Config.getInt("thread.worker"), new DefaultThreadFactory("nio-" + "worker"));
			executorGroup = new DefaultEventExecutorGroup(Config.getInt("thread.task"), new DefaultThreadFactory("task"));

			for (CheckedConsumer<Server> handler : onStartHandlers) {
				handler.accept(this);
			}

			LoggingHandler loggingHandler = new LoggingHandler(Server.class);
			bootstrap(loggingHandler, Config.getInt("http.port"), null);
			if (Config.getBoolean("ssl.enabled")) {
				bootstrap(loggingHandler, Config.getInt("ssl.port"), getSslContext());
			}

			Map<String, Set<String>> handlers = new TreeMap<>();
			exactHandlers.forEach((key, value) -> handlers.computeIfAbsent(key, k -> new TreeSet<>()).addAll(value.keySet()));
			genericHandlers.forEach((key, value) -> handlers.computeIfAbsent(key + "/*", k -> new TreeSet<>()).addAll(value.keySet()));
			Log.info("HTTP Handlers: {}", handlers);

			Log.info("Server started in {}ms.", System.currentTimeMillis() - start);
			return this;
		} catch (Exception ex) {
			stop();
			throw new WebException("An error occurred while starting the application:", ex);
		}
	}

	/**
	 * Stop the web server.
	 */
	public void stop() {
		Log.info("Stopping server...");
		for (CheckedConsumer<Server> handler : onStopHandlers) {
			try {
				handler.accept(this);
			} catch (Exception e) {
				Log.warn(e);
			}
		}
		for (EventExecutorGroup group : List.of(bossGroup, workerGroup, executorGroup)) {
			if (!group.isShuttingDown()) {
				group.shutdownGracefully();
			}
		}
		AccessLog.destroy();
		Log.info("Server stopped.");
	}

	/**
	 * Register server start handler.
	 *
	 * @param handler
	 * @return
	 */
	public Server onStart(CheckedConsumer<Server> handler) {
		onStartHandlers.add(handler);
		return this;
	}

	/**
	 * Register server stop handler.
	 *
	 * @param handler
	 * @return
	 */
	public Server onStop(CheckedConsumer<Server> handler) {
		onStopHandlers.add(handler);
		return this;
	}

	private Channel bootstrap(LoggingHandler loggingHandler, int port, SslContext sslContext) throws InterruptedException {
		return new ServerBootstrap()
				.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.handler(loggingHandler)
				.childHandler(new ServerInitializer(this, executorGroup, sslContext))
				.childOption(ChannelOption.TCP_NODELAY, true)
				.bind(port).sync().channel();
	}

	private SslContext getSslContext() throws IOException {
		String keyStoreCert = Config.get("ssl.cert");
		String keyStoreKey = Config.get("ssl.key");
		SslContextBuilder builder = SslContextBuilder.forServer(IOUtil.getStream(keyStoreCert), IOUtil.getStream(keyStoreKey), Config.get("ssl.password"));
		if (Config.getBoolean("http.h2")) {
			builder.applicationProtocolConfig(new ApplicationProtocolConfig(
					Protocol.ALPN, SelectorFailureBehavior.NO_ADVERTISE, SelectedListenerFailureBehavior.ACCEPT,
					ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1)
			);
		}
		return builder.build();
	}
}

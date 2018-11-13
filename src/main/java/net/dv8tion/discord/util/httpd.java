package net.dv8tion.discord.util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.dv8tion.discord.bridge.IrcConnection;

@SuppressWarnings("restriction")
public class httpd {
	static HttpServer server;
	static String baseDomain;
	public static Map<String, String> pages = new LinkedHashMap<String, String>();
	public static void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress(9790), 0);
		registerContext("/", new IndexHandler(), "Home");
	}
    /**
     * Creates a route from a URL to a HttpHandler
     * @param route
     * @param handlerIn
     * @param pageName
     */
	public static void registerContext(String route,  HttpHandler handlerIn, String pageName) {
		if(server != null) {
			//IRCBot.log.info("Adding " + pageName + " to page list");
			pages.put(pageName, route);
			server.createContext(route, handlerIn);
		}
    }
    
	public static void start() throws Exception {
		if(server != null) {
			//IRCBot.log.info("Starting HTTPD On port " + Config.httpdport + " Base domain: " + Config.httpdBaseDomain);
			server.setExecutor(null); // creates a default executor
	        server.start();
		} else {
			//IRCBot.log.error("httpd server was null!");
		}
    }
	public static void setBaseDomain(String httpdBaseDomain) {
		baseDomain = httpdBaseDomain;
	}
	
	public static String getBaseDomain() {
		return baseDomain;
	}
	
	static class IndexHandler implements HttpHandler {
		
		static String html;
		@Override
		public void handle(HttpExchange t) throws IOException {
			String target = t.getRequestURI().toString();
			String response = IrcConnection.getIDFromUser(target);
			t.sendResponseHeaders(200, response.getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}
}

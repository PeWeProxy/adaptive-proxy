package rabbit.meta;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import rabbit.http.HttpHeader;
import rabbit.proxy.Connection;
import rabbit.proxy.HtmlPage;
import rabbit.util.SProperties;
import rabbit.util.TrafficLogger;
import sk.fiit.peweproxy.AdaptiveEngine;
import sk.fiit.peweproxy.plugins.PluginHandler;
import sk.fiit.peweproxy.plugins.ProxyPlugin;
import sk.fiit.peweproxy.plugins.events.ConnectionEventPlugin;
import sk.fiit.peweproxy.plugins.events.FailureEventPlugin;
import sk.fiit.peweproxy.plugins.events.TimeoutEventPlugin;
import sk.fiit.peweproxy.plugins.processing.RequestProcessingPlugin;
import sk.fiit.peweproxy.plugins.processing.ResponseProcessingPlugin;
import sk.fiit.peweproxy.plugins.services.RequestServiceModule;
import sk.fiit.peweproxy.plugins.services.ResponseServiceModule;
import sk.fiit.peweproxy.plugins.services.ServiceModule;
import sk.fiit.peweproxy.services.ModulesManager;
import sk.fiit.peweproxy.services.ProxyService;

public class AdaptiveProxyStatus extends BaseMetaHandler {
	AdaptiveEngine adaptiveEngine = null;
	PluginHandler pluginHandler = null;
	boolean reloadURL = false;
	
	@Override
	public void handle(HttpHeader request, SProperties htab, Connection con,
			TrafficLogger tlProxy, TrafficLogger tlClient) throws IOException {
		adaptiveEngine = con.getProxy().getAdaptiveEngine();
		pluginHandler = adaptiveEngine.getPluginHandler();
		if (htab.getProperty("argstring", "").endsWith("reload")) {
			reloadURL = true;
			adaptiveEngine.reloadPlugins();
		}
		super.handle(request, htab, con, tlProxy, tlClient);
	}

	
	@Override
	protected PageCompletion addPageInformation(StringBuilder sb) {
		addPluginsPart(sb);
		
		sb.append("<table width=\"100%\">\n<tr>\n<td align=\"center\">");
		sb.append("<form action=\"");
		if (!reloadURL)
			sb.append("AdaptiveProxyStatus/reload");
		sb.append("\" method=\"get\">\n<input type=\"submit\" value=\"Reload plugins\"/>\n</form><br>\n");
		sb.append("</td>\n</tr>\n</table>");

		addModulesPart(sb);
		addProcessingPluginsPart(sb);
		addEventPluginsPart(sb);
		addLogPart(sb);
		return PageCompletion.PAGE_DONE;
	}
	
	private void addPluginsPart(StringBuilder sb) {
		sb.append ("<p><h2>Loaded proxy plugins</h2></p>\n");
		sb.append (HtmlPage.getTableHeader (100, 1));
		sb.append (HtmlPage.getTableTopicRow ());
		sb.append ("<th width=\"20%\">Plugin name</th>");
		sb.append ("<th width=\"60%\">Plugin class</th>");
		sb.append ("<th width=\"20%\">Plugin types</th>\n");
		List<String> pluginNames = pluginHandler.getLoadedPluginNames();
		for (String pluginName : pluginNames) {
			ProxyPlugin plugin = pluginHandler.getPlugin(pluginName, ProxyPlugin.class);
			sb.append ("<tr><td>");
			sb.append(pluginName);
			sb.append ("</td>\n<td>");
			sb.append(plugin.getClass().getName());
			sb.append ("</td>\n<td>\n");
			List<String> pluginTypes = pluginHandler.getTypesOfPlugin(plugin);
			for (String pluginType : pluginTypes) {
				sb.append(pluginType);
				sb.append("<br>\n");
			}
			sb.append ("</td></tr>\n");
		}
		sb.append ("</table>\n<br>\n");
	}
	
	private void addModulesPart(StringBuilder sb) {
		sb.append ("<p><h2>Service modules summary</h2></p>\n");
		sb.append (HtmlPage.getTableHeader (100, 1));
		sb.append (HtmlPage.getTableTopicRow ());
		sb.append ("<th width=\"20%\">Plugin name</th>");
		sb.append ("<th width=\"70%\">Provided services</th>\n");
		sb.append ("<th width=\"5%\">RQ</th>\n");
		sb.append ("<th width=\"5%\">RP</th>\n");
		ModulesManager modulesManager = adaptiveEngine.getModulesManager();
		List<RequestServiceModule> rqServicePlugins = modulesManager.getLoadedRequestModules();
		List<ResponseServiceModule> rpServicePlugins = modulesManager.getLoadedResponsetModules();
		Set<ServiceModule> loadedModules = new LinkedHashSet<ServiceModule>();
		loadedModules.addAll(rqServicePlugins);
		loadedModules.addAll(rpServicePlugins);
		for (ServiceModule module : loadedModules) {
			sb.append ("<tr><td>");
			sb.append(pluginHandler.getPluginName(module));
			sb.append ("</td>\n<td>\n");
			boolean rq = (rqServicePlugins.contains(module));
			boolean rp = (rpServicePlugins.contains(module));
			sb.append ("<b>Services for requests:</b><br>\n");
			if (rq) {
				for (Class<? extends ProxyService> svcClass : modulesManager.getProvidedRequestServices((RequestServiceModule)module)) {
					sb.append(svcClass.getName());
					sb.append("<br>\n");
				}
			}
			sb.append ("<b>Services for responses:</b><br>\n");
			if (rq) {
				for (Class<? extends ProxyService> svcClass : modulesManager.getProvidedResponseServices((ResponseServiceModule)module)) {
					sb.append(svcClass.getName());
					sb.append("<br>\n");
				}
			}
			sb.append ("</td>\n<td align=\"center\">\n");
			if (rq)
				sb.append ("<b>X</b>");
			else
				sb.append ("&nbsp");
			sb.append ("</td>\n<td align=\"center\">\n");
			if (rp)
				sb.append ("<b>X</b>");
			else
				sb.append ("&nbsp");
			sb.append ("</td></tr>");
		}
		sb.append ("</table>\n<br>\n");
	}
	
	private void addProcessingPluginsPart(StringBuilder sb) {
		sb.append ("<p><h2>Processing plugins summary</h2></p>\n");
		sb.append (HtmlPage.getTableHeader (100, 1));
		sb.append (HtmlPage.getTableTopicRow ());
		sb.append ("<th width=\"90%\">Plugin name</th>");
		sb.append ("<th width=\"5%\">RQ</th>\n");
		sb.append ("<th width=\"5%\">RP</th>\n");
		List<RequestProcessingPlugin> rqPlugins = adaptiveEngine.getLoadedRequestPlugins();
		List<ResponseProcessingPlugin> rpPlugins = adaptiveEngine.getLoadedResponsePlugins();
		Set<ProxyPlugin> loadedPlugins = new LinkedHashSet<ProxyPlugin>();
		loadedPlugins.addAll(rqPlugins);
		loadedPlugins.addAll(rpPlugins);
		for (ProxyPlugin plugin : loadedPlugins) {
			sb.append ("<tr><td>");
			sb.append(pluginHandler.getPluginName(plugin));
			sb.append ("</td>\n<td align=\"center\">\n");
			if (rqPlugins.contains(plugin))
				sb.append ("<b>X</b>");
			else
				sb.append ("&nbsp");
			sb.append ("</td>\n<td align=\"center\">\n");
			if (rpPlugins.contains(plugin))
				sb.append ("<b>X</b>");
			else
				sb.append ("&nbsp");
			sb.append ("</td></tr>");
		}
		sb.append ("</table>\n<br>\n");
	}
	
	private void addEventPluginsPart(StringBuilder sb) {
		sb.append ("<p><h2>Event plugins summary</h2></p>\n");
		sb.append (HtmlPage.getTableHeader (100, 1));
		sb.append (HtmlPage.getTableTopicRow ());
		sb.append ("<th width=\"70%\">Plugin name</th>");
		sb.append ("<th width=\"10%\">Connection</th>\n");
		sb.append ("<th width=\"10%\">Timeout</th>\n");
		sb.append ("<th width=\"10%\">Failed</th>\n");
		List<ConnectionEventPlugin> cePlugins = adaptiveEngine.getEventsHandler().getLoadedConnectionEventPlugins();
		List<TimeoutEventPlugin> toPlugins = adaptiveEngine.getEventsHandler().getLoadedTimeoutEventPlugins();
		List<FailureEventPlugin> flPlugins = adaptiveEngine.getEventsHandler().getLoadedFailureEventPlugins();
		Set<ProxyPlugin> loadedPlugins = new LinkedHashSet<ProxyPlugin>();
		loadedPlugins.addAll(cePlugins);
		loadedPlugins.addAll(toPlugins);
		loadedPlugins.addAll(flPlugins);
		synchronized (pluginHandler) {
			for (ProxyPlugin plugin : loadedPlugins) {
				sb.append ("<tr><td>");
				sb.append(pluginHandler.getPluginName(plugin));
				sb.append ("</td>\n<td align=\"center\">\n");
				if (cePlugins.contains(plugin))
					sb.append ("<b>X</b>");
				else
					sb.append ("&nbsp");
				sb.append ("</td>\n<td align=\"center\">\n");
				if (toPlugins.contains(plugin))
					sb.append ("<b>X</b>");
				else
					sb.append ("&nbsp");
				sb.append ("</td>\n<td align=\"center\">\n");
				if (flPlugins.contains(plugin))
					sb.append ("<b>X</b>");
				else
					sb.append ("&nbsp");
				sb.append ("</td></tr>");
			}
		}
		sb.append ("</table>\n<br>\n");
	}
	
	private void addLogPart(StringBuilder sb) {
		sb.append("<p><h2>Plugins loading log</h2></p>\n");
		//sb.append("<table border=\"1\" style=\"max-width:100%;\">\n");
		//sb.append("<tr>\n<td>\n");
		sb.append("<div height=\"200px\" width=\"100%\" style=\"overflow:scroll; white-space:nowrap; border-style:solid;\">\n");
		Scanner sc = new Scanner(pluginHandler.getLoadingLogText());
		while (sc.hasNextLine())
			sb.append(colorLogLine(sc.nextLine())+"<br>\n");
		sb.append("</div>\n");
		//sb.append("</td>\n</tr>\n</table>\n");
	}
	
	private String colorLogLine(String lineText) {
		//13:52:48,797 INFO   - Can not read variables file ...
		String logLvl = lineText.substring(13, lineText.indexOf(' ', 13));
		String style = "black";
		if ("TRACE".equals(logLvl))
			style = "yellow";
		else if ("DEBUG".equals(logLvl))
			style = "green";
		else if ("INFO".equals(logLvl))
			style = "blue";
		else if ("WARN".equals(logLvl))
			style = "red; font-weight:bold";
		else if ("ERROR".equals(logLvl))
			style = "purple; font-weight:bold";
		else if ("FATAL".equals(logLvl))
			style = "black; font-weight:bold";
		lineText = lineText.replaceAll(" ", "&nbsp;");
		lineText = "<code style=\"color:" + style + ";\">" + lineText + "</code>";
		return lineText;
	}
	@Override
	protected String getPageHeader() {
		return "AdaptiveProxy status page";
	}

}
